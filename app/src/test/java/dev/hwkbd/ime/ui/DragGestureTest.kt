package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 三处拖动手势的**共用换算**:候选词翻页、L5 翻页、上滑飞字
 * (2026-09-29 用户定案「手感对齐,都支持死区、跟手动画和触发阈值」)。
 *
 * 契约只有四条,这里逐条钉住:
 * 1. 死区内进度恒 0(抖动不启动,视觉零变化);
 * 2. 过死区后与手指 1:1(**跟手**,不是抬手才跳);
 * 3. 走满阈值进度恰为 1(触发线);
 * 4. 松手补完动画时长按**剩余距离**缩放(动画速度不随松手早晚跳变)。
 */
class DragGestureTest {

    @Test
    fun `dead zone swallows the first quarter of the threshold`() {
        val t = 300f
        assertEquals("死区 = 阈值的 1/4", 75f, DragGesture.dead(t), 1e-4f)
        assertEquals(0f, DragGesture.progress(0f, t), 1e-6f)
        assertEquals(0f, DragGesture.progress(40f, t), 1e-6f)
        assertEquals(0f, DragGesture.progress(75f, t), 1e-6f) // 恰好在死区边界上
    }

    @Test
    fun `progress follows the finger 1 to 1 past the dead zone`() {
        val t = 300f
        val span = t - DragGesture.dead(t)
        val p0 = DragGesture.progress(100f, t)
        val p1 = DragGesture.progress(100f + 120f, t)
        assertEquals("位移 1:1 变成进度 1:1", 120f / span, p1 - p0, 1e-4f)
    }

    @Test
    fun `progress is monotonic and clamps`() {
        val t = 300f
        var prev = -1f
        for (travel in 0..600 step 5) {
            val p = DragGesture.progress(travel.toFloat(), t)
            assertTrue("单调不减 (travel=$travel)", p >= prev - 1e-6f)
            prev = p
        }
        assertEquals("走满阈值 = 1", 1f, DragGesture.progress(t, t), 1e-6f)
        assertEquals("超出阈值不越界", 1f, DragGesture.progress(t * 3f, t), 1e-6f)
    }

    @Test
    fun `settle duration scales with remaining distance so speed never jumps`() {
        val near = DragGesture.settleDuration(0.9f)
        val far = DragGesture.settleDuration(0.1f)
        assertTrue("越接近目标补完越快", far > near)
        assertTrue("下限 60ms", near >= 60L)
        assertTrue("上限 260ms", far <= 260L)
    }

    @Test
    fun `settle duration is proportional to remaining distance between the clamps`() {
        // 取两端都不撞夹取(60..260ms)的两组值,验证"同一速度"这个契约本身:
        // 剩余距离翻倍 ⇒ 时长也翻倍。撞上夹取的那段另有 [clamped ends] 那条测。
        val d1 = DragGesture.settleDuration(0.7f) // 0.3 * 260 = 78ms
        val d2 = DragGesture.settleDuration(0.4f) // 0.6 * 260 = 156ms
        assertEquals("剩余距离翻倍 ⇒ 时长翻倍", 2.0f, d2.toFloat() / d1.toFloat(), 0.02f)
    }

    @Test
    fun `settle duration is clamped at both ends so the tail never flashes`() {
        // 快到终点:线性算出来只有 26ms,夹到 60ms,免得一闪而过看不清发生了什么
        assertEquals(60L, DragGesture.settleDuration(0.9f))
        assertEquals(260L, DragGesture.settleDuration(0.0f))
    }

    @Test
    fun `a zero threshold still yields a usable 0 to 1 range`() {
        assertEquals(0f, DragGesture.progress(0f, 0f), 1e-6f)
        assertEquals(1f, DragGesture.progress(1f, 0f), 1e-6f)
    }
}
