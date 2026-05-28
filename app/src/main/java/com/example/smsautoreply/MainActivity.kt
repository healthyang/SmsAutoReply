package com.example.smsautoreply

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_SMS_PERMISSIONS = 1001
        private val REQUIRED_PERMISSIONS = arrayOf(
            android.Manifest.permission.RECEIVE_SMS,
            android.Manifest.permission.SEND_SMS,
            android.Manifest.permission.READ_SMS
        )
    }

    private lateinit var autoReplySwitch: Switch
    private lateinit var testButton: Button
    private lateinit var permissionStatusText: android.widget.TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initUI()
        checkAndRequestPermissions()
        checkBatteryOptimization()
        startSmsAnalysisService()
    }

    override fun onResume() {
        super.onResume()
        updatePermissionStatus()
    }

    private fun startSmsAnalysisService() {
        val serviceIntent = Intent(this, SmsAnalysisService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }
    }

    private fun checkBatteryOptimization() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = android.net.Uri.parse("package:$packageName")
                startActivityForResult(intent, 1003)
            }
        }
    }

    private fun initUI() {
        permissionStatusText = android.widget.TextView(this)
        permissionStatusText.textSize = 14f
        permissionStatusText.setPadding(0, 24, 0, 8)
        updatePermissionStatus()

        autoReplySwitch = findViewById(R.id.autoReplySwitch)
        autoReplySwitch.isChecked = true
        autoReplySwitch.setOnCheckedChangeListener { _, isChecked ->
            Toast.makeText(this, if (isChecked) "自动回复已开启" else "自动回复已关闭", Toast.LENGTH_SHORT).show()
        }

        testButton = findViewById(R.id.testButton)
        testButton.setOnClickListener {
            val testSender = "10086"
            val testMessage = "零信任安全登录申请;同意请回复：Y4828,不同意请回复：N4828"
            val serviceIntent = Intent(this, SmsAnalysisService::class.java)
            serviceIntent.putExtra("sender", testSender)
            serviceIntent.putExtra("messageBody", testMessage)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Toast.makeText(this, "测试短信已发送", Toast.LENGTH_SHORT).show()
        }

        val permissionButton = Button(this)
        permissionButton.text = "权限设置"
        permissionButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        permissionButton.setPadding(0, 16, 0, 16)
        permissionButton.setOnClickListener { openAppSettings() }

        val notificationListenerButton = Button(this)
        notificationListenerButton.text = "开启通知监听"
        notificationListenerButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        notificationListenerButton.setPadding(0, 16, 0, 16)
        notificationListenerButton.setOnClickListener { openNotificationListenerSettings() }

        val hintText = android.widget.TextView(this)
        hintText.textSize = 12f
        hintText.setPadding(16, 24, 16, 8)
        hintText.text = "提示：发送短信时如弹出确认框，请手动点击确认"
        hintText.setTextColor(0xFF757575.toInt())

        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        rootLayout.addView(permissionStatusText, 2)
        rootLayout.addView(permissionButton)
        rootLayout.addView(notificationListenerButton)
        rootLayout.addView(hintText)
    }

    private fun isNotificationListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        return !flat.isNullOrEmpty() && flat.contains("${packageName}/${packageName}.SmsNotificationListener")
    }

    private fun updatePermissionStatus() {
        val hasSendSms = ContextCompat.checkSelfPermission(this, android.Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED
        val hasReceiveSms = ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED
        val isNotificationListener = isNotificationListenerEnabled()

        val sb = StringBuilder()
        sb.append("权限状态:\n")
        sb.append("• 发送短信: ${if (hasSendSms) "✓ 已授权" else "✗ 未授权"}\n")
        sb.append("• 接收短信: ${if (hasReceiveSms) "✓ 已授权" else "✗ 未授权"}\n")
        sb.append("• 通知监听: ${if (isNotificationListener) "✓ 已开启" else "✗ 未开启"}")

        if (!hasSendSms) sb.append("\n\n⚠️ 发送短信权限未授权")
        if (!isNotificationListener) sb.append("\n\n⚠️ 通知监听未开启，短号短信无法自动回复")

        if (::permissionStatusText.isInitialized) {
            permissionStatusText.text = sb.toString()
            permissionStatusText.setTextColor(if (hasSendSms && isNotificationListener) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        }
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "请找到「短信自动回复」并开启通知监听权限", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开通知监听设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings() {
        try {
            val intent = Intent("miui.intent.action.APP_PERM_EDITOR")
            intent.setClassName("com.miui.securitycenter", "com.miui.permcenter.permissions.PermissionsEditorActivity")
            intent.putExtra("extra_pkgname", packageName)
            startActivity(intent)
        } catch (_: Exception) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = android.net.Uri.parse("package:$packageName")
            startActivity(intent)
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        return manager.getRunningServices(Int.MAX_VALUE).any { serviceClass.name == it.service.className }
    }

    private fun checkAndRequestPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toMutableList()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                missing.add(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_SMS_PERMISSIONS)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_SMS_PERMISSIONS && !grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            Toast.makeText(this, "部分权限被拒绝，应用可能无法正常工作", Toast.LENGTH_SHORT).show()
        }
    }
}
