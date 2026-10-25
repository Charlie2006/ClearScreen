package com.qingping.app

/** Pure rules: no device access, actions, or copied third-party rules. */
object AdRules {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        override fun toString() = "[$left,$top,$right,$bottom]"
    }
    data class Node(
        val text: String, val description: String, val id: String,
        val box: Box, val visible: Boolean, val enabled: Boolean, val editable: Boolean
    )
    data class Screen(
        val box: Box, val ageMs: Long, val ad: Boolean,
        val motion: Boolean, val onboarding: Boolean, val nearbyTimer: Boolean = false,
        val startupSkip: Boolean = false
    )
    data class Decision(val candidate: Boolean, val click: Boolean, val reason: String)

    fun normalize(value: String): String = buildString {
        value.lowercase(java.util.Locale.ROOT).forEach { c ->
            when {
                c in '０'..'９' -> append('0' + (c - '０'))
                c.isWhitespace() || c in "\u200b\u200c\u200d\ufeff" -> Unit
                c in "（【" -> append('(')
                c in "）】" -> append(')')
                c == '｜' -> append('|')
                else -> append(c)
            }
        }
    }
    private val skip = Regex("^(?:跳过|skip)(?:[|:：·(]*[0-9]{1,2}(?:秒|s|秒后)?[)]*)?$|^(?:[(]*[0-9]{1,2}(?:秒|s|秒后)?[)|:：·]*)(?:跳过|skip)$")
    private val explicit = Regex("^(跳过广告|关闭广告|跳过视频广告|skipad|closead|dismissad)(?:[(|:：]*[0-9]{1,2}(?:秒|s)?[)]*)?$")
    private val timer = Regex("[0-9]{1,2}(?:秒|s)|[0-9]{1,2}.*(?:跳过|skip)|(?:跳过|skip).*?[0-9]{1,2}")
    private val adToken = Regex("(^|_)(ad|ads|advert|advertisement|splash)(_|$)|^(tt_splash|ksad_|gdt_|mbridge_|csj_)")
    private val strongAdToken = Regex("(^|_)(ad|ads|advert|advertisement)(_|$)|^(tt_splash|ksad_|gdt_|mbridge_|csj_)")
    private val skipToken = Regex("(^|_)skip(_|$)")
    private val actionToken = Regex("(^|_)(skip|close|dismiss)(_|$)")

    fun topRight(node: Node, screen: Box): Boolean {
        val b = node.box
        if (screen.width <= 0 || screen.height <= 0) return false
        return b.width > 0 && b.height > 0 && b.left >= screen.left && b.top >= screen.top &&
            b.right <= screen.right && b.bottom <= screen.bottom &&
            (b.left + b.width / 2 - screen.left) >= screen.width * 0.65 &&
            (b.top + b.height / 2 - screen.top) <= screen.height * 0.25 &&
            b.width <= screen.width * 0.45 && b.height <= screen.height * 0.16
    }

    fun decide(node: Node, screen: Screen): Decision {
        val labels = listOf(normalize(node.text), normalize(node.description))
        val id = node.id.substringAfterLast('/').replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").replace('-', '_').lowercase(java.util.Locale.ROOT)
        val skipText = labels.any { skip.matches(it) }
        val explicitText = labels.any { explicit.matches(it) }
        val genericClose = labels.any { it in setOf("关闭", "close", "dismiss", "×", "✕", "x") }
        val idAction = actionToken.containsMatchIn(id)
        val textlessSkipId = labels.all { it.isEmpty() } && skipToken.containsMatchIn(id)
        val adId = idAction && adToken.containsMatchIn(id)
        // Log loose matches too: a phrase like “跳过登录” must explain why it was rejected.
        val candidate = skipText || explicitText || genericClose || idAction ||
            labels.any { it.contains("跳过") || it.contains("skip") }
        if (!candidate) return Decision(false, false, "不是跳过/关闭候选")
        fun no(reason: String) = Decision(true, false, reason)
        if (screen.ageMs !in 0 until SplashWindow.DURATION_MS) return no("超过开屏 10 秒窗口，不执行点击")
        if (node.editable) return no("输入控件，不点击")
        if (!node.visible) return no("候选不可见，不点击")
        if (!node.enabled) return no("候选被禁用，等待下一次检查")
        if (node.box.width <= 0 || node.box.height <= 0) return no("候选没有有效位置")
        if (node.box.left < screen.box.left || node.box.top < screen.box.top ||
            node.box.right > screen.box.right || node.box.bottom > screen.box.bottom) return no("候选超出当前窗口")
        if (node.box.width > screen.box.width * 0.5 || node.box.height > screen.box.height * 0.22)
            return no("候选区域过大，可能是整页容器")
        val corner = topRight(node, screen.box)
        val startup = screen.ageMs in 0 until SplashWindow.DURATION_MS
        if (screen.startupSkip && startup && labels.any { it.contains("跳过") || skip.matches(it) })
            return Decision(true, true, "开屏直点模式：10 秒窗口内发现跳过文字，不限制右上角或广告线索")
        val timed = labels.any { timer.containsMatchIn(it) } || screen.nearbyTimer
        if (screen.onboarding && !explicitText && !(adId && strongAdToken.containsMatchIn(id)) && !screen.motion)
            return no("页面含登录/隐私/引导线索，缺少明确广告证据")
        if (explicitText) return Decision(true, true, "明确的广告跳过/关闭文字")
        if (adId) return Decision(true, true, "控件 ID 同时含广告/开屏标记和跳过/关闭标记")
        if ((skipText || textlessSkipId) && corner) {
            val evidence = when {
                screen.motion -> "摇一摇/扭一扭/滑动跳转提示"
                screen.ad -> "广告提示"
                timed -> "倒计时"
                startup -> "应用切换后 10 秒开屏窗口"
                else -> return no("发现右上角跳过，但已超过开屏窗口且无倒计时/广告线索")
            }
            return Decision(true, true, "右上角跳过 + $evidence")
        }
        if (skipText || textlessSkipId) return no("发现跳过，但不在右上角且无明确广告文字/ID")
        if (genericClose && corner && screen.ad && startup)
            return Decision(true, true, "开屏窗口内右上角关闭 + 广告提示")
        return no("候选文字/ID不够明确，缺少广告和位置组合证据")
    }
}
