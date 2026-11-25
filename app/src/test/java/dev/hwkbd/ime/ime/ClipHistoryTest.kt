package dev.hwkbd.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 剪贴板历史(纯逻辑)的断言(2026-10-16 用户「剪贴板功能:历史面板,复用 L5 浮层」)。
 *
 * 钉住四条:最新在前 / 相邻去重 / 上限淘汰最旧 / 敏感标记跟着条目走。
 * 面板的绘制与点选上屏靠真机验,本类只管**列表语义**。
 */
class ClipHistoryTest {

    @Test
    fun `最新在前`() {
        val h = ClipHistory()
        h.record("a")
        h.record("b")
        assertEquals(listOf("b", "a"), h.entries.map { it.text })
    }

    @Test
    fun `相邻重复不重复记`() {
        val h = ClipHistory()
        h.record("a")
        h.record("a")
        h.record("b")
        assertEquals(listOf("b", "a"), h.entries.map { it.text })
    }

    @Test
    fun `非相邻重复各记一条`() {
        val h = ClipHistory()
        h.record("a")
        h.record("b")
        h.record("a")
        assertEquals(listOf("a", "b", "a"), h.entries.map { it.text })
    }

    @Test
    fun `超过上限淘汰最旧的`() {
        val h = ClipHistory(capacity = 3)
        for (s in listOf("a", "b", "c", "d")) h.record(s)
        assertEquals(listOf("d", "c", "b"), h.entries.map { it.text })
    }

    @Test
    fun `空串不记`() {
        val h = ClipHistory()
        h.record("")
        assertTrue(h.entries.isEmpty())
    }

    @Test
    fun `sensitive 标记跟着条目走`() {
        val h = ClipHistory()
        h.record("pw", sensitive = true, knownSensitive = true)
        h.record("ok", knownSensitive = true)
        assertEquals(true, h.entries.first { it.text == "pw" }.sensitive)
        assertEquals(false, h.entries.first { it.text == "ok" }.sensitive)
        assertEquals(true, h.entries.first { it.text == "pw" }.knownSensitive)
        assertEquals(true, h.entries.first { it.text == "ok" }.knownSensitive)
    }

    @Test
    fun `无平台敏感标记时默认未确认`() {
        val h = ClipHistory()
        h.record("pw")
        assertEquals(false, h.entries.first().knownSensitive)
    }

    @Test
    fun `默认保留 10 条`() {
        val h = ClipHistory()
        for (i in 1..12) h.record("v$i")
        assertEquals(10, h.entries.size)
        assertEquals("v12", h.entries.first().text)
        assertEquals("v3", h.entries.last().text)
    }
}
