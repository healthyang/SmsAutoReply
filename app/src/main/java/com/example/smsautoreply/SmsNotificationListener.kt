package com.example.smsautoreply

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class SmsNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "SmsNotificationListener"
        private val SMS_PACKAGES = setOf(
            "com.android.mms",
            "com.google.android.apps.messaging",
            "com.samsung.android.messaging",
            "com.miui.sms",
            "com.xiaomi.mms"
        )
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        val packageName = sbn.packageName ?: return
        if (!SMS_PACKAGES.contains(packageName)) return

        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString() ?: ""

        val messageBody = if (bigText.isNotEmpty()) bigText else text
        if (messageBody.isEmpty()) return
        if (messageBody.contains("正在运行") || messageBody.contains("点按即可了解详情")) return

        Log.d(TAG, "SMS from: $title")
        SmsAnalysisService.processSmsFromNotification(this, title, messageBody)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}
}
