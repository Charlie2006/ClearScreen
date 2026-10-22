package com.qingping.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.SystemClock
import android.content.Intent
import java.util.Locale

class AdGuardAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var isConnected: Boolean = false
            private set
    }

    private var lastActionPackage: String? = null
    private var lastActionAt: Long = 0L

    private var foregroundPackage: String? = null
    private var lastClickedPackage: String? = null
    private var lastClickAt: Long = 0L

    private data class NodeEntry(
        val node: AccessibilityNodeInfo,
        val label: String,
        val viewId: String
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        isConnected = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val pkg = event.packageName?.toString() ?: return
        // Never click controls or navigate back while the user grants permissions.
        if (pkg == packageName || pkg == "com.android.settings" ||
            pkg == "com.android.systemui" || pkg == "com.huawei.systemmanager" ||
            pkg == "com.android.permissioncontroller" ||
            pkg == "com.google.android.permissioncontroller") {
            foregroundPackage = null
            lastClickedPackage = null
            lastClickAt = 0L
            return
        }

        val now = SystemClock.elapsedRealtime()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) {
            lastClickedPackage = pkg
            lastClickAt = now
        }

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {

            val previous = foregroundPackage
            if (previous == null) {
                foregroundPackage = pkg
            } else if (previous != pkg) {
                val clickJustBeforeTransition =
                    lastClickedPackage == previous &&
                    now - lastClickAt in 0L..1600L

                if (StatsStore.autoBackEnabled(this) &&
                    clickJustBeforeTransition &&
                    isLikelyBrowserOrStore(pkg)) {

                    // 这是“跳转后返回”，并非真正拦截启动请求。
                    // 用户主动点击外链也可能触发，所以功能默认关闭。
                    foregroundPackage = pkg
                    lastClickedPackage = null
                    lastClickAt = 0L

                    if (performGlobalAction(GLOBAL_ACTION_BACK)) {
                        StatsStore.record(this, previous)
                        lastActionPackage = previous
                        lastActionAt = now
                    }
                    return
                }

                foregroundPackage = pkg
            }
        }

        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                if (lastActionPackage == pkg && now - lastActionAt < 1800L) return

                val root = rootInActiveWindow ?: return
                // Ignore stale events after a switch to another app or Settings.
                if (root.packageName?.toString() != pkg) return
                try {
                    tryCloseClearlyMarkedAd(root, pkg, now)
                } catch (_: Exception) {
                    // 某些应用会在读取期间销毁窗口；忽略本次事件，不影响服务。
                }
            }
        }
    }

    private fun tryCloseClearlyMarkedAd(
        root: AccessibilityNodeInfo,
        packageName: String,
        now: Long
    ) {
        val entries = ArrayList<NodeEntry>()
        val budget = intArrayOf(0)
        collectNodes(root, entries, budget)

        if (entries.isEmpty()) return

        val screenText = entries.joinToString(" ") { it.label }.lowercase(Locale.ROOT)
        val hasAdContext =
            screenText.contains("广告") ||
            screenText.contains("推广") ||
            screenText.contains("sponsored") ||
            screenText.contains("advertisement") ||
            Regex("\\bad\\b", RegexOption.IGNORE_CASE).containsMatchIn(screenText)

        for (entry in entries) {
            val labels = entry.label.split(" || ")
                .map { it.trim().lowercase(Locale.ROOT) }
            val id = entry.viewId.lowercase(Locale.ROOT)

            val explicitAdClose = labels.any {
                it in setOf("跳过广告", "关闭广告", "skip ad", "close ad", "dismiss ad")
            }

            val genericClose = labels.any {
                it in setOf("跳过", "关闭", "skip", "close", "dismiss", "×", "✕")
            }

            val closeLikeId =
                (id.contains("close") || id.contains("skip") || id.contains("dismiss")) &&
                (id.contains("ad") || hasAdContext)

            if (!(explicitAdClose || (hasAdContext && genericClose) || closeLikeId)) {
                continue
            }

            if (performClickOnNodeOrClickableParent(entry.node)) {
                StatsStore.record(this, packageName)
                lastActionPackage = packageName
                lastActionAt = now
                return
            }
        }
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo,
        output: MutableList<NodeEntry>,
        budget: IntArray
    ) {
        if (budget[0] >= 350) return
        budget[0]++

        val text = node.text?.toString().orEmpty()
        val description = node.contentDescription?.toString().orEmpty()
        val label = listOf(text, description)
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString(" || ")

        output.add(
            NodeEntry(
                node = node,
                label = label,
                viewId = node.viewIdResourceName.orEmpty()
            )
        )

        for (i in 0 until node.childCount) {
            if (budget[0] >= 350) break
            val child = node.getChild(i) ?: continue
            collectNodes(child, output, budget)
        }
    }

    private fun performClickOnNodeOrClickableParent(node: AccessibilityNodeInfo): Boolean {
        var candidate: AccessibilityNodeInfo? = node
        repeat(4) {
            val current = candidate ?: return false
            if (current.isClickable &&
                current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            candidate = current.parent
        }
        return false
    }

    private fun isLikelyBrowserOrStore(pkg: String): Boolean {
        val p = pkg.lowercase(Locale.ROOT)
        val hints = listOf(
            "chrome", "browser", "vending", "market", "firefox",
            "opera", "brave", "sbrowser", "ucbrowser", "edge"
        )
        return hints.any { p.contains(it) }
    }

    override fun onUnbind(intent: Intent?): Boolean {
        isConnected = false
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        isConnected = false
        super.onDestroy()
    }

    override fun onInterrupt() {
        // Android 通知服务中断时不需要执行额外操作。
    }
}
