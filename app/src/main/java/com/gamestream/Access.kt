package com.gamestream

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.Process

/** Проверки особых разрешений, нужных для авто-скрытия личного. */
object Access {

    /** Можно ли узнавать, какое приложение сейчас открыто. */
    fun hasUsageAccess(ctx: Context): Boolean {
        val ops = ctx.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName)
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

}
