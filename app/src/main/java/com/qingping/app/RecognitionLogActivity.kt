package com.qingping.app

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class RecognitionLogActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var summary: TextView
    private lateinit var query: EditText
    private lateinit var filter: Spinner
    private var lastRevision = -1L
    private var lastConnected = false
    private var displayed = emptyList<RecognitionLog.Entry>()
    private val labels = HashMap<String, String>()
    private val tick = object : Runnable {
        override fun run() { refresh(); handler.postDelayed(this, 1000L) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 12)
            setBackgroundColor(Color.WHITE)
        }
        fun text(s: String, size: Float = 14f) = TextView(this).apply { text = s; textSize = size; setPadding(0, 8, 0, 8) }
        fun button(s: String, action: () -> Unit) { page.addView(Button(this).apply { text = s; setOnClickListener { action() } }) }
        page.addView(text("应用识别日志", 23f))
        page.addView(text("开启记录后，切换到目标应用复现广告，再返回查看。仅保留最近 250 条本机记录；不会截图、保存完整页面或上传。候选文字可能含敏感信息，复制前请检查。"))
        page.addView(Switch(this).apply {
            text = "记录识别诊断"
            isChecked = RecognitionLog.enabled(this@RecognitionLogActivity)
            setOnCheckedChangeListener { _, value -> RecognitionLog.setEnabled(this@RecognitionLogActivity, value); refresh(true) }
        })
        query = EditText(this).apply {
            hint = "按应用名称、包名或原因筛选"
            isSingleLine = true
        }
        page.addView(query)
        filter = Spinner(this).apply {
            adapter = ArrayAdapter(this@RecognitionLogActivity, android.R.layout.simple_spinner_dropdown_item,
                listOf("全部记录", "拒绝候选", "失败 / 未确认 / 未发现", "匹配 / 操作 / 按钮结果", "应用 / 服务 / 窗口"))
        }
        page.addView(filter)
        summary = text("")
        page.addView(summary)
        val buttons = LinearLayout(this)
        buttons.addView(Button(this).apply {
            text = "复制筛选结果"
            setOnClickListener {
                refresh(true)
                val content = displayed.joinToString("\n\n") { format(it) }
                if (content.isEmpty()) Toast.makeText(this@RecognitionLogActivity, "没有可复制记录", Toast.LENGTH_SHORT).show()
                else {
                    (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("清屏诊断", content))
                    Toast.makeText(this@RecognitionLogActivity, "已复制，请先检查敏感信息", Toast.LENGTH_SHORT).show()
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "清空日志"
            setOnClickListener {
                AlertDialog.Builder(this@RecognitionLogActivity).setMessage("清空本机识别日志？统计次数不会清空。")
                    .setNegativeButton("取消", null).setPositiveButton("清空") { _, _ ->
                        RecognitionLog.clear(this@RecognitionLogActivity); refresh(true)
                    }.show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        page.addView(buttons)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(-1, 0, 1f))
        button("返回清屏") { finish() }
        setContentView(page)
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { refresh(true) }
            override fun afterTextChanged(s: Editable?) {}
        })
        filter.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { refresh(true) }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        refresh(true)
    }
    private fun appLabel(pkg: String) = labels.getOrPut(pkg) { StatsStore.resolveAppLabel(this, pkg) }
    private fun format(e: RecognitionLog.Entry): String = buildString {
        append(SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(e.time)))
        append("  [${e.kind}]\n${appLabel(e.pkg)} (${e.pkg})\n${e.reason}")
        if (e.text.isNotBlank()) append("\n候选：${e.text}")
        if (e.id.isNotBlank()) append("\nID：${e.id}")
        if (e.bounds.isNotBlank()) append("\n位置：${e.bounds}")
        if (e.signals.isNotBlank()) append("\n${e.signals}")
    }
    private fun refresh(force: Boolean = false) {
        if (!::list.isInitialized || (!force && lastRevision == RecognitionLog.revision && lastConnected == AdGuardAccessibilityService.isConnected)) return
        val entries = RecognitionLog.entries(this)
        val q = query.text.toString().trim()
        displayed = entries.filter { e ->
            val kindMatches = when (filter.selectedItemPosition) {
                1 -> e.kind == "拒绝"
                2 -> e.kind in setOf("点击失败", "读取失败", "无法读取", "结果未确认", "未发现候选", "按钮仍在")
                3 -> e.kind in setOf("匹配", "操作已接受", "按钮仍在", "按钮已消失", "自动返回")
                4 -> e.kind in setOf("应用识别", "服务", "窗口切换", "跳过页面", "冷却")
                else -> true
            }
            kindMatches && (q.isEmpty() || "${appLabel(e.pkg)} ${e.pkg} ${e.reason} ${e.text} ${e.id}".contains(q, true))
        }
        summary.text = "${if (RecognitionLog.enabled(this)) "记录中" else "记录已暂停"} · 显示 ${displayed.size} / ${entries.size} 条 · ${if (AdGuardAccessibilityService.isConnected) "服务已连接" else "服务未连接"}"
        list.removeAllViews()
        if (displayed.isEmpty()) list.addView(TextView(this).apply { text = "暂无匹配记录。确认服务已连接并开启记录，然后切到目标应用复现。" })
        displayed.forEach { e ->
            list.addView(TextView(this).apply {
                text = format(e)
                textSize = 13f
                setTextIsSelectable(true)
                setTextColor(if (e.kind == "拒绝" || e.kind.contains("失败")) Color.rgb(150, 70, 20) else Color.DKGRAY)
                setPadding(0, 14, 0, 14)
            })
        }
        lastRevision = RecognitionLog.revision
        lastConnected = AdGuardAccessibilityService.isConnected
    }
    override fun onResume() { super.onResume(); lastRevision = -1; handler.post(tick) }
    override fun onPause() { handler.removeCallbacks(tick); super.onPause() }
}
