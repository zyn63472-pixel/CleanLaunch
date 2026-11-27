package com.cleanlaunch.guard

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (18 * resources.displayMetrics.density).toInt()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
            gravity = Gravity.TOP
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setPadding(0, pad / 2, 0, pad / 2)
        }
        root.addView(label("净启助手", 26f))
        root.addView(label("帮助减少广告误触后跳转到浏览器或应用商店。"))
        root.addView(label("工作方式：使用 Android 无障碍服务识别部分广告文字和跳过按钮。"))
        root.addView(label("注意：系统限制较多，不能保证拦截所有广告或所有跳转；部分页面无法读取按钮。"))
        val settings = Button(this).apply {
            text = "打开无障碍设置"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }
        root.addView(settings)
        status = label("")
        root.addView(status)
        val count = getSharedPreferences("cleanlaunch", MODE_PRIVATE).getInt("blocked", 0)
        root.addView(label("累计处理记录：" + count + " 次"))
        root.addView(label("隐私说明：本应用不申请网络权限。无障碍权限可读取当前屏幕内容，仅用于本机识别。"))
        setContentView(root, ViewGroup.LayoutParams(-1, -1))
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::status.isInitialized) refreshStatus()
    }

    private fun refreshStatus() {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains(packageName) == true
        status.text = if (enabled) "无障碍服务：已启用（请确认系统设置中的开关）" else "无障碍服务：尚未启用"
    }
}
