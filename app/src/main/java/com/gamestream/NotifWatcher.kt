package com.gamestream

import android.app.NotificationManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Замечает всплывающие уведомления, чтобы на это время закрыть экран на стриме. */
class NotifWatcher : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val service = StreamService.instance ?: return
        if (sbn == null || sbn.packageName == packageName || sbn.isOngoing) return
        // Всплывают поверх игры только важные уведомления — остальные на экране не видны
        val ranking = Ranking()
        val known = runCatching { currentRanking.getRanking(sbn.key, ranking) }.getOrDefault(false)
        if (known && ranking.importance < NotificationManager.IMPORTANCE_HIGH) return
        service.onPrivateNotification(sbn.packageName)
    }
}
