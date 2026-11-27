package com.cleanlaunch.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager

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
    private var shieldKey = ""
    private val shieldViews = mutableListOf<View>()
    private val windowManager by lazy {
        getSystemService(WINDOW_SERVICE) as WindowManager
    }

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
        if (!protectedPackages.contains(pkg)) {
            removeAdTouchShield()
        }
        val now = System.currentTimeMillis()

        // Remember the two apps being tested, so a later ad-triggered app switch
        // can be attributed to the correct source application.
        if (protectedPackages.contains(pkg)) {
            lastProtectedPackage = pkg
            lastProtectedAt = now
        }

        val root = rootInActiveWindow
        if (protectedPackages.contains(pkg)) {
            recordDiagnostic(event, pkg, root)
        }
        if (root == null) return
        try {
            val content = StringBuilder().also { collectText(root, it, 0) }.toString().lowercase()

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

            // Shield only the known ad-content view, not the whole screen.
            // A visible skip button is cut out of the shield area when it overlaps.
            if (protectedPackages.contains(pkg) && hasAdSignal) {
                updateAdTouchShield(root)
            } else {
                removeAdTouchShield()
            }

            if (protectedPackages.contains(pkg) &&
                (hasAdSignal || (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && hasSkipSignal))
            ) {
                lastAdSignalAt = now
            }

            // Prefer the actual skip control (its position may vary, including
            // the lower-left corner). Never assume a fixed screen coordinate.
            val skipClicked = protectedPackages.contains(pkg) &&
                now - lastAdSignalAt < 10_000 &&
                hasSkipSignal &&
                clickSkip(root, 0)
            if (skipClicked) {
                recordBlock("识别并尝试点击广告跳过按钮")
            }

            // Use the explicit close-ad control only as a fallback when no
            // visible skip control was successfully clicked.
            if (!skipClicked &&
                protectedPackages.contains(pkg) &&
                now - lastAdSignalAt < 10_000 &&
                hasAdSignal
            ) {
                if (clickAdClose(root, 0)) {
                    recordBlock("识别并尝试点击关闭广告按钮")
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

    private fun updateAdTouchShield(root: AccessibilityNodeInfo) {
        val adBounds = mutableListOf<Rect>()
        val skipBounds = mutableListOf<Rect>()
        collectShieldTargets(root, adBounds, skipBounds, 0)
        val desired = mutableListOf<Rect>()
        for (ad in adBounds) {
            if (ad.width() <= 1 || ad.height() <= 1) continue
            // If the skip control is not exposed in the accessibility tree,
            // reserve a small lower-left safe zone for this known app's skip button.
            val skip = skipBounds.firstOrNull { Rect.intersects(ad, it) }
                ?: if (lastProtectedPackage == "com.questions.liteai") {
                    Rect(
                        ad.left,
                        ad.top + (ad.height() * 0.76f).toInt(),
                        ad.left + (ad.width() * 0.34f).toInt(),
                        ad.bottom
                    )
                } else null
            if (skip == null) {
                desired.add(Rect(ad))
            } else {
                val cut = Rect(ad)
                if (!cut.intersect(skip)) {
                    desired.add(Rect(ad))
                } else {
                    addShieldRect(desired, ad.left, ad.top, ad.right, cut.top)
                    addShieldRect(desired, ad.left, cut.bottom, ad.right, ad.bottom)
                    addShieldRect(desired, ad.left, cut.top, cut.left, cut.bottom)
                    addShieldRect(desired, cut.right, cut.top, ad.right, cut.bottom)
                }
            }
        }
        val key = desired.joinToString("|") { "${it.left},${it.top},${it.right},${it.bottom}" }
        if (key == shieldKey) return
        removeAdTouchShield()
        if (desired.isEmpty()) return
        for (rect in desired) {
            val shield = View(this).apply {
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
                setOnTouchListener { _, _ -> true }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            val params = WindowManager.LayoutParams(
                rect.width(),
                rect.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = android.view.Gravity.TOP or android.view.Gravity.LEFT
                x = rect.left
                y = rect.top
            }
            try {
                windowManager.addView(shield, params)
                shieldViews.add(shield)
            } catch (_: Exception) {
                // If the overlay cannot be added, do not interfere with the app.
                removeAdTouchShield()
                return
            }
        }
        shieldKey = key
    }

    private fun collectShieldTargets(
        node: AccessibilityNodeInfo,
        adBounds: MutableList<Rect>,
        skipBounds: MutableList<Rect>,
        depth: Int
    ) {
        if (depth > 24) return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        val className = node.className?.toString().orEmpty()
        val label = ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).lowercase()
        if (node.isVisibleToUser && node.isEnabled &&
            className.contains("NativeVideoAdView", ignoreCase = true)
        ) {
            adBounds.add(Rect(bounds))
        }
        if (node.isVisibleToUser && node.isEnabled &&
            (label.contains("跳过") || label.contains("skip"))
        ) {
            skipBounds.add(Rect(bounds))
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            try {
                collectShieldTargets(child, adBounds, skipBounds, depth + 1)
            } finally {
                child.recycle()
            }
        }
    }

    private fun addShieldRect(out: MutableList<Rect>, left: Int, top: Int, right: Int, bottom: Int) {
        if (right - left > 1 && bottom - top > 1) {
            out.add(Rect(left, top, right, bottom))
        }
    }

    private fun removeAdTouchShield() {
        for (view in shieldViews.toList()) {
            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
                // The window may already have been removed by the system.
            }
        }
        shieldViews.clear()
        shieldKey = ""
    }

    private fun containsSensorPermissionRequest(content: String): Boolean {
        val mentionsSensor = sensorPermissionWords.any { content.contains(it) }
        val looksLikePermissionPrompt = listOf(
            "允许", "不允许", "拒绝", "permission", "allow", "deny", "访问"
        ).any { content.contains(it) }
        return mentionsSensor && looksLikePermissionPrompt
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

    private fun clickAdClose(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 20) return false
        val label = ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).lowercase()

        val isExplicitAdClose = label.contains("关闭广告") ||
            label.contains("close ad") ||
            label.contains("close advertisement")

        if (isExplicitAdClose && node.isVisibleToUser && node.isEnabled) {
            if (node.isClickable &&
                node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            ) return true
            val parent = node.parent
            if (parent != null) {
                try {
                    if (parent.isClickable &&
                        parent.isVisibleToUser &&
                        parent.isEnabled &&
                        parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    ) return true
                } finally {
                    parent.recycle()
                }
            }
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val clicked = try {
                clickAdClose(child, depth + 1)
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

    // Diagnostic capture is deliberately read-only: it does not change ad detection,
    // click handling, or redirect protection. Data stays in local app preferences.
    private fun recordDiagnostic(
        event: AccessibilityEvent,
        pkg: String,
        root: AccessibilityNodeInfo?
    ) {
        try {
            val out = StringBuilder()
            out.append("时间：").append(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())).append('\n')
            out.append("包名：").append(pkg).append('\n')
            out.append("事件类型：").append(event.eventType).append('\n')
            out.append("事件文本：")
                .append(event.text.joinToString(" ") { it?.toString() ?: "" })
                .append('\n')
            if (root == null) {
                out.append("活动窗口：无法读取（rootInActiveWindow=null）")
            } else {
                out.append("窗口节点：\n")
                appendDiagnosticNodes(root, out, 0, intArrayOf(0))
            }
            getSharedPreferences("cleanlaunch", MODE_PRIVATE).edit()
                .putString("last_diagnostic", out.toString().take(12000))
                .apply()
        } catch (_: Exception) {
            // Diagnostics must never interfere with the original interception flow.
        }
    }

    private fun appendDiagnosticNodes(
        node: AccessibilityNodeInfo,
        out: StringBuilder,
        depth: Int,
        count: IntArray
    ) {
        if (depth > 24 || count[0] >= 100 || out.length >= 11000) return
        count[0]++
        val text = node.text?.toString()?.trim().orEmpty()
        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (text.isNotEmpty() || desc.isNotEmpty() || node.isClickable) {
            val bounds = android.graphics.Rect()
            node.getBoundsInScreen(bounds)
            out.append("  ".repeat(depth.coerceAtMost(12)))
                .append("class=").append(node.className ?: "")
                .append(" text=").append(text.take(100))
                .append(" desc=").append(desc.take(100))
                .append(" clickable=").append(node.isClickable)
                .append(" enabled=").append(node.isEnabled)
                .append(" visible=").append(node.isVisibleToUser)
                .append(" bounds=").append(bounds.flattenToString())
                .append('\n')
        }
        for (i in 0 until node.childCount) {
            if (count[0] >= 100 || out.length >= 11000) break
            val child = node.getChild(i) ?: continue
            try {
                appendDiagnosticNodes(child, out, depth + 1, count)
            } finally {
                child.recycle()
            }
        }
    }

    private fun recordBlock(message: String) {
        val prefs = getSharedPreferences("cleanlaunch", MODE_PRIVATE)
        prefs.edit()
            .putInt("blocked", prefs.getInt("blocked", 0) + 1)
            .putString("last_action", message)
            .apply()
    }

    override fun onInterrupt() {
        removeAdTouchShield()
    }

    override fun onDestroy() {
        removeAdTouchShield()
        super.onDestroy()
    }
}
