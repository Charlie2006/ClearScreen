package com.qingping.app

import org.junit.Assert.*
import org.junit.Test

class RecognitionLogFilterTest {
    private val stream = listOf("服务", "应用识别", "未发现候选", "拒绝", "冷却", "匹配", "操作已接受", "按钮仍在", "按钮已消失", "结果未确认", "点击失败")
    @Test fun focusedViewRetainsRejectionsAndCompleteActionHistory() {
        val visible = stream.filter { RecognitionLogFilter.matches(it, true, 0) }
        assertEquals(listOf("拒绝", "匹配", "操作已接受", "按钮仍在", "按钮已消失", "结果未确认", "点击失败"), visible)
    }
    @Test fun turningFocusOffRestoresBackgroundDiagnostics() {
        assertEquals(stream, stream.filter { RecognitionLogFilter.matches(it, false, 0) })
    }
    @Test fun failureCategoryStillHonorsFocusSwitch() {
        assertFalse(RecognitionLogFilter.matches("未发现候选", true, 2))
        assertTrue(RecognitionLogFilter.matches("未发现候选", false, 2))
        assertTrue(RecognitionLogFilter.matches("点击失败", true, 2))
        assertFalse(RecognitionLogFilter.matches("匹配", true, 2))
    }
}
