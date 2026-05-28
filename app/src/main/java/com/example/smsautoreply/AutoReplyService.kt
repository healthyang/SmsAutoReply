package com.example.smsautoreply

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.telephony.SmsManager
import android.util.Log

class AutoReplyService : Service() {

    companion object {
        private const val TAG = "AutoReplyService"
        private const val CHANNEL_ID = "AutoReplyServiceChannel"
        private const val WAKELOCK_TIMEOUT = 30_000L
        private const val ACTION_SMS_SENT = "com.example.smsautoreply.SMS_SENT"
        private const val ALERT_TIMEOUT_MS = 15_000L // 15秒后自动停止提醒
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var sentReceiver: BroadcastReceiver? = null
    private var ringtone: android.media.Ringtone? = null
    private var vibrator: Vibrator? = null
    private val handler = Handler(Looper.getMainLooper())
    private var alertRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "AutoReplyService started")

        acquireWakeLock()
        createNotificationChannel()

        val notification = buildNotification("短信自动回复", "正在发送自动回复")
        startForeground(1, notification)

        val sender = intent?.getStringExtra("sender")
        val replyMessage = intent?.getStringExtra("replyMessage")

        if (sender != null && replyMessage != null) {
            Log.d(TAG, "Sending auto reply to $sender: $replyMessage")
            sendAutoReply(sender, replyMessage)
        }

        // 延迟停止服务
        Thread {
            Thread.sleep(5000)
            releaseWakeLock()
            stopForeground(true)
            stopSelf()
            Log.d(TAG, "AutoReplyService stopped")
        }.start()

        return START_NOT_STICKY
    }

    private fun createNotificationChannel() {
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
    }

    private fun buildNotification(title: String, content: String): Notification {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle(title)
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setOngoing(true)
                .build()
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "SmsAutoReply::AutoReplyService_WAKELOCK"
            )
            wakeLock?.acquire(WAKELOCK_TIMEOUT)
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Error acquiring WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock released")
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WakeLock: ${e.message}")
        }
    }

    private fun sendAutoReply(recipient: String, message: String) {
        try {
            Log.d(TAG, "Attempting to send SMS to $recipient, message length: ${message.length}")

            // 开始振铃提醒（如果有弹窗，用户可以听到声音去点击确认）
            startAlert()

            // 注册发送结果接收器
            registerSentReceiver(recipient, message)

            // 创建SENT PendingIntent
            val sentIntent = Intent(ACTION_SMS_SENT)
            sentIntent.setPackage(packageName)
            val sentPendingIntent = PendingIntent.getBroadcast(
                this, 0, sentIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )

            val smsManager = SmsManager.getDefault()

            if (message.length > 70) {
                val parts = smsManager.divideMessage(message)
                Log.d(TAG, "Message divided into ${parts.size} parts")
                val sentIntents = ArrayList<PendingIntent>()
                for (i in parts.indices) {
                    sentIntents.add(sentPendingIntent)
                }
                smsManager.sendMultipartTextMessage(recipient, null, parts, sentIntents, null)
            } else {
                smsManager.sendTextMessage(recipient, null, message, sentPendingIntent, null)
            }

            Log.d(TAG, "sendTextMessage called successfully")

            // 设置超时自动停止提醒
            alertRunnable = Runnable {
                Log.d(TAG, "Alert timeout, stopping alert")
                stopAlert()
                showSendNotification("请检查", "如需手动确认发送，请点击确认按钮")
            }
            handler.postDelayed(alertRunnable!!, ALERT_TIMEOUT_MS)

        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException: ${e.message}")
            stopAlert()
            showSendNotification("发送失败", "权限不足，请在小米设置中开启「发送短信」权限并关闭「短信发送保护」")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send auto reply: ${e.message}")
            e.printStackTrace()
            stopAlert()
            showSendNotification("发送失败", e.message ?: "未知错误")
        }
    }

    private fun startAlert() {
        try {
            // 播放提示音
            val alertUri: Uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            ringtone = RingtoneManager.getRingtone(this, alertUri)
            if (ringtone != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.isLooping = true
                }
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    ringtone?.audioAttributes = audioAttributes
                }
                ringtone?.play()
                Log.d(TAG, "Alert ringtone started")
            }

            // 振动
            vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 500, 300, 500, 300, 500) // 振动模式
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createWaveform(pattern, 0)) // 0 = 重复
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(pattern, 0)
            }
            Log.d(TAG, "Vibration started")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting alert: ${e.message}")
        }
    }

    private fun stopAlert() {
        try {
            // 取消超时任务
            alertRunnable?.let { handler.removeCallbacks(it) }
            alertRunnable = null

            // 停止提示音
            if (ringtone?.isPlaying == true) {
                ringtone?.stop()
                Log.d(TAG, "Alert ringtone stopped")
            }
            ringtone = null

            // 停止振动
            vibrator?.cancel()
            vibrator = null
            Log.d(TAG, "Vibration stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping alert: ${e.message}")
        }
    }

    private fun registerSentReceiver(recipient: String, message: String) {
        unregisterSentReceiver()

        sentReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == ACTION_SMS_SENT) {
                    stopAlert()
                    when (resultCode) {
                        Activity.RESULT_OK -> {
                            Log.d(TAG, "SMS sent successfully to $recipient")
                            showSendNotification("已发送回复给 $recipient", message)
                        }
                        SmsManager.RESULT_ERROR_NO_SERVICE -> {
                            Log.e(TAG, "SMS send failed: no service")
                            showSendNotification("发送失败", "无网络服务")
                        }
                        SmsManager.RESULT_ERROR_RADIO_OFF -> {
                            Log.e(TAG, "SMS send failed: radio off")
                            showSendNotification("发送失败", "无线电已关闭")
                        }
                        else -> {
                            Log.e(TAG, "SMS send failed with result code: $resultCode")
                            showSendNotification("发送失败", "错误代码: $resultCode，请检查小米短信发送设置")
                        }
                    }
                    unregisterSentReceiver()
                }
            }
        }

        val filter = IntentFilter(ACTION_SMS_SENT)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(sentReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(sentReceiver, filter)
        }
    }

    private fun unregisterSentReceiver() {
        if (sentReceiver != null) {
            try {
                unregisterReceiver(sentReceiver)
            } catch (_: Exception) {}
            sentReceiver = null
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

    override fun onDestroy() {
        stopAlert()
        unregisterSentReceiver()
        releaseWakeLock()
        super.onDestroy()
    }
}
