package com.example.smsautoreply

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Telephony
import android.util.Log

class SmsBroadcastReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsBroadcastReceiver"
        private const val WAKELOCK_TIMEOUT = 60_000L

        private var staticCallback: ((sender: String, messageBody: String) -> Unit)? = null

        fun setCallback(callback: (sender: String, messageBody: String) -> Unit) {
            staticCallback = callback
        }

        fun clearCallback() {
            staticCallback = null
        }
    }

    private var manualCallback: ((sender: String, messageBody: String) -> Unit)? = null

    fun setManualCallback(callback: (sender: String, messageBody: String) -> Unit) {
        manualCallback = callback
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmsAutoReply::SMS_RECEIVED_WAKELOCK")
        wakeLock.acquire(WAKELOCK_TIMEOUT)

        when (intent.action) {
            Telephony.Sms.Intents.SMS_DELIVER_ACTION,
            Telephony.Sms.Intents.SMS_RECEIVED_ACTION -> {
                processSmsIntent(context, intent, wakeLock)
            }
            else -> releaseWakeLock(wakeLock)
        }
    }

    private fun releaseWakeLock(wakeLock: PowerManager.WakeLock) {
        try {
            if (wakeLock.isHeld) wakeLock.release()
        } catch (_: Exception) {}
    }

    private fun processSmsIntent(context: Context, intent: Intent, wakeLock: PowerManager.WakeLock) {
        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)

        if (messages.isNotEmpty()) {
            val sender = messages[0].originatingAddress
            val body = StringBuilder()
            for (msg in messages) body.append(msg.messageBody)
            val completeMessage = body.toString()

            if (completeMessage.isNotEmpty()) {
                val effectiveSender = sender ?: "unknown"
                Log.d(TAG, "Received SMS from: $effectiveSender")

                val callback = manualCallback ?: staticCallback
                if (callback != null) {
                    callback.invoke(effectiveSender, completeMessage)
                } else {
                    val serviceIntent = Intent(context, SmsAnalysisService::class.java)
                    serviceIntent.putExtra("sender", effectiveSender)
                    serviceIntent.putExtra("messageBody", completeMessage)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                }
            }
        }

        releaseWakeLock(wakeLock)
    }
}
