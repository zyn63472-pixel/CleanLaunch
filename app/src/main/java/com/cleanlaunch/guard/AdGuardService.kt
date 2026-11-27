package com.cleanlaunch.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class AdGuardService : AccessibilityService() {
    private var lastAdSignalAt = 0L
    private var lastBackAt = 0L
    private val redirectPackages = setOf(
        "com.android.chrome", "com.google.android.googlequicksearchbox",
        "com.android.vending", "com.huawei.appmarket",
        "com.oppo.market", "com.bbk.appstore", "com.xiaomi.market"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()
        if (redirectPackages.contains(pkg) && now - lastAdSignalAt < 5000 && now - lastBackAt > 1200) {
            lastBackAt = now
            performGlobalAction(GLOBAL_ACTION_BACK)
            recordBlock("广告后打开浏览器/商店，尝试返回")
            return
        }
        val root = rootInActiveWindow ?: return
        try {
            val text = StringBuilder()
            collectText(root, text, 0)
            val content = text.toString().lowercase()
            if (listOf("广告", "sponsored", "advertisement", "跳过", "skip").any { content.contains(it) }) {
                lastAdSignalAt = now
            }
            if (now - lastAdSignalAt <= 8000 && (content.contains("跳过") || content.contains("skip"))) {
                if (clickSkip(root, 0)) recordBlock("识别并尝试点击广告跳过按钮")
            }
        } finally {
            root.recycle()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 18) return
        node.text?.let { out.append(it).append(' ') }
        node.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try { collectText(child, out, depth + 1) } finally { child.recycle() }
        }
    }

    private fun clickSkip(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 18) return false
        val label = ((node.text?.toString() ?: "") + " " + (node.contentDescription?.toString() ?: "")).lowercase()
        if ((label.contains("跳过") || label.contains("skip")) && node.isVisibleToUser && node.isEnabled) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            var parent = node.parent
            try {
                repeat(4) {
                    val p = parent ?: return@repeat
                    if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                    val next = p.parent
                    p.recycle()
                    parent = next
                }
            } finally { parent?.recycle() }
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try { clickSkip(child, depth + 1) } finally { child.recycle() }
            if (clicked) return true
        }
        return false
    }

    private fun recordBlock(message: String) {
        val prefs = getSharedPreferences("cleanlaunch", MODE_PRIVATE)
        prefs.edit().putInt("blocked", prefs.getInt("blocked", 0) + 1)
            .putString("last_action", message).apply()
    }

    override fun onInterrupt() = Unit
}
