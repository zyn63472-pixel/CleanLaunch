package com.cleanlaunch.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * On-device ad navigation guard.
 *
 * Android does not expose a general API for cancelling another app's Activity launch.
 * This service therefore detects a likely ad-triggered foreground switch and attempts
 * to return to the protected app. It only does so when a recent ad signal was observed.
 */
class AdGuardService : AccessibilityService() {
    private var lastAdSignalAt = 0L
    private var lastBackAt = 0L
    private var lastProtectedPackage: String? = null
    private var lastProtectedAt = 0L

    private val protectedPackages = setOf(
        "com.kmxs.reader",       // 七猫免费小说
        "com.questions.liteai"   // 快搜搜题
    )

    private val redirectPackages = setOf(
        "com.android.chrome",
        "com.google.android.googlequicksearchbox",
        "com.android.browser",
        "com.android.vending",
        "com.huawei.appmarket",
        "com.oppo.market",
        "com.bbk.appstore",
        "com.xiaomi.market",
        "com.tencent.android.qqdownloader"
    )

    private val permissionControllerPackages = setOf(
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller"
    )

    private val sensorPermissionWords = listOf(
        "身体传感器", "陀螺仪", "运动传感器", "body sensors",
        "physical activity", "gyroscope"
    )

    private val denyWords = listOf(
        "不允许", "拒绝", "禁止", "don't allow", "dont allow", "deny"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        event ?: return
        val pkg = event.packageName?.toString() ?: return
        val now = System.currentTimeMillis()

        // Remember the two apps being tested, so a later ad-triggered app switch
        // can be attributed to the correct source application.
        if (protectedPackages.contains(pkg)) {
            lastProtectedPackage = pkg
            lastProtectedAt = now
        }

        val root = rootInActiveWindow ?: return
        try {
            val content = buildString { collectText(root, this, 0) }.lowercase()

            // Deny only a system permission dialog that explicitly refers to
            // body sensors / motion sensors / gyroscope. Do not touch camera,
            // microphone, photos, or unrelated permission prompts.
            if (permissionControllerPackages.contains(pkg) &&
                containsSensorPermissionRequest(content)
            ) {
                if (clickByLabels(root, denyWords)) {
                    recordBlock("已拒绝传感器相关权限请求")
                    // Continue processing the current event; do not stop monitoring.
                }
                return
            }

            val hasAdSignal = listOf(
                "广告", "赞助内容", "推广", "sponsored", "advertisement", "ad choices"
            ).any { content.contains(it) }

            val hasSkipSignal = listOf("跳过", "skip").any { content.contains(it) }

            if (protectedPackages.contains(pkg) &&
                (hasAdSignal || (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && hasSkipSignal))
            ) {
                lastAdSignalAt = now
            }

            // Keep skip-button handling scoped to an observed ad context.
            if (protectedPackages.contains(pkg) &&
                now - lastAdSignalAt < 10_000 &&
                hasSkipSignal
            ) {
                if (clickSkip(root, 0)) {
                    recordBlock("识别并尝试点击广告跳过按钮")
                }
            }

            val isSystemUi = pkg.startsWith("com.android.systemui") ||
                pkg.startsWith("com.google.android.inputmethod") ||
                pkg.contains("inputmethod") ||
                permissionControllerPackages.contains(pkg)

            val recentAd = now - lastAdSignalAt < 10_000
            val recentProtectedApp = now - lastProtectedAt < 12_000
            val knownRedirect = redirectPackages.contains(pkg)
            val unexpectedSwitchAfterAd = recentAd &&
                recentProtectedApp &&
                lastProtectedPackage != null &&
                pkg != lastProtectedPackage &&
                !protectedPackages.contains(pkg) &&
                !isSystemUi

            if ((knownRedirect && recentAd) || unexpectedSwitchAfterAd) {
                if (now - lastBackAt > 1_200) {
                    lastBackAt = now
                    if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                        recordBlock("检测到广告后跳转至 $pkg，已尝试返回原应用")
                    }
                }
            }
        } finally {
            root.recycle()
        }
    }

    private fun containsSensorPermissionRequest(content: String): Boolean {
        val mentionsSensor = sensorPermissionWords.any { content.contains(it) }
        val looksLikePermissionPrompt = listOf(
            "允许", "不允许", "拒绝", "permission", "allow", "deny", "访问"
        ).any { content.contains(it) }
        return mentionsSensor && looksLikePermissionPrompt
    }

    private fun buildString(block: (StringBuilder) -> Unit): String {
        val builder = StringBuilder()
        block(builder)
        return builder.toString()
    }

    private fun collectText(node: AccessibilityNodeInfo, out: StringBuilder, depth: Int) {
        if (depth > 20) return
        node.text?.let { out.append(it).append(' ') }
        node.contentDescription?.let { out.append(it).append(' ') }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectText(child, out, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun clickByLabels(node: AccessibilityNodeInfo, labels: List<String>, depth: Int = 0): Boolean {
        if (depth > 20) return false
        val label = ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).lowercase()

        if (labels.any { label.contains(it) } && node.isVisibleToUser && node.isEnabled) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val parent = node.parent
            if (parent != null) {
                try {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } finally {
                    parent.recycle()
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try {
                clickByLabels(child, labels, depth + 1)
            } finally {
                child.recycle()
            }
            if (clicked) return true
        }
        return false
    }

    private fun clickSkip(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 20) return false
        val label = ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).lowercase()

        if ((label.contains("跳过") || label.contains("skip")) &&
            node.isVisibleToUser && node.isEnabled
        ) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val parent = node.parent
            if (parent != null) {
                try {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                } finally {
                    parent.recycle()
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try {
                clickSkip(child, depth + 1)
            } finally {
                child.recycle()
            }
            if (clicked) return true
        }
        return false
    }

    private fun recordBlock(message: String) {
        val prefs = getSharedPreferences("cleanlaunch", MODE_PRIVATE)
        prefs.edit()
            .putInt("blocked", prefs.getInt("blocked", 0) + 1)
            .putString("last_action", message)
            .apply()
    }

    override fun onInterrupt() = Unit
}
