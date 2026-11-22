package com.gamestream

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import java.net.InetSocketAddress
import java.net.Socket

/** Проверка перед эфиром: всё ли готово и какое качество потянет. */
object Preflight {

    enum class Level(val icon: String) { OK("✓"), WARN("⚠"), BAD("✗") }

    data class Line(val level: Level, val text: String)

    data class Result(val lines: List<Line>, val recommended: Quality) {
        val hasBlockers get() = lines.any { it.level == Level.BAD }
    }

    /** Блокирующая проверка (сеть) — не из главного потока. */
    fun run(ctx: Context, cfg: StreamConfig): Result {
        val lines = mutableListOf<Line>()
        fun ok(t: String) = lines.add(Line(Level.OK, t))
        fun warn(t: String) = lines.add(Line(Level.WARN, t))
        fun bad(t: String) = lines.add(Line(Level.BAD, t))

        // Настройки и разрешения
        cfg.problem(ctx)?.let { bad(it) } ?: ok("Настройки заполнены")
        if (ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) ok("Разрешение «Микрофон»")
        else warn("Разрешение «Микрофон» спросим при старте — без него не будет звука")
        if (Settings.canDrawOverlays(ctx)) ok("Кнопка поверх игр разрешена")
        else warn("Кнопка поверх игр не разрешена — не будет плашки с таймером")

        // Батарея
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        when {
            charging -> ok("Заряд $level%, телефон на зарядке")
            level < 20 -> bad("Заряд $level% — поставь на зарядку, стрим быстро разряжает")
            level < 50 -> warn("Заряд $level% — лучше поставить на зарядку")
            else -> ok("Заряд $level%")
        }

        // Нагрев
        var hot = 0
        if (Build.VERSION.SDK_INT >= 29) {
            val status = ctx.getSystemService(PowerManager::class.java).currentThermalStatus
            hot = status
            when {
                status >= PowerManager.THERMAL_STATUS_SEVERE -> bad("Телефон сильно перегрет — дай остыть, иначе будут лаги")
                status >= PowerManager.THERMAL_STATUS_MODERATE -> warn("Телефон тёплый — сними чехол, качество лучше снизить")
                else -> ok("Телефон не перегрет")
            }
        }

        // Сеть
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val onWifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val onCell = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        var linkMbps = 0
        if (onWifi) {
            @Suppress("DEPRECATION")
            val info = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).connectionInfo
            linkMbps = info.linkSpeed
            val ghz5 = info.frequency >= 4900
            val band = if (ghz5) "5 ГГц" else "2,4 ГГц"
            when {
                !ghz5 -> warn("Wi‑Fi $band, $linkMbps Мбит/с — на 5 ГГц будет стабильнее")
                linkMbps in 1..99 -> warn("Wi‑Fi $band, но слабый сигнал ($linkMbps Мбит/с) — подойди ближе к роутеру")
                else -> ok("Wi‑Fi $band, $linkMbps Мбит/с")
            }
        }

        val usbPrefixes = PcFinder.usbPrefixes()
        var recommended: Quality

        if (cfg.target == Target.PC) {
            if (!onWifi && usbPrefixes.isEmpty()) bad("Нет Wi‑Fi и USB-подключения к ПК")
            val pc = runCatching { PcFinder.find(ctx, 3500) }.getOrNull()
            when {
                pc == null -> bad("ПК не отвечает — запущена ли программа GameStream PC?")
                cfg.pcIp.isNotBlank() && pc.ip != cfg.pcIp.trim() ->
                    warn("ПК найден по другому адресу (${pc.ip}${if (pc.usb) ", USB" else ""}) — нажми «Найти ПК автоматически»")
                else -> ok("ПК на связи: ${pc.name}${if (pc.usb) " (по USB)" else ""}")
            }
            recommended = when {
                pc?.usb == true -> Quality.P1080_60
                linkMbps >= 300 -> Quality.P1080_60
                linkMbps >= 150 -> Quality.P720_60
                linkMbps > 0 -> Quality.P720_30
                else -> Quality.P720_60
            }
        } else {
            if (onCell) warn("Мобильный интернет — следи за трафиком, стрим тратит ~1–3 ГБ в час")
            val t0 = System.currentTimeMillis()
            val reach = runCatching {
                Socket().use { it.connect(InetSocketAddress("a.rtmp.youtube.com", 1935), 4000) }
            }.isSuccess
            val ping = System.currentTimeMillis() - t0
            if (reach) {
                if (ping > 300) warn("Сервер YouTube отвечает медленно ($ping мс)") else ok("Сервер YouTube доступен ($ping мс)")
            } else {
                bad("Нет связи с сервером YouTube — проверь интернет")
            }
            warn("Скорость отдачи не проверяется — если стрим подлагивает, снизь качество")
            recommended = when {
                onWifi && linkMbps >= 200 && ping < 150 -> Quality.P1080_30
                onWifi -> Quality.P720_60
                else -> Quality.P720_30
            }
        }

        // При нагреве — на ступень ниже
        if (Build.VERSION.SDK_INT >= 29 && hot >= PowerManager.THERMAL_STATUS_MODERATE) {
            recommended = when (recommended) {
                Quality.P1080_60 -> Quality.P720_60
                Quality.P1080_30, Quality.P720_60 -> Quality.P720_30
                Quality.P720_30 -> Quality.P720_30
            }
        }
        return Result(lines, recommended)
    }
}
