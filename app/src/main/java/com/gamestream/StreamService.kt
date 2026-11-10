package com.gamestream

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.pedro.common.ConnectChecker
import com.pedro.encoder.input.sources.audio.AudioSource
import com.pedro.encoder.input.sources.audio.InternalAudioSource
import com.pedro.encoder.input.sources.audio.MicrophoneSource
import com.pedro.encoder.input.sources.audio.MixAudioSource
import com.pedro.encoder.input.sources.video.NoVideoSource
import com.pedro.encoder.input.sources.video.ScreenSource
import com.pedro.library.generic.GenericStream
import kotlin.math.abs

/**
 * Фоновый сервис стрима. Захватывает экран + звук и отправляет:
 *  • на YouTube по RTMP, или
 *  • на ПК в OBS по SRT (Wi‑Fi).
 *
 * Плавающая кнопка во время игры:
 *  • тап — выключить/включить микрофон
 *  • долгое нажатие — завершить стрим
 *  • перетаскивание — подвинуть
 */
class StreamService : Service(), ConnectChecker {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val ACTION_STOP = "com.gamestream.STOP"
        private const val CHANNEL_ID = "stream"
        private const val NOTIF_ID = 7
        @Volatile var isRunning = false
    }

    private enum class State { CONNECTING, LIVE, RECONNECTING }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var windowManager: WindowManager

    private var stream: GenericStream? = null
    private var projection: MediaProjection? = null
    private var url = ""
    private var toPc = false
    private var audioMode = AudioMode.GAME_AND_MIC
    private var micMuted = false

    private var state = State.CONNECTING
    private var liveSince = 0L
    private var kbps = 0L

    private var bubble: TextView? = null
    private var bubbleBg: GradientDrawable? = null

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
        if (stream != null || intent == null) return START_NOT_STICKY

        audioMode = runCatching { AudioMode.valueOf(intent.getStringExtra("audio")!!) }
            .getOrDefault(AudioMode.MIC)
        url = intent.getStringExtra("url") ?: ""
        toPc = intent.getStringExtra("target") == Target.PC.name

        try {
            startStreaming(intent)
        } catch (e: Throwable) {
            App.saveCrash(this, e)
            fail("Ошибка запуска: ${e.message ?: e.javaClass.simpleName}")
        }
        return START_NOT_STICKY
    }

    private fun startStreaming(intent: Intent) {
        // На Android 14+ foreground-сервис обязан стартовать ДО получения MediaProjection
        startInForeground()

        val code = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
        val data: Intent? = if (Build.VERSION.SDK_INT >= 33)
            intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)

        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = data?.let { mpm.getMediaProjection(code, it) }
        if (p == null) {
            fail("Нет доступа к записи экрана")
            return
        }
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stopSelf() }
        }, main)
        projection = p

        val s = GenericStream(applicationContext, this, NoVideoSource(), buildAudioSource(p, micMuted))
        val w = intent.getIntExtra("w", 1280)
        val h = intent.getIntExtra("h", 720)
        val fps = intent.getIntExtra("fps", 30)
        val bitrate = intent.getIntExtra("bitrate", 2_500_000)

        val videoOk = s.prepareVideo(w, h, bitrate, fps)
        val audioOk = s.prepareAudio(48000, true, 160_000)
        if (!videoOk || !audioOk) {
            s.release()
            fail(
                if (!videoOk) "Телефон не поддерживает выбранное качество — выбери ниже"
                else "Не удалось включить запись звука — проверь разрешение «Микрофон»"
            )
            return
        }
        s.changeVideoSource(ScreenSource(applicationContext, p))
        s.getStreamClient().setReTries(10)
        stream = s

        // Экран отдаёт кадры только при изменениях — заставляем слать их постоянно
        runCatching { s.getGlInterface().setForceRender(true, 15) }

        addBubble()
        s.startStream(url)
        tick()
    }

    // ---------- Звук ----------

    private fun buildAudioSource(p: MediaProjection, muteMic: Boolean): AudioSource {
        if (Build.VERSION.SDK_INT < 29) return MicrophoneSource()
        return when (audioMode) {
            AudioMode.GAME -> InternalAudioSource(p)
            AudioMode.MIC -> MicrophoneSource()
            AudioMode.GAME_AND_MIC -> if (muteMic) InternalAudioSource(p) else MixAudioSource(p)
        }
    }

    private fun toggleMic() {
        val s = stream ?: return
        val p = projection ?: return
        if (audioMode != AudioMode.GAME_AND_MIC || Build.VERSION.SDK_INT < 29) {
            toast(if (audioMode == AudioMode.GAME) "Микрофон отключён в настройках" else "В этом режиме микрофон не выключается")
            return
        }
        micMuted = !micMuted
        s.changeAudioSource(buildAudioSource(p, micMuted))
        toast(if (micMuted) "Микрофон выключен" else "Микрофон включён")
        render()
    }

    // ---------- Соединение (колбэки приходят из фонового потока) ----------

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        main.post {
            if (liveSince == 0L) liveSince = SystemClock.elapsedRealtime()
            state = State.LIVE
            render()
            toast(if (toPc) "Подключено к ПК" else "Вы в эфире!")
        }
    }

    override fun onConnectionFailed(reason: String) {
        main.post {
            val s = stream
            if (s != null && s.getStreamClient().reTry(3000, reason)) {
                state = State.RECONNECTING
                render()
            } else if (s != null) {
                fail("Связь потеряна: $reason")
            }
        }
    }

    override fun onNewBitrate(bitrate: Long) {
        kbps = bitrate / 1000
    }

    override fun onDisconnect() {}

    override fun onAuthError() {
        main.post { fail("Сервер отклонил ключ трансляции") }
    }

    override fun onAuthSuccess() {}

    private fun fail(msg: String) {
        toast(msg)
        stopSelf()
    }

    // ---------- Плавающая кнопка ----------

    private fun tick() {
        render()
        if (stream != null) main.postDelayed(::tick, 1000)
    }

    private fun render() {
        val b = bubble ?: return
        val color: Int
        val title: String
        when (state) {
            State.CONNECTING -> { color = Color.argb(220, 90, 90, 100); title = "● подкл…" }
            State.RECONNECTING -> { color = Color.argb(230, 230, 140, 20); title = "● связь…" }
            State.LIVE -> {
                color = Color.argb(230, 220, 30, 45)
                val sec = (SystemClock.elapsedRealtime() - liveSince) / 1000
                title = if (sec >= 3600) "● %d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60)
                else "● %02d:%02d".format(sec / 60, sec % 60)
            }
        }
        val mic = when {
            audioMode == AudioMode.GAME -> "🎮"
            micMuted -> "🔇"
            else -> "🎙"
        }
        val rate = if (kbps >= 1000) "%.1f Мб".format(kbps / 1000f) else "$kbps Кб"
        b.text = "$title\n$mic $rate"
        bubbleBg?.setColor(color)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBubble() {
        if (!android.provider.Settings.canDrawOverlays(this)) return
        val d = resources.displayMetrics.density
        val bg = GradientDrawable().apply { cornerRadius = 14 * d }
        val view = TextView(this).apply {
            textSize = 12f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            background = bg
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = (8 * d).toInt()
            y = (120 * d).toInt()
        }

        var startX = 0; var startY = 0
        var downX = 0f; var downY = 0f
        var dragging = false
        var longDone = false
        val longPress = Runnable {
            longDone = true
            toast("Стрим завершён")
            stopSelf()
        }

        view.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x; startY = params.y
                    downX = e.rawX; downY = e.rawY
                    dragging = false; longDone = false
                    main.postDelayed(longPress, 1200)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > 10 * d || abs(dy) > 10 * d)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { windowManager.updateViewLayout(view, params) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (e.action == MotionEvent.ACTION_UP && !dragging && !longDone) toggleMic()
                }
            }
            true
        }
        windowManager.addView(view, params)
        bubble = view
        bubbleBg = bg
        render()
    }

    // ---------- Уведомление ----------

    private fun startInForeground() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Стрим", NotificationManager.IMPORTANCE_LOW))
        val stopIntent = PendingIntent.getService(
            this, 0, Intent(this, StreamService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle(if (toPc) "Трансляция на ПК" else "Стрим на YouTube")
            .setContentText("Тап по кнопке — микрофон, долгое нажатие — стоп")
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Завершить стрим", stopIntent).build())
            .build()

        if (Build.VERSION.SDK_INT >= 30) {
            var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            val micGranted = checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            if (audioMode != AudioMode.GAME && micGranted) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            startForeground(NOTIF_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun toast(msg: String) {
        // Показываем сразу, если уже в главном потоке, — иначе тост потеряется при остановке сервиса
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        } else {
            main.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        }
    }

    override fun onDestroy() {
        isRunning = false
        main.removeCallbacksAndMessages(null)
        stream?.let {
            runCatching { if (it.isStreaming) it.stopStream() }
            runCatching { it.release() }
        }
        stream = null
        bubble?.let { runCatching { windowManager.removeView(it) } }
        bubble = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }
}
