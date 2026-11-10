package com.screentranslator

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

/**
 * Фоновый сервис: захватывает экран, показывает плавающую кнопку «译» и слой с переводом.
 *  • тап по кнопке — перевести экран / убрать перевод
 *  • долгое нажатие — режим реального времени (перевод каждые ~2 сек)
 *  • кнопку можно перетаскивать
 */
class TranslatorService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.screentranslator.STOP"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1
        private const val AUTO_INTERVAL_MS = 2000L
        @Volatile var isRunning = false
    }

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bgThread = HandlerThread("capture").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val frameLock = Any()
    private var lastImage: Image? = null
    private var screenW = 0
    private var screenH = 0
    private var dpi = 0

    private var bubble: TextView? = null
    private var bubbleParams: WindowManager.LayoutParams? = null
    private var overlay: OverlayView? = null
    private var overlayShown = false
    private var busy = false
    private var autoJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        isRunning = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()

        if (projection == null) {
            val code = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
                intent?.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent?.getParcelableExtra(EXTRA_RESULT_DATA)

            if (data == null || !startProjection(code, data)) {
                toast("Не удалось получить доступ к экрану")
                stopSelf()
                return START_NOT_STICKY
            }
            addOverlay()
            addBubble()
        }
        return START_NOT_STICKY
    }

    // ---------- Уведомление (обязательно для захвата экрана) ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Переводчик экрана", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, TranslatorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Переводчик работает")
            .setContentText("Тап по «译» — перевод, долгое нажатие — авто-режим")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Остановить", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    // ---------- Захват экрана ----------

    private fun realScreenSize(): Pair<Int, Int> {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = windowManager.maximumWindowMetrics.bounds
            b.width() to b.height()
        } else {
            val m = DisplayMetrics()
            @Suppress("DEPRECATION") windowManager.defaultDisplay.getRealMetrics(m)
            m.widthPixels to m.heightPixels
        }
    }

    private fun startProjection(code: Int, data: Intent): Boolean {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = mpm.getMediaProjection(code, data) ?: return false
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, Handler(Looper.getMainLooper()))
        projection = p

        val (w, h) = realScreenSize()
        screenW = w; screenH = h
        dpi = resources.displayMetrics.densityDpi
        val reader = newReader(w, h)
        imageReader = reader
        virtualDisplay = p.createVirtualDisplay(
            "screen-translator", w, h, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface, null, bgHandler
        )
        return true
    }

    private fun newReader(w: Int, h: Int): ImageReader {
        val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        // Всегда держим последний кадр — даже если экран статичен
        reader.setOnImageAvailableListener({ r ->
            val img = try { r.acquireLatestImage() } catch (e: Exception) { null } ?: return@setOnImageAvailableListener
            synchronized(frameLock) {
                lastImage?.close()
                lastImage = img
            }
        }, bgHandler)
        return reader
    }

    /** Игры часто в горизонтали — при повороте пересоздаём буфер под новый размер. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val (w, h) = realScreenSize()
        if (w == screenW && h == screenH || virtualDisplay == null) return
        screenW = w; screenH = h
        hideTranslation()
        val old = imageReader
        val reader = newReader(w, h)
        imageReader = reader
        virtualDisplay?.resize(w, h, dpi)
        virtualDisplay?.surface = reader.surface
        synchronized(frameLock) {
            lastImage?.close()
            lastImage = null
        }
        old?.close()
    }

    private fun grabFrame(): Bitmap? = synchronized(frameLock) {
        val image = lastImage ?: return null
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, image.width, image.height).also { padded.recycle() }
    }

    // ---------- Перевод ----------

    private suspend fun translateScreen() {
        if (busy) return
        busy = true
        try {
            // Прячем свои окна, чтобы они не попали в кадр, и ждём свежий кадр
            overlay?.visibility = View.INVISIBLE
            bubble?.visibility = View.INVISIBLE
            delay(180)
            val bitmap = withContext(Dispatchers.Default) { grabFrame() }
            bubble?.visibility = View.VISIBLE
            if (bitmap == null) {
                toast("Кадр ещё не готов, попробуй снова")
                return
            }
            val blocks = Engine.process(bitmap)
            bitmap.recycle()
            overlay?.show(blocks)
            overlay?.visibility = View.VISIBLE
            overlayShown = true
            if (blocks.isEmpty() && autoJob == null) toast("Китайский текст не найден")
        } catch (e: Exception) {
            bubble?.visibility = View.VISIBLE
            toast("Ошибка: ${e.message}")
        } finally {
            busy = false
        }
    }

    private fun hideTranslation() {
        overlay?.show(emptyList())
        overlayShown = false
    }

    private fun toggleAuto() {
        val job = autoJob
        if (job != null) {
            job.cancel()
            autoJob = null
            setBubbleColor(false)
            hideTranslation()
            toast("Авто-режим выключен")
        } else {
            setBubbleColor(true)
            toast("Авто-режим: перевод каждые ${AUTO_INTERVAL_MS / 1000} сек")
            autoJob = scope.launch {
                while (isActive) {
                    translateScreen()
                    delay(AUTO_INTERVAL_MS)
                }
            }
        }
    }

    // ---------- Окна поверх игр ----------

    private fun overlayParams(w: Int, h: Int, extraFlags: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or extraFlags,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun addOverlay() {
        val view = OverlayView(this)
        // NOT_TOUCHABLE — касания проходят сквозь перевод в игру
        val params = overlayParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        )
        windowManager.addView(view, params)
        overlay = view
    }

    private fun setBubbleColor(auto: Boolean) {
        (bubble?.background as? GradientDrawable)?.setColor(
            if (auto) Color.argb(230, 220, 60, 60) else Color.argb(210, 40, 110, 230)
        )
    }

    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (52 * d).toInt()
        val view = TextView(this).apply {
            text = "译"
            textSize = 22f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        val params = overlayParams(size, size, 0).apply {
            x = (12 * d).toInt()
            y = (200 * d).toInt()
        }
        bubble = view
        bubbleParams = params
        setBubbleColor(false)

        val longPressMs = 600L
        val handler = Handler(Looper.getMainLooper())
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; toggleAuto() }

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, longPressMs)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - touchX
                    val dy = e.rawY - touchY
                    if (!dragging && (abs(dx) > 10 * d || abs(dy) > 10 * d)) {
                        dragging = true
                        handler.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (e.action == MotionEvent.ACTION_UP && !dragging && !longPressed) onBubbleTap()
                }
            }
            true
        }
        windowManager.addView(view, params)
    }

    private fun onBubbleTap() {
        if (autoJob != null) { toggleAuto(); return }
        if (overlayShown) hideTranslation() else scope.launch { translateScreen() }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        bubble?.let { runCatching { windowManager.removeView(it) } }
        overlay?.let { runCatching { windowManager.removeView(it) } }
        virtualDisplay?.release()
        synchronized(frameLock) { lastImage?.close(); lastImage = null }
        imageReader?.close()
        projection?.stop()
        bgThread.quitSafely()
        super.onDestroy()
    }
}
