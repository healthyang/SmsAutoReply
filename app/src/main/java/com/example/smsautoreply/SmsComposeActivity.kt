package com.example.smsautoreply

import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity

class SmsComposeActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "SmsComposeActivity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate called")
        
        // 简单实现，仅用于满足默认短信应用的要求
        // 实际应用中可以实现完整的短信撰写界面
        finish() // 直接结束活动
    }
}
