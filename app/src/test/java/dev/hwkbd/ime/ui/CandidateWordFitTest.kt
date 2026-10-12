package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选词截断判据的单测。
 *
 * 判据是纯函数,测量由**假字体**注入(每字固定宽),所以这里能精确卡住
 * 「裁几字、什么时候只剩省略号、什么时候空串」—— 这些边界真机上要换窄槽
 * 才看得见,而单测每次构建都跑(见 [CandidateWordFit] 的拆法理由)。
 */
class CandidateWordFitTest {

    /** 假字体:CJK 每字 100,拉丁 50,省略号 80。 */
    private val w = mapOf('…' to 80f)
    private fun measure(s: String): Float = s.sumOf { c -> (w[c] ?: if (c.code > 0x2E80) 100f else 50f).toDouble() }.toFloat()

    private fun fit(text: String, maxW: Float) = CandidateWordFit.fit(text, maxW, ::measure)

    @Test
    fun `fits - returned untouched`() {
        assertEquals("泥", fit("泥", 100f))
        assertEquals("泥妳", fit("泥妳", 200f))
        // 恰好等于 maxW 也算装得下(判据是 <=,不是 <)
        assertEquals("泥妳", fit("泥妳", 200.0f))
    }

    @Test
    fun `overflows - ellipsis appended and result fits`() {
        val s = fit("泥妳拟呢你妳拟", 450f)
        assertEquals("泥妳拟…", s)          // 3 字 300 + 省略号 80 = 380 ≤ 450;4 字就 480 > 450
        assertTrue("裁完必须放得进槽(实测 ${measure(s)} ≤ 450)", measure(s) <= 450f)
    }

    @Test
    fun `binary search agrees with naive scan`() {
        // 二分 vs 从头试:同一个答案。防「单调假设破了但二分先错」。
        // 朴素参照的预算必须**含省略号**:maxW=84 时 'a…' = 130 超宽,
        // 正确答案是只剩省略号(80 ≤ 84)。
        val text = "a泥b妳c拟d呢e你f妳g拟h呢i你"
        for (maxW in 0..1200 step 7) {
            val m = maxW.toFloat()
            val naive = when {
                measure(text) <= m -> text
                measure(CandidateWordFit.ELLIPSIS) > m -> ""
                else -> {
                    var k = 0
                    while (k <= text.length && measure(text.substring(0, k)) + measure(CandidateWordFit.ELLIPSIS) <= m) k++
                    val pre = text.substring(0, k - 1)
                    if (pre.isEmpty()) CandidateWordFit.ELLIPSIS else pre + CandidateWordFit.ELLIPSIS
                }
            }
            assertEquals("maxW=$maxW 二分与逐个试不一致", naive, fit(text, m))
        }
    }

    @Test
    fun `narrower than one ellipsis - empty not clipped garbage`() {
        // 连一个省略号(80)都放不下:返回空串,drawText("") 是 no-op,
        // 不会画出槽界(返回"…"会超 80 > maxW)。
        assertEquals("", fit("泥妳拟", 79f))
        assertEquals("…", fit("泥妳拟", 80f))
    }

    @Test
    fun `cjk and latin mix - prefix cuts at measured width not char count`() {
        // a泥b = 50+100+50 = 200;+省略号 80 = 280。maxW=280 恰好收 a泥b。
        assertEquals("a泥b…", fit("a泥b泥", 280f))
        assertEquals("a泥…", fit("a泥b泥", 279f))
    }
}
