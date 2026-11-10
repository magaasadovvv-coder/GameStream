package com.screentranslator

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
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
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CancellationException
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
 * Фоновый сервис: захватывает экран и сам, в реальном времени, показывает перевод поверх него.
 *
 * Чтобы перевод не мигал, экран не перечитывается по таймеру. Сервис дважды в секунду
 * сравнивает кадры и действует по ситуации:
 *  • ничего не изменилось — перевод просто остаётся на месте;
 *  • появился новый текст — он допереводится, старый перевод не трогается;
 *  • экран сменился целиком — старый перевод убирается, новый появляется, когда картинка успокоится.
 *
 * Кнопка «译»: тап — пауза/продолжить, долгое нажатие — закрыть переводчик, её можно перетаскивать.
 */
class TranslatorService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.screentranslator.STOP"
        private const val CHANNEL_ID = "translator"
        private const val NOTIF_ID = 1

        private const val CYCLE_MS = 450L            // как часто сравниваем кадры
        private const val SCENE_CHANGE = 0.25f       // доля изменившегося экрана = «другой экран»
        private const val MIN_CHANGED_CELLS = 3      // меньше — считаем, что ничего не менялось
        private const val ADD_INTERVAL_MS = 1200L    // не чаще дочитываем новый текст
        private const val RECHECK_MS = 8000L         // при живой картинке — полная перепроверка
        private const val THRESHOLD = 28             // порог разницы яркости клетки

        @Volatile var isRunning = false
    }

    private class Diff(val changed: Int, val fraction: Float)

    private lateinit var windowManager: WindowManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val bgThread = HandlerThread("capture").apply { start() }
    private val bgHandler = Handler(bgThread.looper)

    private var projection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val frameLock = Any()
    private var lastImage: Image? = null
    private var frameCount = 0L
    private var screenW = 0
    private var screenH = 0
    private var dpi = 0

    private var bubble: TextView? = null
    private var overlay: OverlayView? = null
    private var autoJob: Job? = null
    private var errorShown = false
    private val maskPaint = Paint().apply { color = Color.rgb(40, 40, 44) }

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
            setActive(true)
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
            .setContentText("Тап по «译» — пауза, долгое нажатие — закрыть")
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
                frameCount++
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
        overlay?.show(emptyList())
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

    /** Копия последнего кадра (в него можно рисовать). */
    private fun grabFrame(): Bitmap? = synchronized(frameLock) {
        val image = lastImage ?: return null
        val plane = image.planes[0]
        val pixelStride = plane.pixelStride
        val rowPadding = plane.rowStride - pixelStride * image.width
        val padded = Bitmap.createBitmap(image.width + rowPadding / pixelStride, image.height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (rowPadding == 0) padded
        else Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            .let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true) }
            .also { if (it !== padded) padded.recycle() }
    }

    private fun gridCols(w: Int, h: Int) = if (w >= h) 48 else 27
    private fun gridRows(w: Int, h: Int) = (gridCols(w, h) * h / w).coerceAtLeast(1)

    /** «Отпечаток» кадра: яркость в узлах сетки. Считается прямо из буфера, без копирования кадра. */
    private fun signature(): IntArray? = synchronized(frameLock) {
        val image = lastImage ?: return null
        val plane = image.planes[0]
        val buf = plane.buffer
        val w = image.width
        val h = image.height
        val cols = gridCols(w, h)
        val rows = gridRows(w, h)
        val out = IntArray(cols * rows)
        val limit = buf.limit()
        for (r in 0 until rows) {
            val y = ((r + 0.5f) * h / rows).toInt().coerceIn(0, h - 1)
            for (c in 0 until cols) {
                val x = ((c + 0.5f) * w / cols).toInt().coerceIn(0, w - 1)
                val i = y * plane.rowStride + x * plane.pixelStride
                if (i + 2 >= limit) continue
                val red = buf.get(i).toInt() and 0xff
                val green = buf.get(i + 1).toInt() and 0xff
                val blue = buf.get(i + 2).toInt() and 0xff
                out[r * cols + c] = (red * 3 + green * 6 + blue) / 10
            }
        }
        out
    }

    /** Какие клетки сетки закрыты нашим переводом или кнопкой — их в сравнении не учитываем. */
    private fun coverMask(size: Int, rects: List<Rect>): BooleanArray {
        val cols = gridCols(screenW, screenH)
        val rows = gridRows(screenW, screenH)
        val mask = BooleanArray(size)
        if (cols * rows != size) return mask
        val cellW = screenW.toFloat() / cols
        val cellH = screenH.toFloat() / rows
        for (rect in rects) {
            val c0 = ((rect.left / cellW) - 1).toInt().coerceIn(0, cols - 1)
            val c1 = ((rect.right / cellW) + 1).toInt().coerceIn(0, cols - 1)
            val r0 = ((rect.top / cellH) - 1).toInt().coerceIn(0, rows - 1)
            val r1 = ((rect.bottom / cellH) + 1).toInt().coerceIn(0, rows - 1)
            for (r in r0..r1) for (c in c0..c1) mask[r * cols + c] = true
        }
        return mask
    }

    private fun diff(a: IntArray, b: IntArray, maskA: BooleanArray?, maskB: BooleanArray): Diff {
        var changed = 0
        var total = 0
        for (i in a.indices) {
            if (maskB[i] || (maskA != null && maskA[i])) continue
            total++
            if (abs(a[i] - b[i]) > THRESHOLD) changed++
        }
        return Diff(changed, if (total == 0) 0f else changed.toFloat() / total)
    }

    private fun bubbleRect(): Rect? {
        val b = bubble ?: return null
        val loc = IntArray(2)
        b.getLocationOnScreen(loc)
        return Rect(loc[0], loc[1], loc[0] + b.width, loc[1] + b.height)
    }

    /** Закрашивает области, которые распознавать не нужно (нашу кнопку и уже показанный перевод). */
    private fun mask(bitmap: Bitmap, rects: List<Rect>) {
        if (rects.isEmpty()) return
        val canvas = Canvas(bitmap)
        for (r in rects) canvas.drawRect(r, maskPaint)
    }

    // ---------- Перевод в реальном времени ----------

    private fun setActive(on: Boolean) {
        autoJob?.cancel()
        autoJob = null
        overlay?.show(emptyList())
        (bubble?.background as? GradientDrawable)?.setColor(
            if (on) Color.argb(190, 40, 110, 230) else Color.argb(150, 90, 90, 100)
        )
        if (on) autoJob = scope.launch { autoLoop() }
    }

    private suspend fun autoLoop() {
        var prev: IntArray? = null
        var prevMask: BooleanArray? = null
        var needFull = true
        var unsettled = 0
        var dirty = false
        var quiet = 0
        var lastFull = 0L
        var lastAdd = 0L

        while (scope.isActive) {
            delay(CYCLE_MS)
            try {
                val ov = overlay ?: continue
                val sig = withContext(Dispatchers.Default) { signature() } ?: continue
                val maskNow = coverMask(sig.size, ov.coveredRects() + listOfNotNull(bubbleRect()))
                val before = prev
                val d = if (before != null && before.size == sig.size) diff(before, sig, prevMask, maskNow) else null
                prev = sig
                prevMask = maskNow

                if (d == null) {
                    needFull = true                       // первый кадр или поворот экрана
                } else if (d.fraction > SCENE_CHANGE) {
                    // Другой экран: старый перевод больше не к месту — убираем сразу
                    ov.show(emptyList())
                    needFull = true
                    if (++unsettled <= 2) continue        // ждём, пока картинка успокоится (до ~1 с)
                }

                if (needFull) {
                    translateAll(ov)
                    needFull = false
                    unsettled = 0
                    dirty = false
                    quiet = 0
                    lastFull = SystemClock.elapsedRealtime()
                    continue
                }

                val change = d ?: continue
                val now = SystemClock.elapsedRealtime()
                if (change.changed >= MIN_CHANGED_CELLS) {
                    // Что-то поменялось рядом: дочитываем новый текст, показанный перевод не трогаем
                    dirty = true
                    quiet = 0
                    if (now - lastAdd > ADD_INTERVAL_MS) {
                        lastAdd = now
                        translateNew(ov)
                    }
                    if (now - lastFull > RECHECK_MS) needFull = true
                } else {
                    // Экран замер — один раз перепроверяем, не устарел ли перевод
                    quiet++
                    if (dirty && quiet >= 2 && now - lastFull > 2500) needFull = true
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!errorShown) {
                    errorShown = true
                    toast("Не удалось перевести: открой приложение и скачай языки")
                }
                delay(3000)
            }
        }
    }

    /** Перевести весь экран. Если перевод уже показан — прячем его на долю секунды, чтобы прочитать текст под ним. */
    private suspend fun translateAll(ov: OverlayView) {
        val hide = !ov.isEmpty
        if (hide) {
            ov.visibility = View.INVISIBLE
            awaitFreshFrames()
        }
        val bitmap = withContext(Dispatchers.Default) { grabFrame() }
        if (hide) ov.visibility = View.VISIBLE
        bitmap ?: return
        mask(bitmap, listOfNotNull(bubbleRect()))
        val blocks = Engine.process(bitmap)
        bitmap.recycle()
        ov.show(blocks)
        errorShown = false
    }

    /** Перевести только текст вне уже показанного перевода — без мигания. */
    private suspend fun translateNew(ov: OverlayView) {
        val covered = ov.coveredRects()
        val bitmap = withContext(Dispatchers.Default) { grabFrame() } ?: return
        mask(bitmap, covered + listOfNotNull(bubbleRect()))
        val blocks = Engine.process(bitmap, skip = covered)
        bitmap.recycle()
        ov.add(blocks)
    }

    /** Ждём пару свежих кадров после того, как спрятали перевод (обычно 30–60 мс). */
    private suspend fun awaitFreshFrames() {
        val start = synchronized(frameLock) { frameCount }
        repeat(25) {
            delay(10)
            if (synchronized(frameLock) { frameCount } >= start + 2) return
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

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        val d = resources.displayMetrics.density
        val size = (44 * d).toInt()
        val view = TextView(this).apply {
            text = "译"
            textSize = 18f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        }
        val params = overlayParams(size, size, 0).apply {
            x = (12 * d).toInt()
            y = (200 * d).toInt()
        }
        bubble = view

        val handler = Handler(Looper.getMainLooper())
        var startX = 0; var startY = 0
        var touchX = 0f; var touchY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            toast("Переводчик закрыт")
            stopSelf()
        }

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    touchX = e.rawX; touchY = e.rawY
                    dragging = false; longPressed = false
                    handler.postDelayed(longPress, 900)
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
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longPress)
                    if (e.action == MotionEvent.ACTION_UP && !dragging && !longPressed) {
                        val turnOn = autoJob == null
                        setActive(turnOn)
                        toast(if (turnOn) "Перевод включён" else "Перевод на паузе")
                    }
                }
            }
            true
        }
        windowManager.addView(view, params)
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
