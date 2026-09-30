package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选词变化入场动画的**错峰与幅度**断言(计算在主源码的 [SwapAnim] 里)。
 *
 * 2026-09-30 用户「候选词变化需要有动画」。视图里的动画靠 `ValueAnimator` 驱动,
 * JVM 单测跑不了,所以"哪几槽该动、按什么顺序、动多大"必须是纯函数才有单测。
 */
class SwapAnimTest {

    @Test
    fun `only slots whose word actually changed animate`() {
        val old = listOf("你", "泥沼", "呢好", null, null)
        val new = listOf("泥", "呢好", "你", null, null)
        // 三槽都变了。顺序按"离中心"排:中槽(2)第一,然后 1(dist=1),最后 0(dist=2)
        assertEquals(listOf(2, 1, 0), SwapAnim.changedSlots(old, new))
    }

    @Test
    fun `an unchanged slot does not animate`() {
        assertTrue(
            "内容没变不该有任何槽动",
            SwapAnim.changedSlots(listOf("你", "泥沼", "呢好"), listOf("你", "泥沼", "呢好")).isEmpty(),
        )
    }

    @Test
    fun `a slot appearing from empty does not count as a word change`() {
        // 从"没有候选"到"有候选"不是换词,是首次渲染 —— 逐槽播入场会像卡了一下
        val new = listOf("你", "泥沼", "呢好", null, null)
        assertTrue(SwapAnim.changedSlots(listOf<String?>(null, null, null, null, null), new).isEmpty())
    }

    @Test
    fun `a slot becoming empty does not animate either`() {
        val old = listOf("你", "泥沼", "呢好", null, null)
        assertTrue(SwapAnim.changedSlots(old, listOf<String?>(null, null, null, null, null)).isEmpty())
    }

    @Test
    fun `center slot goes first and outer slots wait`() {
        assertEquals("中槽不该等", 0L, SwapAnim.delayMs(2))
        assertEquals("相邻一格晚一格", 45L, SwapAnim.delayMs(1))
        assertEquals("远端晚两格", 90L, SwapAnim.delayMs(0))
    }

    @Test
    fun `total duration covers the last slot's delay plus its own run`() {
        assertEquals(200L + 90L, SwapAnim.totalMs(listOf(2, 0, 1)))
    }

    @Test
    fun `an outer slot stays invisible until its delay elapses`() {
        val total = SwapAnim.totalMs(listOf(2, 0))
        assertEquals(0f, SwapAnim.localProgress(0f, 2, total), 1e-6f)
        assertEquals(0f, SwapAnim.localProgress(0f, 0, total), 1e-6f)
        assertEquals("中槽应先到位", 1f, SwapAnim.localProgress(0.9f, 2, total), 1e-6f)
        assertTrue("远端此刻应仍在路上", SwapAnim.localProgress(0.9f, 0, total) < 1f)
        assertEquals("结束时全部到位", 1f, SwapAnim.localProgress(1f, 0, total), 1e-6f)
    }

    @Test
    fun `transform goes from lifted and small to settled`() {
        val (lift0, scale0) = SwapAnim.transform(0f)
        assertEquals("起始应在上方", SwapAnim.LIFT, lift0, 1e-6f)
        assertEquals("起始应偏小", SwapAnim.SCALE_FROM, scale0, 1e-6f)

        val (lift1, scale1) = SwapAnim.transform(1f)
        assertEquals("到位后不位移", 0f, lift1, 1e-6f)
        assertEquals("到位后原尺寸", 1f, scale1, 1e-6f)
    }

    @Test
    fun `amplitude stays small enough for a 48dp row`() {
        // 候选行 48dp ≈ 115px;上移 7px + 12% 缩放是"读得出在动但不出界"的量级
        assertTrue("上移量应 < 行高的 1/8", SwapAnim.LIFT < 115f / 8f)
        val (_, minScale) = SwapAnim.transform(0f)
        assertTrue("起始缩放不该小于 0.8(再小就看不出是同一个词了)", minScale >= 0.8f)
    }

    @Test
    fun `alpha starts dim but visible`() {
        assertTrue("起始不该全透明(那样像凭空出现)", SwapAnim.alpha(0f) > 0f)
        assertEquals(1f, SwapAnim.alpha(1f), 1e-6f)
    }
}
