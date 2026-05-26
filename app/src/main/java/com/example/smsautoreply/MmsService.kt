package com.example.smsautoreply

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log

class MmsService : Service() {

    companion object {
        private const val TAG = "MmsService"
    }

    override fun onBind(intent: Intent?): IBinder? {
        Log.d(TAG, "onBind called")
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand called")
        // 简单实现，仅用于满足默认短信应用的要求
        // 实际应用中可以实现完整的彩信服务
        stopSelf()
        return START_NOT_STICKY
    }
}
