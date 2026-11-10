package com.gamestream

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File

/** Запоминает причину сбоя, чтобы показать её при следующем запуске. */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            saveCrash(this, error)
            previous?.uncaughtException(thread, error)
        }
    }

    companion object {
        private fun file(ctx: Context) = File(ctx.filesDir, "last_crash.txt")

        fun saveCrash(ctx: Context, error: Throwable) {
            runCatching {
                file(ctx).writeText(
                    "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), " +
                        "${Build.MANUFACTURER} ${Build.MODEL}\n" + Log.getStackTraceString(error)
                )
            }
        }

        fun lastCrash(ctx: Context): String? =
            runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

        fun clearCrash(ctx: Context) {
            runCatching { file(ctx).delete() }
        }
    }
}
