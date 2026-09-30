package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 越界橡皮筋(拉伸变形)的数学断言。
 *
 * 2026-09-30 用户「翻页到翻到头要有拉伸变形动画」:此前首/末页往回拖**整条拖动都不接管**,
 * 手指在动、屏幕纹丝不动 —— 那不是"到头了"的反馈,是"卡了"。
 *
 * 公式在主源码的 [Rubber](纯函数)里,这里只钉它的行为。视图里的私有计算测不到,
 * 所以阻尼必须抽成纯函数才有单测 —— 这条约束本身就是这个文件存在的理由。
 */
class RubberBandTest {

    private val scale = 120f  // 阻尼长度,相当于 dip(50)
    private val limit = 134f  // 封顶 dip(56)

    @Test
    fun `no overshoot means no shift`() {
        assertEquals(0f, Rubber.shift(0f, scale, limit), 1e-6f)
        assertEquals("负位移(往回拖)不计", 0f, Rubber.shift(-50f, scale, limit), 1e-6f)
    }

    @Test
    fun `shift is damped - always less than the finger moved`() {
        val raw = 300f
        val s = Rubber.shift(raw, scale, limit)
        assertTrue("阻尼后应小于手指位移:$s vs $raw", s < raw)
        // 阻尼是渐近的,拉得越远折扣越狠,所以下限只能给个很松的界:
        // raw=300、scale=120 时折扣 0.29,再拉就没有保证。
        assertTrue("但也不能小到没反馈", s > raw * 0.2f)
    }

    @Test
    fun `damping grows with distance - pulling further gets heavier`() {
        val first = Rubber.shift(100f, scale, limit) - Rubber.shift(0f, scale, limit)
        val later = Rubber.shift(600f, scale, limit) - Rubber.shift(500f, scale, limit)
        assertTrue("越拉越沉:$later 应小于 $first", later < first)
    }

    @Test
    fun `shift is capped so keys never leave the panel`() {
        // 拉到很远也不能超过封顶 —— 这是"动画完全在自己区域内"的保证。
        // 渐近线是 scale,所以 scale < limit 时封顶永远碰不到(该由渐近线兜底);
        // 这里把 limit 调到小于 scale,才能真正验到封顶这一层。
        val tight = 80f
        assertEquals(tight, Rubber.shift(100_000f, scale, tight), 1e-3f)
    }

    @Test
    fun `asymptote bounds the shift even without a tight cap`() {
        // scale=120 ⇒ 拉再远也趋近 120(测试里 limit=134 不会截断)
        assertTrue("应趋近 scale", Rubber.shift(100_000f, scale, limit) <= scale)
        assertTrue("且非常接近", Rubber.shift(100_000f, scale, limit) > scale * 0.95f)
    }

    @Test
    fun `squash stays small so slots remain readable`() {
        val q = Rubber.squash(Rubber.shift(100_000f, scale, limit), 1440f)
        assertTrue("形变量应克制:$q", q <= Rubber.MAX_SQUASH)
        assertTrue("也不该是 0(那就没有形变)", q > 0f)
    }

    @Test
    fun `squash of a zero-width view does not divide by zero`() {
        assertEquals(0f, Rubber.squash(50f, 0f), 1e-6f)
    }

    @Test
    fun `zero scale disables the band entirely`() {
        assertEquals(0f, Rubber.shift(300f, 0f, limit), 1e-6f)
    }
}
