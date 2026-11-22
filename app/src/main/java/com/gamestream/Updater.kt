package com.gamestream

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Обновление прямо из приложения: проверяет последний выпуск на GitHub,
 * скачивает APK и ставит его поверх текущей версии (настройки сохраняются).
 */
object Updater {
    private const val REPO = "magaasadovvv-coder/GameStream"
    const val ASSET = "GameStream.apk"
    private const val ACTION_STATUS = "com.gamestream.UPDATE_STATUS"

    data class Release(val version: Int, val url: String)

    fun currentVersion(ctx: Context): Int = runCatching {
        val info = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt() else @Suppress("DEPRECATION") info.versionCode
    }.getOrDefault(0)

    /** Новая версия, если она есть. Блокирующий вызов — не из главного потока. */
    fun check(ctx: Context): Release? {
        val conn = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "GameStream-Android")
        conn.connectTimeout = 8000
        conn.readTimeout = 8000
        try {
            if (conn.responseCode != 200) return null
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            // Выпуски называются v1.<номер сборки>, а номер сборки = номер версии приложения
            val version = json.getString("tag_name").substringAfterLast('.').toIntOrNull() ?: return null
            if (version <= currentVersion(ctx)) return null
            val assets = json.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name") == ASSET) return Release(version, a.getString("browser_download_url"))
            }
            return null
        } finally {
            conn.disconnect()
        }
    }

    /** Можно ли приложению ставить APK. Если нет — открывает нужную настройку. */
    fun ensureInstallAllowed(activity: Activity): Boolean {
        if (activity.packageManager.canRequestPackageInstalls()) return true
        Toast.makeText(activity, "Разреши GameStream устанавливать обновления и нажми «Обновить» ещё раз", Toast.LENGTH_LONG).show()
        runCatching {
            activity.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
            )
        }
        return false
    }

    /** Скачать и установить. onProgress(0..100), onError(текст) — вызываются из фонового потока. */
    fun downloadAndInstall(ctx: Context, release: Release, onProgress: (Int) -> Unit, onError: (String) -> Unit) {
        thread {
            try {
                val file = File(ctx.cacheDir, "update.apk")
                var url = URL(release.url)
                var conn: HttpURLConnection
                // GitHub отдаёт файл через переадресацию на другой адрес — проходим её вручную
                var hops = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.setRequestProperty("User-Agent", "GameStream-Android")
                    conn.connectTimeout = 10000
                    conn.readTimeout = 20000
                    val code = conn.responseCode
                    if (code in 300..399 && hops++ < 5) {
                        url = URL(url, conn.getHeaderField("Location"))
                        conn.disconnect()
                        continue
                    }
                    if (code != 200) throw IllegalStateException("сервер ответил $code")
                    break
                }
                val total = conn.contentLengthLong
                conn.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            if (total > 0) {
                                val p = (done * 100 / total).toInt()
                                if (p != last) { last = p; onProgress(p) }
                            }
                        }
                    }
                }
                conn.disconnect()
                install(ctx, file)
            } catch (e: Exception) {
                onError(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    private fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("update", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ACTION_STATUS).setPackage(ctx.packageName), flags
            )
            session.commit(pi.intentSender)
        }
    }

    /** Получает ответ системы об установке: показывает окно подтверждения или ошибку. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33)
                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let { runCatching { context.startActivity(it) } }
                }
                PackageInstaller.STATUS_SUCCESS -> {}
                else -> {
                    val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "неизвестная ошибка"
                    Toast.makeText(context, "Обновление не установилось: $msg", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}
