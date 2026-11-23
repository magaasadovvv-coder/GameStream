package com.gamestream

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Toast

/**
 * Невидимый экран для быстрого старта из шторки: берёт прошлые настройки,
 * спрашивает только разрешение на запись экрана и запускает стрим.
 */
class QuickStartActivity : Activity() {

    private val reqCapture = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (StreamService.isRunning) {
            startService(Intent(this, StreamService::class.java).setAction(StreamService.ACTION_STOP))
            finish()
            return
        }
        val cfg = StreamConfig.load(this)
        val problem = (if (!License.isUnlocked(this)) "Сначала активируй приложение ключом" else null)
            ?: cfg.problem(this)
            ?: if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                "Первый запуск сделай из приложения — нужно выдать разрешения" else null
        if (problem != null) {
            Toast.makeText(this, problem, Toast.LENGTH_LONG).show()
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return
        }
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), reqCapture)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == reqCapture && resultCode == RESULT_OK && data != null) {
            val intent = StreamConfig.load(this).toIntent(Intent(this, StreamService::class.java))
                .putExtra(StreamService.EXTRA_RESULT_CODE, resultCode)
                .putExtra(StreamService.EXTRA_RESULT_DATA, data)
            startForegroundService(intent)
            Toast.makeText(this, "Стрим запускается — открывай игру!", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
