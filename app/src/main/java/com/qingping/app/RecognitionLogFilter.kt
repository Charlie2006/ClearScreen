package com.qingping.app

/** Candidate decisions and their click results; background diagnostics remain recorded. */
object RecognitionLogFilter {
    private val related = setOf("匹配", "拒绝", "点击失败", "操作已接受", "按钮仍在", "按钮已消失", "结果未确认")
    fun matches(kind: String, onlyRelated: Boolean, category: Int): Boolean {
        if (onlyRelated && kind !in related) return false
        return when (category) {
            1 -> kind == "拒绝"
            2 -> kind in setOf("点击失败", "读取失败", "无法读取", "结果未确认", "未发现候选", "按钮仍在")
            3 -> kind in setOf("匹配", "操作已接受", "按钮仍在", "按钮已消失", "自动返回")
            4 -> kind in setOf("应用识别", "服务", "窗口切换", "跳过页面", "冷却")
            else -> true
        }
    }
}
