package com.qingping.app

import android.accessibilityservice.AccessibilityServiceInfo
import android.view.accessibility.AccessibilityManager
import android.content.ActivityNotFoundException
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.widget.Toast
import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private val statusHandler = Handler(Looper.getMainLooper())
    private val refreshStatus = object : Runnable {
        override fun run() {
            updateServiceStatus()
            statusHandler.postDelayed(this, 1000L)
        }
    }

    private lateinit var page: LinearLayout
    private var currentTarget: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) render()
        statusHandler.removeCallbacks(refreshStatus)
        statusHandler.post(refreshStatus)
    }

    override fun onPause() {
        statusHandler.removeCallbacks(refreshStatus)
        super.onPause()
    }

    private fun render() {
        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Color.rgb(247, 248, 251))

        page = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        scroll.addView(
            page,
            android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        setContentView(scroll)

        addText("清屏", 30f, true, Color.rgb(26, 35, 52))
        addText("开屏广告辅助拦截与本地统计", 15f, false, Color.rgb(93, 103, 119), top = 2)

        addCard {
            statusText = makeText("正在检查服务状态…", 18f, true, Color.rgb(180, 91, 20))
            (currentTarget ?: page).addView(statusText, matchWrap())
            updateServiceStatus()
            addText(
                "在系统设置中手动启用“清屏广告辅助服务”，确认系统提示后返回。开关已开但服务未连接时，请关闭再开启一次，并等待几秒。",
                14f, false, Color.rgb(70, 79, 94), top = 8
            )
            addButton("打开系统无障碍设置") { openAccessibilitySettings() }
            addButton("重新检测服务状态") { updateServiceStatus() }
            if (android.os.Build.MANUFACTURER.equals("HUAWEI", ignoreCase = true) ||
                android.os.Build.BRAND.equals("HUAWEI", ignoreCase = true)) {
                addText(
                    "华为畅享 60：在“设置 → 辅助功能 → 无障碍”中找到本服务并确认开启。若后台运行后经常断开，可在“应用启动管理”中为清屏关闭自动管理，并允许自启动、关联启动和后台活动。菜单名称可能随系统版本不同。",
                    13f, false, Color.rgb(93, 103, 119), top = 8
                )
                addButton("打开清屏应用信息") { openAppDetails() }
            }
        }

        addCard {
            addText("实验性功能", 18f, true, Color.rgb(26, 35, 52))
            val autoBack = Switch(this@MainActivity).apply {
                text = "疑似误触跳转后自动返回"
                textSize = 15f
                isChecked = StatsStore.autoBackEnabled(this@MainActivity)
                setOnCheckedChangeListener { _, checked ->
                    StatsStore.setAutoBackEnabled(this@MainActivity, checked)
                }
            }
            (currentTarget ?: page).addView(autoBack, matchWrap(top = 8))
            addText(
                "默认关闭。开启后，如果你在某个应用中刚点击界面，随后切换到浏览器或应用商店，清屏会尝试返回。它无法判断你是否真的误触，因此也可能拦截你主动打开的链接。",
                13f, false, Color.rgb(106, 76, 46), top = 6
            )
        }

        val stats = StatsStore.getStats(this)
        val total = stats.sumOf { it.count }

        addCard {
            addText("拦截统计", 20f, true, Color.rgb(26, 35, 52))
            addText("累计成功执行操作：$total 次", 16f, true, Color.rgb(35, 86, 160), top = 8)
            addText("按应用统计；只统计清屏成功点击关闭按钮或成功执行返回的次数。", 13f, false, Color.rgb(93, 103, 119), top = 4)

            if (stats.isEmpty()) {
                addText(
                    "暂无记录。启用服务后，遇到带有可识别广告文字和关闭按钮的页面时，清屏会尝试处理。",
                    14f, false, Color.rgb(93, 103, 119), top = 14
                )
            } else {
                stats.forEach { stat ->
                    addDivider(top = 12)
                    val row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val left = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    left.addView(makeText(stat.label, 16f, true, Color.rgb(35, 43, 58)))
                    left.addView(makeText(stat.packageName, 11f, false, Color.rgb(105, 113, 128)).apply {
                        setPadding(0, dp(3), 0, 0)
                    })
                    left.addView(makeText("最近记录：${StatsStore.formatTimestamp(stat.lastBlockedAt)}", 12f, false, Color.rgb(105, 113, 128)).apply {
                        setPadding(0, dp(3), 0, 0)
                    })
                    row.addView(left, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                    row.addView(makeText("${stat.count} 次", 16f, true, Color.rgb(35, 86, 160)))
                    (currentTarget ?: page).addView(row, matchWrap(top = 8))
                }
                addButton("清空统计") {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("清空统计")
                        .setMessage("确定删除所有应用的拦截统计吗？此操作不可撤销。")
                        .setNegativeButton("取消", null)
                        .setPositiveButton("清空") { _, _ ->
                            StatsStore.clearStats(this@MainActivity)
                            render()
                        }
                        .show()
                }
            }
        }

        addText("隐私与能力说明", 17f, true, Color.rgb(26, 35, 52), top = 6)
        addText(
            "清屏不申请网络权限，统计保存在本机。它只读取无障碍服务能够访问的界面信息；广告按钮若没有可访问的文字或控件，可能无法识别。Android 和手机厂商仍可能限制后台运行。",
            13f, false, Color.rgb(93, 103, 119), top = 6
        )
    }

    private fun addCard(content: LinearLayout.() -> Unit) {
        val card = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setBackgroundColor(Color.WHITE)
        }
        val previousTarget = currentTarget
        currentTarget = card
        card.content()
        currentTarget = previousTarget

        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        params.topMargin = dp(16)
        page.addView(card, params)
    }

    private fun addText(
        text: String,
        size: Float,
        bold: Boolean,
        color: Int,
        top: Int = 0
    ) {
        (currentTarget ?: page).addView(makeText(text, size, bold, color), matchWrap(top))
    }

    private fun makeText(text: String, size: Float, bold: Boolean, color: Int): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setLineSpacing(dp(2).toFloat(), 1f)
        }
    }

    private fun addButton(label: String, action: () -> Unit) {
        val button = Button(this).apply {
            text = label
            setOnClickListener { action() }
        }
        (currentTarget ?: page).addView(button, matchWrap(top = 10))
    }

    private fun addDivider(top: Int = 0) {
        val divider = View(this).apply { setBackgroundColor(Color.rgb(230, 233, 239)) }
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            dp(1)
        )
        params.topMargin = dp(top)
        (currentTarget ?: page).addView(divider, params)
    }

    private fun matchWrap(top: Int = 0): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    private fun updateServiceStatus() {
        if (!::statusText.isInitialized) return
        if (AdGuardAccessibilityService.isConnected) {
            statusText.text = "服务状态：已连接，正在运行"
            statusText.setTextColor(Color.rgb(24, 125, 75))
            return
        }
        val component = ComponentName(this, AdGuardAccessibilityService::class.java)
        // OEM settings providers and AccessibilityManager can update at different times.
        val managerEnabled = try {
            val manager = getSystemService(ACCESSIBILITY_SERVICE) as AccessibilityManager
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                .any { info ->
                    val service = info.resolveInfo.serviceInfo
                    ComponentName(service.packageName, service.name) == component
                }
        } catch (_: RuntimeException) {
            null
        }
        val settingsEnabled = try {
            Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':')
                .any { ComponentName.unflattenFromString(it.trim()) == component }
        } catch (_: RuntimeException) {
            null
        }
        statusText.text = when {
            managerEnabled == true || settingsEnabled == true ->
                "服务状态：系统已启用，等待服务连接"
            managerEnabled == null && settingsEnabled == null ->
                "服务状态：无法读取，请在系统设置中确认"
            else -> "服务状态：尚未启用，请在系统设置中开启"
        }
        statusText.setTextColor(Color.rgb(180, 91, 20))
    }

    private fun openAccessibilitySettings() {
        // Launch the system Settings task independently of QingPing's task.
        // Use standard Android actions; Huawei private activity names vary by release.
        if (tryOpenSystemSettings(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))) return
        if (tryOpenSystemSettings(Intent(Settings.ACTION_SETTINGS))) return
        Toast.makeText(this, "无法打开设置，请手动进入系统设置 → 辅助功能 → 无障碍", Toast.LENGTH_LONG).show()
    }

    private fun openAppDetails() {
        if (!tryOpenSystemSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:$packageName")))) {
            Toast.makeText(this, "请在系统设置的应用管理中找到清屏", Toast.LENGTH_LONG).show()
        }
    }

    private fun tryOpenSystemSettings(intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }
}
