package com.fenghuolun.ktv

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 免责声明文本完整性测试（v1.2.7）。
 *
 * 风火轮 disclaimer 体系的核心纪律：风险自负 / 无担保 / 第三方责任条款禁止删减。
 * 这里用关键词钉死四类条款 + 行车安全提醒，防止后人精简文本时把法律要素删掉。
 */
class DisclaimerTextTest {

    @Test
    fun disclaimer_containsCoreClauses() {
        val text = DisclaimerText.DIALOG_TEXT
        assertTrue("缺少版权条款", text.contains("版权"))
        assertTrue("缺少风险自负条款", text.contains("风险"))
        assertTrue("缺少无担保(AS IS)条款", text.contains("现状"))
        assertTrue("缺少权限用途说明", text.contains("权限"))
        assertTrue("缺少行车安全提醒", text.contains("驾驶"))
        assertTrue("缺少评分参考性说明", text.contains("评分"))
    }

    @Test
    fun disclaimer_uiTexts_notBlank() {
        assertTrue(DisclaimerText.DIALOG_TITLE.isNotBlank())
        assertTrue(DisclaimerText.CHECKBOX_TEXT.isNotBlank())
        assertTrue(DisclaimerText.HOME_FOOTER.isNotBlank())
    }

    @Test
    fun disclaimer_checkboxRequiresFullRead() {
        assertTrue(DisclaimerText.CHECKBOX_TEXT.contains("同意"))
    }
}
