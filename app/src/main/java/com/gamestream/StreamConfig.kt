package com.gamestream

import android.content.Context
import android.content.Intent
import android.os.Build

enum class Target { YOUTUBE, PC }

/** Откуда брать звук. Звук игры доступен с Android 10. */
enum class AudioMode(val title: String) {
    GAME_AND_MIC("Игра + микрофон"),
    GAME("Только звук игры"),
    MIC("Только микрофон"),
}

enum class Quality(val title: String, val width: Int, val height: Int, val fps: Int, val bitrate: Int) {
    P720_30("720p 30 к/с", 1280, 720, 30, 2_500_000),
    P720_60("720p 60 к/с", 1280, 720, 60, 4_500_000),
    P1080_30("1080p 30 к/с", 1920, 1080, 30, 4_500_000),
    P1080_60("1080p 60 к/с", 1920, 1080, 60, 6_800_000),
}

/** Все настройки стрима. Сохраняются между запусками. */
data class StreamConfig(
    val target: Target = Target.YOUTUBE,
    val ytServer: String = "rtmp://a.rtmp.youtube.com/live2",
    val ytKey: String = "",
    val pcIp: String = "",
    val pcPort: Int = 9000,
    val quality: Quality = Quality.P720_60,
    val vertical: Boolean = false,
    val audio: AudioMode = AudioMode.GAME_AND_MIC,
) {
    /** Адрес, куда отправляем поток. */
    val url: String
        get() = when (target) {
            Target.YOUTUBE -> ytServer.trimEnd('/') + "/" + ytKey.trim()
            Target.PC -> "srt://${pcIp.trim()}:$pcPort/live"
        }

    /** По локальной сети можно лить с битрейтом выше — картинка чище. */
    val bitrate: Int
        get() = if (target == Target.PC) quality.bitrate * 2 else quality.bitrate

    val videoWidth get() = if (vertical) quality.height else quality.width
    val videoHeight get() = if (vertical) quality.width else quality.height

    val usesMic get() = audio != AudioMode.GAME

    /** На Android 9 и ниже звук игры захватить нельзя — только микрофон. */
    fun effectiveAudio(): AudioMode =
        if (Build.VERSION.SDK_INT < 29) AudioMode.MIC else audio

    fun toIntent(intent: Intent): Intent = intent
        .putExtra("target", target.name)
        .putExtra("url", url)
        .putExtra("w", videoWidth)
        .putExtra("h", videoHeight)
        .putExtra("fps", quality.fps)
        .putExtra("bitrate", bitrate)
        .putExtra("audio", effectiveAudio().name)

    fun save(ctx: Context) {
        prefs(ctx).edit()
            .putString("target", target.name)
            .putString("ytServer", ytServer)
            .putString("ytKey", ytKey)
            .putString("pcIp", pcIp)
            .putInt("pcPort", pcPort)
            .putString("quality", quality.name)
            .putBoolean("vertical", vertical)
            .putString("audio", audio.name)
            .apply()
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("stream", Context.MODE_PRIVATE)

        fun load(ctx: Context): StreamConfig {
            val p = prefs(ctx)
            val d = StreamConfig()
            return StreamConfig(
                target = runCatching { Target.valueOf(p.getString("target", null)!!) }.getOrDefault(d.target),
                ytServer = p.getString("ytServer", d.ytServer)!!,
                ytKey = p.getString("ytKey", "")!!,
                pcIp = p.getString("pcIp", "")!!,
                pcPort = p.getInt("pcPort", d.pcPort),
                quality = runCatching { Quality.valueOf(p.getString("quality", null)!!) }.getOrDefault(d.quality),
                vertical = p.getBoolean("vertical", false),
                audio = runCatching { AudioMode.valueOf(p.getString("audio", null)!!) }.getOrDefault(d.audio),
            )
        }
    }
}
