package com.example.smsautoreply

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class SmsAutoClickService : AccessibilityService() {

    companion object {
        private const val TAG = "SmsAutoClickService"

        // 服务运行状态
        var isRunning = false
            private set

        // 是否正在等待发送短信确认
        var isWaitingForSmsConfirm = false

        // 可能的确认按钮文本
        private val CONFIRM_BUTTON_TEXTS = listOf(
            "确认发送", "允许", "确定", "同意", "Allow", "OK", "始终允许"
        )

        // 短信相关的包名
        private val SMS_PACKAGES = setOf(
            "com.android.permissioncontroller",
            "com.miui.securitycenter",
            "com.lbe.security.miui"
        )
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        isRunning = true
        Log.d(TAG, "Accessibility service connected")

        val info = AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.notificationTimeout = 100
        info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        serviceInfo = info
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""

        // 打印所有窗口事件，用于调试
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            Log.d(TAG, "Window changed - package: $packageName, class: $className")
            Log.d(TAG, "isWaitingForSmsConfirm: $isWaitingForSmsConfirm")
        }

        // 临时：监听所有窗口变化，看看弹窗来自哪里
        if (isWaitingForSmsConfirm) {
            Log.d(TAG, "Waiting for confirm - package: $packageName, class: $className")

            // 不限制包名，尝试查找并点击确认按钮
            findAndClickConfirmButton()
        }
    }

    private fun isSmsConfirmPackage(packageName: String): Boolean {
        return SMS_PACKAGES.contains(packageName)
    }

    private fun findAndClickConfirmButton() {
        try {
            val rootNode = rootInActiveWindow
            if (rootNode == null) {
                Log.w(TAG, "rootInActiveWindow is null")
                return
            }

            // 先检查弹窗是否与短信相关
            if (!isSmsRelatedDialog(rootNode)) {
                Log.d(TAG, "Dialog is not SMS related, skipping")
                return
            }

            Log.d(TAG, "Dialog is SMS related, looking for confirm button...")

            // 查找确认按钮 - 优先查找精确匹配
            for (text in CONFIRM_BUTTON_TEXTS) {
                val nodes = rootNode.findAccessibilityNodeInfosByText(text)
                Log.d(TAG, "Found ${nodes.size} nodes with text '$text'")

                for (node in nodes) {
                    val nodeText = node.text?.toString() ?: ""
                    Log.d(TAG, "Node text: '$nodeText', clickable: ${node.isClickable}, enabled: ${node.isEnabled}")

                    // 精确匹配确认按钮文本
                    if (nodeText == "确认发送" || nodeText == "允许" || nodeText == "确定") {
                        if (node.isClickable && node.isEnabled) {
                            Log.d(TAG, "Clicking button directly: $nodeText")
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                            isWaitingForSmsConfirm = false
                            return
                        }

                        // 尝试点击父节点
                        var parent = node.parent
                        var depth = 0
                        while (parent != null && depth < 5) {
                            Log.d(TAG, "Checking parent at depth $depth, clickable: ${parent.isClickable}")
                            if (parent.isClickable && parent.isEnabled) {
                                Log.d(TAG, "Clicking parent node")
                                parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                                isWaitingForSmsConfirm = false
                                return
                            }
                            parent = parent.parent
                            depth++
                        }
                    }
                }
            }

            // 备用方案：递归查找所有可点击的按钮
            Log.d(TAG, "Trying fallback: recursive search")
            findClickableButton(rootNode)

        } catch (e: Exception) {
            Log.e(TAG, "Error finding confirm button: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun isSmsRelatedDialog(rootNode: AccessibilityNodeInfo): Boolean {
        // 检查弹窗内容是否包含短信相关文字
        val allText = getAllText(rootNode)
        Log.d(TAG, "Dialog text: $allText")

        return allText.contains("短信") ||
                allText.contains("SMS") ||
                allText.contains("发送") ||
                allText.contains("send", ignoreCase = true) ||
                allText.contains("自动回复") ||
                allText.contains("smsautoreply", ignoreCase = true)
    }

    private fun getAllText(node: AccessibilityNodeInfo): String {
        val sb = StringBuilder()
        val text = node.text?.toString()
        if (!text.isNullOrEmpty()) {
            sb.append(text).append(" ")
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            sb.append(getAllText(child))
        }

        return sb.toString()
    }

    private fun findClickableButton(node: AccessibilityNodeInfo) {
        try {
            val text = node.text?.toString() ?: ""
            val className = node.className?.toString() ?: ""

            // 检查是否是可点击的按钮
            if (node.isClickable && node.isEnabled) {
                if (text == "确认发送" || text == "允许" || text == "确定" || text == "同意") {
                    Log.d(TAG, "Found clickable button with text: $text")
                    node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    isWaitingForSmsConfirm = false
                    return
                }
            }

            // 递归查找子节点
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                findClickableButton(child)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in findClickableButton: ${e.message}")
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        Log.d(TAG, "Accessibility service destroyed")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return super.onStartCommand(intent, flags, startId)
    }
}
