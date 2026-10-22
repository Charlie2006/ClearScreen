package com.qingping.app

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
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var page: LinearLayout
    private var currentTarget: LinearLayout? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    override fun onResume() {
        super.onResume()
        if (::page.isInitialized) render()
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
            addText(
                if (isAccessibilityEnabled()) "服务状态：已启用" else "服务状态：尚未启用",
                18f, true,
                if (isAccessibilityEnabled()) Color.rgb(24, 125, 75) else Color.rgb(180, 91, 20)
            )
            addText(
                "首次使用请进入系统无障碍设置，手动启用“清屏广告辅助服务”。清屏不会也不能替你授予这项权限。",
                14f, false, Color.rgb(70, 79, 94), top = 8
            )
            addButton("打开无障碍设置") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false

        val component = ComponentName(this, AdGuardAccessibilityService::class.java)
        val full = component.flattenToString()
        val short = component.flattenToShortString()

        return enabled.split(':').any {
            it.equals(full, ignoreCase = true) ||
            it.equals(short, ignoreCase = true)
        }
    }
}
