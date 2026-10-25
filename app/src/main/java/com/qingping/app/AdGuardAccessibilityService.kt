package com.qingping.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.app.KeyguardManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.ArrayDeque
import java.util.Locale

class AdGuardAccessibilityService : AccessibilityService() {
    companion object {
        @Volatile var isConnected = false
            private set
    }
    private val handler = Handler(Looper.getMainLooper())
    private val window = SplashWindow()
    private val foreground get() = window.packageName
    private val foregroundAt get() = window.startedAt
    private val generation get() = window.generation
    private var homePackage: String? = null
    private var pendingPackage: String? = null
    private var pendingAt = 0L
    private var pendingToken = 0
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) resetWindow()
        }
    }
    private val adLabel = Regex("广告|推广|赞助|sponsored|advertisement|\\bad\\b")
    private var lastScanAt = 0L
    private var lastActionAt = -10000L
    private var lastActionPackage: String? = null
    private var lastClickedPackage: String? = null
    private var lastClickAt = 0L
    private var gesturePending = false

    private data class Entry(val node: AccessibilityNodeInfo, val info: AdRules.Node)
    private data class Snapshot(val entries: List<Entry>, val truncated: Boolean)

    private fun protected(pkg: String) = pkg == packageName || pkg == homePackage || pkg in setOf(
        "com.android.settings", "com.android.systemui", "com.huawei.systemmanager",
        "com.android.permissioncontroller", "com.google.android.permissioncontroller",
        "com.android.packageinstaller", "com.google.android.packageinstaller")

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }

    private fun resetWindow() {
        window.reset()
        pendingPackage = null
        pendingToken++
        handler.removeCallbacksAndMessages(null)
        gesturePending = false
        lastClickedPackage = null
    }

    private inline fun guarded(block: () -> Unit) {
        try { block() } catch (e: RuntimeException) {
            Log.w("QingPingService", "Accessibility callback failed", e)
            // Logging must not cause another exception to escape the service callback.
            try { RecognitionLog.add(this, foreground.orEmpty(), "读取失败", "回调异常：${e.javaClass.simpleName}") }
            catch (_: RuntimeException) { /* Android log above remains available. */ }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        resetWindow()
        isConnected = true
        guarded {
            homePackage = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
            RecognitionLog.add(this, packageName, "服务", "服务已连接，仅在应用进入后 10 秒内识别")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = guarded { handleEvent(event) }

    private fun handleEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        if (protected(pkg)) {
            if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != "com.android.systemui") {
                resetWindow()
            }
            return
        }
        val now = SystemClock.elapsedRealtime()
        val entryEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        if (entryEvent && pkg == foreground && pendingPackage != pkg) {
            pendingPackage = null
            pendingToken++
        }
        if (!window.acceptsEvent(pkg, entryEvent, now)) return
        if (entryEvent && (!(getSystemService(POWER_SERVICE) as PowerManager).isInteractive ||
            (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked)) {
            resetWindow()
            return
        }
        if (entryEvent && pkg != foreground && pendingPackage != pkg) {
            // A launch event may precede its accessible root. Retry within the original deadline.
            pendingPackage = pkg
            pendingAt = now
            val token = ++pendingToken
            listOf(300L, 750L, 1500L, 2500L, 4000L, 6000L, 8000L, 9500L).forEach { delay ->
                handler.postDelayed({
                    if (pendingToken == token && SystemClock.elapsedRealtime() - pendingAt < SplashWindow.DURATION_MS)
                        scan("等待开屏窗口", pkg, allowEntry = true)
                }, delay)
            }
        }
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            if (!window.active(now)) return
            lastClickedPackage = pkg
            lastClickAt = now
            return
        }
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) scan("界面事件", pkg, event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
    }

    private fun scan(source: String, eventPackage: String? = null, allowEntry: Boolean = false) {
        if (!isConnected) return
        val now = SystemClock.elapsedRealtime()
        // Expired content/click events return before any cross-process node access.
        val entering = allowEntry && eventPackage != foreground
        if (!window.acceptsEvent(eventPackage, allowEntry, now)) return
        if (!entering && now - lastScanAt < 250L) return
        lastScanAt = now
        try {
            val root = rootInActiveWindow
            if (root == null) {
                RecognitionLog.add(this, eventPackage ?: foreground.orEmpty(), "无法读取", "当前无可访问窗口；等待内容变化或开屏重试")
                return
            }
            val pkg = root.packageName?.toString() ?: return
            if (protected(pkg)) return
            if (eventPackage != null && eventPackage != pkg) {
                return // Stale events, IME and overlays cannot start another application's window.
            }
            if (foreground != pkg) {
                val previous = foreground
                val autoBack = previous != null && lastClickedPackage == previous &&
                    now - lastClickAt in 0..1600 && StatsStore.autoBackEnabled(this) && isBrowserOrStore(pkg)
                if (!allowEntry) return
                handler.removeCallbacksAndMessages(null)
                gesturePending = false
                window.enter(pkg, if (pendingPackage == pkg) pendingAt else now)
                val current = generation
                RecognitionLog.add(this, pkg, "应用识别", "进入应用，开始 10 秒开屏识别窗口")
                // Some ads render after the initial window event or omit content change events.
                listOf(300L, 750L, 1500L, 2500L, 4000L, 6000L, 8000L, 9500L).forEach { delay ->
                    val remaining = foregroundAt + delay - SystemClock.elapsedRealtime()
                    if (remaining > 0) handler.postDelayed({
                        if (generation == current && foreground == pkg) scan("开屏重试")
                    }, remaining)
                }
                if (autoBack) {
                    lastClickedPackage = null
                    val accepted = performGlobalAction(GLOBAL_ACTION_BACK)
                    RecognitionLog.add(this, pkg, "自动返回", if (accepted) "系统接受返回；不代表已阻止广告跳转" else "系统拒绝返回")
                    if (accepted) StatsStore.record(this, previous!!)
                    return
                }
            }
            if (!window.active(SystemClock.elapsedRealtime())) return
            if (gesturePending || (lastActionPackage == pkg && now - lastActionAt < 1500)) {
                RecognitionLog.add(this, pkg, "冷却", "上次操作后冷却中，避免重复点击")
                return
            }
            val snapshot = collect(root)
            val entries = snapshot.entries
            val rootRect = Rect().also { root.getBoundsInScreen(it) }
            if (rootRect.isEmpty) {
                RecognitionLog.add(this, pkg, "无法读取", "当前窗口没有有效屏幕范围")
                return
            }
            val screenBox = AdRules.Box(rootRect.left, rootRect.top, rootRect.right, rootRect.bottom)
            val labels = entries.filter { it.info.visible && !it.info.editable }.flatMap { listOf(it.info.text, it.info.description) }
                .joinToString(" ").lowercase(Locale.ROOT)
            val ad = adLabel.containsMatchIn(labels)
            val motion = listOf("摇一摇", "摇动手机", "扭一扭", "转动手机", "滑动跳转", "滑动进入", "摇动跳转")
                .any { labels.contains(it) }
            val onboarding = (labels.contains("用户协议") && labels.contains("隐私政策") &&
                (labels.contains("同意") || labels.contains("接受"))) ||
                listOf("欢迎使用", "新手引导", "跳过登录", "跳过注册", "权限申请").any { labels.contains(it) }
            val startupSkip = RecognitionLog.startupSkipEnabled(this)
            var candidates = 0
            for (entry in entries) {
                val n = entry.info
                val screen = AdRules.Screen(screenBox, now - foregroundAt, ad, motion, onboarding, startupSkip = startupSkip)
                val d = AdRules.decide(n, screen)
                if (!d.candidate) continue
                candidates++
                val signals = "来源=$source；开屏直点=$startupSkip；开屏=${screen.ageMs}ms；广告=$ad；摇动/滑动=$motion；右上角=${AdRules.topRight(n, screenBox)}；节点=${entries.size}${if (snapshot.truncated) "(达到扫描上限)" else ""}"
                if (candidates <= 12) logCandidate(pkg, if (d.click) "匹配" else "拒绝", d.reason, n, signals)
                if (!d.click) continue
                if (!window.active(SystemClock.elapsedRealtime())) return
                if (clickNode(entry.node, pkg, screenBox)) {
                    accepted(pkg, n, "系统接受节点/小范围父控件点击", signals)
                    return
                }
                if (RecognitionLog.gestureEnabled(this)) {
                    tryGesture(pkg, n, signals)
                    return
                }
                logCandidate(pkg, "点击失败", "节点及安全范围父控件不接受点击；手势兜底未开启", n, signals)
            }
            if (candidates == 0) {
                RecognitionLog.add(this, pkg, "未发现候选", "未读取到跳过/关闭候选；可能是图片、自绘控件或尚未加载", signals =
                    "来源=$source；节点=${entries.size}；截断=${snapshot.truncated}；广告=$ad；摇动/滑动=$motion")
            } else if (candidates > 12) {
                RecognitionLog.add(this, pkg, "扫描", "发现 $candidates 个候选，仅记录前 12 个明细，全部候选均已检查")
            }
        } catch (e: RuntimeException) {
            guarded { RecognitionLog.add(this, foreground.orEmpty(), "读取失败", "窗口可能已销毁：${e.javaClass.simpleName}") }
        }
    }

    private fun logCandidate(pkg: String, kind: String, reason: String, n: AdRules.Node, signals: String) {
        // Only candidate labels, never a full screen dump or editable-field contents.
        RecognitionLog.add(this, pkg, kind, reason, if (n.editable) "[输入控件已隐藏]" else n.text.ifBlank { n.description },
            n.id, n.box.toString(), signals)
    }

    private fun collect(root: AccessibilityNodeInfo): Snapshot {
        val output = ArrayList<Entry>()
        val queue = ArrayDeque<Pair<AccessibilityNodeInfo, Int>>()
        queue.add(root to 0)
        var truncated = false
        val deadline = SystemClock.elapsedRealtime() + 60L
        // A time budget bounds work between node IPC calls (an individual IPC may take longer).
        while (queue.isNotEmpty() && output.size < 600) {
            if (SystemClock.elapsedRealtime() >= deadline || !window.active(SystemClock.elapsedRealtime())) {
                truncated = true
                break
            }
            val (node, depth) = queue.removeFirst()
            val rect = Rect().also { node.getBoundsInScreen(it) }
            val editable = node.isEditable || node.isPassword
            output.add(Entry(node, AdRules.Node(if (editable) "" else node.text?.toString().orEmpty(),
                if (editable) "" else node.contentDescription?.toString().orEmpty(), node.viewIdResourceName.orEmpty(),
                AdRules.Box(rect.left, rect.top, rect.right, rect.bottom), node.isVisibleToUser, node.isEnabled, editable)))
            if (depth >= 50) { truncated = true; continue }
            for (i in 0 until node.childCount) {
                if (SystemClock.elapsedRealtime() >= deadline || !window.active(SystemClock.elapsedRealtime())) {
                    truncated = true
                    break
                }
                if (queue.size + output.size >= 600) { truncated = true; break }
                node.getChild(i)?.let { queue.add(it to depth + 1) }
            }
        }
        return Snapshot(output, truncated || queue.isNotEmpty())
    }

    private fun clickNode(node: AccessibilityNodeInfo, pkg: String, screen: AdRules.Box): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = candidate ?: return false
            if (current.packageName?.toString() != pkg || !current.isVisibleToUser || !current.isEnabled ||
                current.isEditable || current.isPassword) return false
            val b = Rect().also { current.getBoundsInScreen(it) }
            if (b.isEmpty || b.left < screen.left || b.top < screen.top || b.right > screen.right ||
                b.bottom > screen.bottom || b.width() > screen.width * 0.5 || b.height() > screen.height * 0.22) return false
            if (!window.active(SystemClock.elapsedRealtime())) return false
            if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            candidate = current.parent
        }
        return false
    }

    private fun accepted(pkg: String, n: AdRules.Node, reason: String, signals: String) {
        lastActionPackage = pkg
        lastActionAt = SystemClock.elapsedRealtime()
        StatsStore.record(this, pkg)
        logCandidate(pkg, "操作已接受", "$reason；等待检查按钮是否消失", n, signals)
        val current = generation
        handler.postDelayed({ guarded {
            if (generation != current || !window.active(SystemClock.elapsedRealtime())) return@guarded
            val root = try { rootInActiveWindow } catch (_: RuntimeException) { null }
            if (root?.packageName?.toString() != pkg) {
                logCandidate(pkg, "结果未确认", "窗口已切换，不能确认广告是否关闭", n, signals)
            } else {
                try {
                    val result = collect(root)
                    val stillPresent = result.entries.any { entry ->
                        entry.info.visible && if (n.id.isNotBlank()) entry.info.id == n.id else
                            entry.info.box == n.box &&
                                (AdRules.normalize(entry.info.text) == AdRules.normalize(n.text) ||
                                    (entry.info.text.contains("跳过") && n.text.contains("跳过")))
                    }
                    logCandidate(pkg, if (stillPresent) "按钮仍在" else if (result.truncated) "结果未确认" else "按钮已消失",
                        if (!stillPresent && result.truncated) "扫描达到预算，不能确认按钮是否消失" else if (stillPresent) "点击后候选仍可见，后续扫描可重试" else "候选已不可见；不等于确认所有广告已关闭", n, signals)
                } catch (_: RuntimeException) {
                    logCandidate(pkg, "结果未确认", "检查期间窗口已失效", n, signals)
                }
            }
        } }, 500L)
    }

    private fun tryGesture(pkg: String, n: AdRules.Node, signals: String) {
        if (rootInActiveWindow?.packageName?.toString() != pkg) {
            logCandidate(pkg, "点击失败", "手势前窗口已切换，取消点击", n, signals)
            return
        }
        if (!window.active(SystemClock.elapsedRealtime())) return
        val path = Path().apply { moveTo((n.box.left + n.box.right) / 2f, (n.box.top + n.box.bottom) / 2f) }
        val current = generation
        gesturePending = true
        val dispatched = dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, 60L)).build(),
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = guarded {
                    if (generation == current) {
                        gesturePending = false
                        if (window.active(SystemClock.elapsedRealtime())) accepted(pkg, n, "目标节点中心手势执行完成", signals)
                    }
                }
                override fun onCancelled(gestureDescription: GestureDescription?) = guarded {
                    if (generation == current) {
                        gesturePending = false
                        logCandidate(pkg, "点击失败", "系统取消了手势", n, signals)
                    }
                }
            }, handler)
        if (!dispatched) {
            gesturePending = false
            logCandidate(pkg, "点击失败", "系统拒绝派发手势；更新后请重新开关无障碍服务", n, signals)
        }
    }

    private fun isBrowserOrStore(pkg: String) = listOf("chrome", "browser", "vending", "market", "firefox", "opera", "brave", "sbrowser", "ucbrowser", "edge")
        .any { pkg.lowercase(Locale.ROOT).contains(it) }

    private fun disconnected(reason: String) {
        isConnected = false
        resetWindow()
        guarded { RecognitionLog.add(this, packageName, "服务", reason) }
    }
    override fun onUnbind(intent: Intent?): Boolean { disconnected("服务已解绑"); return super.onUnbind(intent) }
    override fun onDestroy() { unregisterReceiver(screenOff); disconnected("服务已销毁"); super.onDestroy() }
    override fun onInterrupt() = guarded { RecognitionLog.add(this, packageName, "服务", "收到系统中断通知；不等于服务已解绑") }
}
