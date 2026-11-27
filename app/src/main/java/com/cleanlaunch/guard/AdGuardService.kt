package com.cleanlaunch.guard

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.View
import android.view.WindowManager
import android.content.pm.ApplicationInfo

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

        if (!isProtectedTarget(pkg)) removeAdTouchShield()

        // Resolve the event's own app window, not blindly rootInActiveWindow.
        // The active root can belong to System UI / Recents while the event still
        // carries the package name of the app underneath it.
        val root = if (isProtectedTarget(pkg)) {
            findRootForPackage(pkg, rootInActiveWindow)
        } else {
            rootInActiveWindow
        }

        if (isProtectedTarget(pkg)) {
            recordDiagnostic(event, pkg, root)
            // Do not treat a stale event package as proof that the protected app
            // is currently visible. This prevents Recents/task-switcher nodes from
            // being mistaken for the ad page.
            if (root == null) {
                removeAdTouchShield()
                return
            }
            lastProtectedPackage = pkg
            lastProtectedAt = now
        }

        // Redirect recovery must run even if the destination window has no readable root.
        val isSystemUi = pkg.startsWith("com.android.systemui") ||
            pkg.startsWith("com.google.android.inputmethod") ||
            pkg.contains("inputmethod") ||
            pkg.contains("launcher") ||
            pkg == "com.android.launcher" ||
            pkg == "com.google.android.apps.nexuslauncher" ||
            permissionControllerPackages.contains(pkg)

        // Only treat a known browser/app-store destination as an ad redirect.
        // A generic switch to another app (for example, the user opening ChatGPT
        // after leaving the protected app) is normal navigation and must never
        // trigger BACK. Keep this window short to reduce false positives.
        val recentAd = now - lastAdSignalAt < 3_000
        val knownRedirect = redirectPackages.contains(pkg)

        if (knownRedirect && recentAd && !isSystemUi) {
            if (now - lastBackAt > 1_200) {
                lastBackAt = now
                if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                    recordBlock("检测到广告后跳转至 $pkg，已尝试返回原应用")
                }
            }
        }

        if (root == null) return
        try {
            val content = StringBuilder().also { collectText(root, it, 0) }.toString().lowercase()

            // Deny only a system permission dialog that explicitly refers to
            // body sensors / motion sensors / gyroscope.
            if (permissionControllerPackages.contains(pkg) &&
                containsSensorPermissionRequest(content)
            ) {
                if (clickByLabels(root, denyWords)) {
                    recordBlock("已拒绝传感器相关权限请求")
                }
                return
            }

            val hasAdSignal = listOf(
                "广告", "赞助内容", "推广", "sponsored", "advertisement", "ad choices",
                "广告剩余", "广告倒计时", "精彩内容即将开始", "广告即将结束"
            ).any { content.contains(it) }

            val hasSkipSignal = listOf("跳过", "跳过广告", "略过", "skip", "skip ad")
                .any { content.contains(it) }
            val hasAdContainer = isProtectedTarget(pkg) && hasKnownAdContainer(root)
            // Countdown text is useful evidence only when it appears with an ad
            // marker/container or an explicit skip control; ordinary page timers
            // must not independently classify a page as an advertisement.
            val hasCountdownSignal = hasAdCountdown(root)
            val isAdSurface = hasAdSignal || hasAdContainer ||
                (hasCountdownSignal && (hasAdSignal || hasAdContainer || hasSkipSignal))

            if (isProtectedTarget(pkg) && isAdSurface) {
                updateAdTouchShield(root)
            } else {
                removeAdTouchShield()
            }

            if (isProtectedTarget(pkg) &&
                (isAdSurface || (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED && hasSkipSignal))
            ) {
                lastAdSignalAt = now
            }

            val skipClicked = isProtectedTarget(pkg) &&
                now - lastAdSignalAt < 10_000 &&
                hasSkipSignal &&
                clickSkip(root, 0)
            if (skipClicked) recordBlock("识别并尝试点击广告跳过按钮")

            if (!skipClicked &&
                isProtectedTarget(pkg) &&
                now - lastAdSignalAt < 10_000 &&
                hasAdSignal
            ) {
                if (clickAdClose(root, 0)) {
                    recordBlock("识别并尝试点击关闭广告按钮")
                }
            }
        } finally {
            root.recycle()
        }
    }

    /**
     * GKD-inspired window/context matching: resolve a node tree for the exact
     * package that emitted the event. A stale event must never authorize actions
     * against a different app's active root.
     */
    private fun findRootForPackage(
        targetPackage: String,
        activeRoot: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        // Only an actually visible active app window may authorize clicks or shields.
        // Matching a package in Recents is not enough: task cards can expose stale
        // accessibility nodes while the launcher is the foreground application.
        if (activeRoot != null) {
            val matches = activeRoot.packageName?.toString() == targetPackage &&
                activeRoot.isVisibleToUser
            if (matches) return activeRoot
            activeRoot.recycle()
        }

        val allWindows = try {
            getWindows()
        } catch (_: Exception) {
            return null
        }

        for (window in allWindows) {
            try {
                // Do not interact with background/inactive windows, even if their
                // package matches the event that triggered this callback.
                if (!window.isActive || !window.isFocused) continue
                val candidate = window.root ?: continue
                val matches = candidate.packageName?.toString() == targetPackage &&
                    candidate.isVisibleToUser
                if (matches) return candidate
                candidate.recycle()
            } catch (_: Exception) {
                // A window may disappear while the system is updating its stack.
            } finally {
                try { window.recycle() } catch (_: Exception) {}
            }
        }
        return null
    }

    private fun hasAdCountdown(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 24 || !node.isVisibleToUser) return false
        val text = ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).lowercase()
        val countdownPatterns = listOf(
            Regex("(跳过|skip)\\s*[:：]?\\s*\\d+"),
            Regex("\\d+\\s*(秒|秒后|s)\\s*(后)?\\s*(跳过|skip)"),
            Regex("(广告|ad).{0,12}\\d+\\s*(秒|s)")
        )
        if (countdownPatterns.any { it.containsMatchIn(text) }) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                hasAdCountdown(child, depth + 1)
            } finally {
                child.recycle()
            }
            if (found) return true
        }
        return false
    }

    private fun hasKnownAdContainer(node: AccessibilityNodeInfo, depth: Int = 0): Boolean {
        if (depth > 24) return false
        if (node.isVisibleToUser && node.isEnabled && isKnownAdContainerNode(node)) return true
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val found = try {
                hasKnownAdContainer(child, depth + 1)
            } finally {
                child.recycle()
            }
            if (found) return true
        }
        return false
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
        val label = ((node.text?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "")).lowercase()
        val validBounds = bounds.width() > 1 && bounds.height() > 1 &&
            bounds.right > 0 && bounds.bottom > 0 &&
            bounds.left < resources.displayMetrics.widthPixels &&
            bounds.top < resources.displayMetrics.heightPixels
        if (node.isVisibleToUser && node.isEnabled && validBounds &&
            isKnownAdContainerNode(node)
        ) {
            adBounds.add(Rect(bounds))
        }
        if (node.isVisibleToUser && node.isEnabled && validBounds &&
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

    private fun isKnownAdContainerNode(node: AccessibilityNodeInfo): Boolean {
        val identity = ((node.className?.toString() ?: "") + " " +
            (node.contentDescription?.toString() ?: "") + " " +
            (node.text?.toString() ?: "")).lowercase()
        return identity.contains("nativevideoadview") ||
            identity.contains("nativeadview") ||
            identity.contains("rewardedadview") ||
            identity.contains("splashadview")
    }

    // Protect user-installed apps by default; skip this app and system packages.
    private fun isProtectedTarget(pkg: String): Boolean {
        if (pkg == packageName || permissionControllerPackages.contains(pkg)) return false
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            (info.flags and ApplicationInfo.FLAG_SYSTEM) == 0
        } catch (_: Exception) {
            false
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
        // Ignore stale/off-screen accessibility nodes. Some apps keep hidden
        // carousel pages and recycled ad controls in the tree with visible=false.
        if (depth > 20 || !node.isVisibleToUser) return
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

        val targetBounds = Rect().also { node.getBoundsInScreen(it) }
        val validTargetBounds = targetBounds.width() > 1 && targetBounds.height() > 1 &&
            targetBounds.right > 0 && targetBounds.bottom > 0 &&
            targetBounds.left < resources.displayMetrics.widthPixels &&
            targetBounds.top < resources.displayMetrics.heightPixels
        if ((label.contains("跳过") || label.contains("skip")) &&
            node.isVisibleToUser && node.isEnabled && validTargetBounds
        ) {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            val parent = node.parent
            if (parent != null) {
                try {
                    val parentBounds = Rect().also { parent.getBoundsInScreen(it) }
                    val isReasonableTarget = parentBounds.width() > 0 &&
                        parentBounds.height() > 0 &&
                        parentBounds.width() <= targetBounds.width() * 4 &&
                        parentBounds.height() <= targetBounds.height() * 4 &&
                        Rect.intersects(parentBounds, targetBounds)
                    if (isReasonableTarget && parent.isClickable &&
                        parent.isVisibleToUser && parent.isEnabled &&
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
        matchedRoot: AccessibilityNodeInfo?
    ) {
        try {
            val out = StringBuilder()
            out.append("时间：").append(java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.getDefault())
                .format(java.util.Date())).append('\n')
            out.append("事件包名：").append(pkg).append('\n')
            out.append("事件类型：").append(event.eventType).append('\n')
            out.append("事件窗口ID：").append(event.windowId).append('\n')
            out.append("事件文本：")
                .append(event.text.joinToString(" ") { it?.toString() ?: "" })
                .append('\n')
            out.append("匹配的前台应用窗口：")
                .append(matchedRoot?.packageName?.toString() ?: "未找到（可能处于桌面/最近任务/切换动画）")
                .append('\n')
            out.append("处理策略：")
                .append(if (matchedRoot == null) "不点击、不遮挡，等待目标应用真正进入前台" else "仅处理已确认的前台目标窗口")
                .append('\n')

            // Capture a compact inventory of every accessible window. This makes
            // Recents/System UI overlays distinguishable from the underlying app.
            val allWindows = try { getWindows() } catch (_: Exception) { emptyList() }
            var nodeBudget = 0
            for (window in allWindows) {
                try {
                    val bounds = Rect()
                    window.getBoundsInScreen(bounds)
                    val windowRoot = window.root
                    try {
                        out.append("窗口：id=").append(window.id)
                            .append(" type=").append(window.type)
                            .append(" layer=").append(window.layer)
                            .append(" active=").append(window.isActive)
                            .append(" focused=").append(window.isFocused)
                            .append(" bounds=").append(bounds.flattenToString())
                            .append(" rootPkg=").append(windowRoot?.packageName?.toString() ?: "null")
                            .append('\n')
                        if (windowRoot != null &&
                            (windowRoot.packageName?.toString() == pkg || window.isActive) &&
                            nodeBudget < 80
                        ) {
                            val count = intArrayOf(nodeBudget)
                            appendDiagnosticNodes(windowRoot, out, 1, count)
                            nodeBudget = count[0]
                        }
                    } finally {
                        windowRoot?.recycle()
                    }
                } catch (_: Exception) {
                    out.append("窗口：读取时已消失\n")
                } finally {
                    try { window.recycle() } catch (_: Exception) {}
                }
                if (out.length >= 11000) break
            }
            if (allWindows.isEmpty() && matchedRoot != null) {
                out.append("匹配窗口节点：\n")
                appendDiagnosticNodes(matchedRoot, out, 0, intArrayOf(0))
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
