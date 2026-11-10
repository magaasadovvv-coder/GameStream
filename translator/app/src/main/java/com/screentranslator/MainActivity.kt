package com.screentranslator

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = MainScope()
    private lateinit var status: TextView
    private val reqCapture = 42

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
            setBackgroundColor(Color.rgb(18, 18, 24))
        }
        fun label(text: String, size: Float, color: Int = Color.WHITE) = TextView(this).apply {
            this.text = text; textSize = size; setTextColor(color)
            setPadding(0, 0, 0, (12 * d).toInt())
        }
        fun button(text: String, onClick: () -> Unit) = Button(this).apply {
            this.text = text; isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(label("译 → RU  Переводчик экрана", 24f))
        root.addView(label(
            "Переводит китайский текст в любом приложении прямо поверх экрана. Работает офлайн.\n\n" +
                "• Тап по кнопке «译» — перевести экран / убрать перевод\n" +
                "• Долгое нажатие — авто-режим (красная кнопка)\n" +
                "• Кнопку можно перетаскивать", 15f, Color.rgb(190, 190, 200)
        ))
        status = label("", 15f, Color.rgb(120, 200, 255))
        root.addView(status)

        root.addView(button("1. Разрешить показ поверх других приложений") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        })
        root.addView(button("2. Скачать языки (один раз, нужен интернет)") { downloadModels() })
        root.addView(button("▶ Запустить переводчик") { start() })
        root.addView(button("■ Остановить") {
            startService(Intent(this, TranslatorService::class.java).setAction(TranslatorService.ACTION_STOP))
            updateStatus()
        })

        setContentView(root)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    private fun updateStatus(extra: String? = null) {
        val overlay = if (Settings.canDrawOverlays(this)) "✓ поверх окон разрешено" else "✗ нет разрешения поверх окон"
        val running = if (TranslatorService.isRunning) "✓ переводчик запущен" else "переводчик остановлен"
        status.text = listOfNotNull(overlay, running, extra).joinToString("\n")
    }

    private fun downloadModels() {
        updateStatus("Скачиваю модели…")
        scope.launch {
            try {
                Engine.downloadModels()
                updateStatus("✓ языки скачаны")
            } catch (e: Exception) {
                updateStatus("Ошибка загрузки: ${e.message}")
            }
        }
    }

    private fun start() {
        if (!Settings.canDrawOverlays(this)) {
            updateStatus("Сначала выполни шаг 1")
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != reqCapture) return
        if (resultCode != RESULT_OK || data == null) {
            updateStatus("Доступ к экрану не выдан")
            return
        }
        // Модели нужны для перевода — подтянем в фоне, если ещё не скачаны
        scope.launch { runCatching { Engine.downloadModels() } }

        val intent = Intent(this, TranslatorService::class.java)
            .putExtra(TranslatorService.EXTRA_RESULT_CODE, resultCode)
            .putExtra(TranslatorService.EXTRA_RESULT_DATA, data)
        startForegroundService(intent)
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
