package com.example.smsautoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.telephony.SmsManager
import android.util.Log

class AutoReplyService : Service() {

    companion object {
        private const val TAG = "AutoReplyService"
        private const val CHANNEL_ID = "AutoReplyServiceChannel"
        private const val WAKELOCK_TIMEOUT = 30_000L // 30秒超时
    }

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AutoReplyService started")

        // 获取WakeLock，确保CPU不会在发送短信期间休眠
        acquireWakeLock()

        // 创建通知通道（Android 8.0+）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "短信回复服务",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.description = "用于发送自动回复短信"
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }

        // 创建前台通知
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("短信自动回复")
                .setContentText("正在发送自动回复")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("短信自动回复")
                .setContentText("正在发送自动回复")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }

        startForeground(1, notification)

        // 处理自动回复
        val sender = intent?.getStringExtra("sender")
        val replyMessage = intent?.getStringExtra("replyMessage")

        if (sender != null && replyMessage != null) {
            Log.d(TAG, "Sending auto reply to $sender: $replyMessage")
            sendAutoReply(sender, replyMessage)
        }

        // 延迟停止服务，确保短信发送完成
        Thread {
            Thread.sleep(3000) // 增加到3秒确保发送完成
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
            Log.d(TAG, "AutoReplyService stopped")
        }.start()

        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsAutoReply::AutoReplyService_WAKELOCK"
            )
            wakeLock?.acquire(WAKELOCK_TIMEOUT)
            Log.d(TAG, "WakeLock acquired for AutoReplyService")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released for AutoReplyService")
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    private fun sendAutoReply(recipient: String, message: String) {
        try {
            Log.d(TAG, "Attempting to send SMS to $recipient, message length: ${message.length}")

            // 点亮屏幕，确保无障碍服务能操作确认弹窗
            wakeUpScreen()

            // 通知无障碍服务准备自动点击确认弹窗
            SmsAutoClickService.isWaitingForSmsConfirm = true
            Log.d(TAG, "Set isWaitingForSmsConfirm = true, accessibility service running: ${SmsAutoClickService.isRunning}")

            val smsManager = SmsManager.getDefault()

            // 检查短信长度，超过70字需要分段发送
            if (message.length > 70) {
                val parts = smsManager.divideMessage(message)
                Log.d(TAG, "Message divided into ${parts.size} parts")
                smsManager.sendMultipartTextMessage(recipient, null, parts, null, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, null, null)
            }

            Log.d(TAG, "sendTextMessage called successfully")

            // 等待一段时间让弹窗出现并被处理
            Thread {
                Thread.sleep(5000)
                SmsAutoClickService.isWaitingForSmsConfirm = false
                Log.d(TAG, "Set isWaitingForSmsConfirm = false after timeout")
            }.start()

            // 显示通知提示发送成功
            showSendNotification("已发送回复给 $recipient", message)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException - 权限不足或被小米安全机制拦截: ${e.message}")
            Log.e(TAG, "请在小米设置中开启「发送短信」权限并关闭「短信发送保护」")
            SmsAutoClickService.isWaitingForSmsConfirm = false
            showSendNotification("发送失败", "权限不足，请检查小米短信发送设置")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send auto reply: ${e.message}")
            e.printStackTrace()
            SmsAutoClickService.isWaitingForSmsConfirm = false
            showSendNotification("发送失败", e.message ?: "未知错误")
        }
    }

    private fun showSendNotification(title: String, content: String) {
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setAutoCancel(true)
                .build()
        }
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }

    private fun wakeUpScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (!pm.isInteractive) {
                val screenLock = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "SmsAutoReply::SCREEN_WAKE"
                )
                screenLock.acquire(10_000L)
                screenLock.release()
                Log.d(TAG, "Screen woken up")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error waking screen: ${e.message}")
        }
    }
}
