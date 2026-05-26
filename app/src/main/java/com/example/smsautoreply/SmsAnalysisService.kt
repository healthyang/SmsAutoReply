package com.example.smsautoreply

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log

class SmsAnalysisService : Service() {

    companion object {
        private const val TAG = "SmsAnalysisService"
        private const val CHANNEL_ID = "SmsAnalysisServiceChannel"
        private const val WAKELOCK_TIMEOUT = 120_000L

        private var notificationCallback: ((sender: String, messageBody: String) -> Unit)? = null

        fun processSmsFromNotification(context: Context, sender: String, messageBody: String) {
            notificationCallback?.invoke(sender, messageBody) ?: run {
                val intent = Intent(context, SmsAnalysisService::class.java)
                intent.putExtra("sender", sender)
                intent.putExtra("messageBody", messageBody)
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start service from notification: ${e.message}")
                }
            }
        }
    }

    private lateinit var smsReceiver: SmsBroadcastReceiver
    private var smsObserver: ContentObserver? = null
    private var lastSmsId: Long = 0
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "SmsAnalysisService created")

        acquireWakeLock()

        notificationCallback = { sender, messageBody ->
            analyzeAndReply(sender, messageBody)
        }

        initLastSmsId()
        initSmsReceiver()
        registerSmsObserver()
        setupForegroundService()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (wakeLock == null || wakeLock?.isHeld != true) {
            acquireWakeLock()
        }

        if (intent?.hasExtra("sender") == true && intent?.hasExtra("messageBody") == true) {
            val sender = intent.getStringExtra("sender")
            val messageBody = intent.getStringExtra("messageBody")
            if (sender != null && messageBody != null) {
                analyzeAndReply(sender, messageBody)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        SmsBroadcastReceiver.clearCallback()
        notificationCallback = null

        try { unregisterReceiver(smsReceiver) } catch (_: Exception) {}
        try { smsObserver?.let { contentResolver.unregisterContentObserver(it) } } catch (_: Exception) {}

        releaseWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAutoReply::SmsAnalysisService_WAKELOCK")
            wakeLock?.acquire(WAKELOCK_TIMEOUT)
        } catch (_: Exception) {}
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            wakeLock = null
        } catch (_: Exception) {}
    }

    private fun initLastSmsId() {
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID),
                "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX}",
                null,
                "${Telephony.Sms._ID} DESC"
            )
            cursor?.use {
                if (it.moveToFirst()) lastSmsId = it.getLong(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting initial SMS ID: ${e.message}")
        }
    }

    private fun initSmsReceiver() {
        SmsBroadcastReceiver.setCallback { sender, messageBody ->
            analyzeAndReply(sender, messageBody)
        }

        smsReceiver = SmsBroadcastReceiver()
        smsReceiver.setManualCallback { sender, messageBody ->
            analyzeAndReply(sender, messageBody)
        }

        val filter = IntentFilter("android.provider.Telephony.SMS_RECEIVED")
        filter.priority = 999

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(smsReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(smsReceiver, filter)
        }
    }

    private fun registerSmsObserver() {
        val handler = Handler(Looper.getMainLooper())
        val observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: android.net.Uri?) {
                super.onChange(selfChange, uri)
                checkNewSms()
            }
        }
        smsObserver = observer

        try {
            contentResolver.registerContentObserver(Telephony.Sms.CONTENT_URI, true, observer)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering SMS observer: ${e.message}")
        }
    }

    private fun checkNewSms() {
        try {
            val cursor = contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.BODY),
                "${Telephony.Sms.TYPE} = ${Telephony.Sms.MESSAGE_TYPE_INBOX} AND ${Telephony.Sms._ID} > ?",
                arrayOf(lastSmsId.toString()),
                "${Telephony.Sms._ID} DESC"
            )
            cursor?.use {
                while (it.moveToNext()) {
                    val id = it.getLong(0)
                    val sender = it.getString(1) ?: ""
                    val body = it.getString(2) ?: ""
                    if (id > lastSmsId && sender.isNotEmpty() && body.isNotEmpty()) {
                        lastSmsId = id
                        Log.d(TAG, "New SMS via ContentObserver - From: $sender")
                        analyzeAndReply(sender, body)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error checking new SMS: ${e.message}")
        }
    }

    private fun setupForegroundService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "短信自动回复服务", NotificationManager.IMPORTANCE_LOW)
            channel.setSound(null, null)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        val pendingIntent = android.app.PendingIntent.getActivity(this, 0, intent, android.app.PendingIntent.FLAG_IMMUTABLE)

        val notification = Notification.Builder(this, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) CHANNEL_ID else "")
            .setContentTitle("短信自动回复")
            .setContentText("服务运行中，监控零信任安全登录申请")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()

        startForeground(1, notification)
    }

    private fun analyzeAndReply(sender: String, messageBody: String) {
        Log.d(TAG, "Analyzing SMS from: $sender")
        val replyMessage = analyzeSmsAndGenerateReply(messageBody)

        if (replyMessage != null) {
            Log.d(TAG, "Reply: $replyMessage")
            val replyIntent = Intent(this, AutoReplyService::class.java)
            replyIntent.putExtra("sender", sender)
            replyIntent.putExtra("replyMessage", replyMessage)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(replyIntent)
                } else {
                    startService(replyIntent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AutoReplyService: ${e.message}")
            }
        }
    }

    private fun analyzeSmsAndGenerateReply(messageBody: String): String? {
        val keyword = "零信任安全登录申请;同意请回复："
        val index = messageBody.indexOf(keyword)
        if (index != -1) {
            val replyPart = messageBody.substring(index + keyword.length)
            return if (replyPart.length >= 5) replyPart.substring(0, 5) else replyPart
        }
        return null
    }
}
