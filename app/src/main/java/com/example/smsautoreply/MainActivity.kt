package com.example.smsautoreply

import android.app.NotificationManager
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
        private const val TAG = "MainActivity"
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
                Toast.makeText(this, "请将应用添加到电池优化白名单", Toast.LENGTH_LONG).show()
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

        val statusCheckButton = Button(this)
        statusCheckButton.text = "检查服务状态"
        statusCheckButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        statusCheckButton.setPadding(0, 16, 0, 16)
        statusCheckButton.setOnClickListener {
            if (isServiceRunning(SmsAnalysisService::class.java)) {
                Toast.makeText(this, "后台服务运行正常", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "后台服务未运行，正在启动...", Toast.LENGTH_SHORT).show()
                startSmsAnalysisService()
            }
        }

        val restartServiceButton = Button(this)
        restartServiceButton.text = "重启后台服务"
        restartServiceButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        restartServiceButton.setPadding(0, 16, 0, 16)
        restartServiceButton.setOnClickListener {
            startSmsAnalysisService()
            Toast.makeText(this, "后台服务已重启", Toast.LENGTH_SHORT).show()
        }

        val permissionButton = Button(this)
        permissionButton.text = "打开权限设置"
        permissionButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        permissionButton.setPadding(0, 16, 0, 16)
        permissionButton.setOnClickListener { openAppSettings() }

        val notificationListenerButton = Button(this)
        notificationListenerButton.text = "开启通知监听（读取短号短信）"
        notificationListenerButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        notificationListenerButton.setPadding(0, 16, 0, 16)
        notificationListenerButton.setOnClickListener { openNotificationListenerSettings() }

        val accessibilityStatusText = android.widget.TextView(this)
        accessibilityStatusText.textSize = 14f
        accessibilityStatusText.setPadding(0, 24, 0, 8)
        updateAccessibilityStatus(accessibilityStatusText)

        val accessibilityButton = Button(this)
        accessibilityButton.text = "开启无障碍服务（自动点击确认弹窗）"
        accessibilityButton.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        accessibilityButton.setPadding(0, 16, 0, 16)
        accessibilityButton.setOnClickListener { openAccessibilitySettings() }

        val rootLayout = findViewById<LinearLayout>(R.id.rootLayout)
        rootLayout.addView(permissionStatusText, 2)
        rootLayout.addView(statusCheckButton)
        rootLayout.addView(restartServiceButton)
        rootLayout.addView(permissionButton)
        rootLayout.addView(notificationListenerButton)
        rootLayout.addView(accessibilityStatusText)
        rootLayout.addView(accessibilityButton)
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

        if (!hasSendSms) sb.append("\n\n⚠️ 发送短信权限未授权，自动回复将弹窗确认")
        if (!isNotificationListener) sb.append("\n\n⚠️ 通知监听未开启，短号短信无法自动回复")

        if (::permissionStatusText.isInitialized) {
            permissionStatusText.text = sb.toString()
            permissionStatusText.setTextColor(if (hasSendSms && isNotificationListener) 0xFF4CAF50.toInt() else 0xFFF44336.toInt())
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        if (SmsAutoClickService.isRunning) return true
        try {
            val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            return !enabled.isNullOrEmpty() && (enabled.contains("SmsAutoClickService", ignoreCase = true) || enabled.contains(packageName, ignoreCase = true))
        } catch (_: Exception) { return false }
    }

    private fun updateAccessibilityStatus(statusText: android.widget.TextView) {
        val enabled = isAccessibilityServiceEnabled()
        statusText.text = if (enabled) "无障碍服务: ✓ 已开启（自动点击确认弹窗）" else "无障碍服务: ✗ 未开启（发送短信会弹窗确认）"
        statusText.setTextColor(if (enabled) 0xFF4CAF50.toInt() else 0xFFFF9800.toInt())
    }

    private fun openNotificationListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            Toast.makeText(this, "请找到「短信自动回复」并开启通知监听权限", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开通知监听设置", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAccessibilitySettings() {
        try {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "请找到「短信自动回复」并开启无障碍服务", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "无法打开无障碍设置", Toast.LENGTH_SHORT).show()
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
