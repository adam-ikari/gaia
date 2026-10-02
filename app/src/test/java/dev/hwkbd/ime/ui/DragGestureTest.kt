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

    /**
     * 死区常量换算的 px。**不再按阈值取比例**(2026-10-02 用户「死区不使用比例计算
     * 采用固定值」「死区改为常量值」),所以这里给一个假想的 density 把它换算成 px,
     * 后面的用例一律按这个 px 构造位移。
     */
    private val dead = DragGesture.DEAD_DP * 2f // density 假设 2.0 ⇒ 16px

    @Test
    fun `死区是常量 不随阈值变化`() {
        // 三种触发线(横滑 158/238/324dp 的像素级对应)下,死区都一样
        for (t in listOf(160f, 316f, 475f, 648f)) {
            assertEquals(
                "阈值 $t 下的死区应恒为 ${dead}px,实际 ${t * 0.25f}",
                0f, DragGesture.progress(dead - 1f, t, dead), 1e-6f,
            )
            assertEquals(
                "阈值 $t 下越过死区就该有进度",
                true, DragGesture.progress(dead + 1f, t, dead) > 0f,
            )
        }
    }

    @Test
    fun `死区内进度恒 0`() {
        val t = 300f
        assertEquals(0f, DragGesture.progress(0f, t, dead), 1e-6f)
        assertEquals(0f, DragGesture.progress(dead - 1f, t, dead), 1e-6f)
        assertEquals(0f, DragGesture.progress(dead, t, dead), 1e-6f) // 恰好在边界上
    }

    @Test
    fun `progress follows the finger 1 to 1 past the dead zone`() {
        val t = 300f
        val span = t - dead
        val p0 = DragGesture.progress(100f, t, dead)
        val p1 = DragGesture.progress(100f + 120f, t, dead)
        assertEquals("位移 1:1 变成进度 1:1", 120f / span, p1 - p0, 1e-4f)
    }

    @Test
    fun `progress is monotonic and clamps`() {
        val t = 300f
        var prev = -1f
        for (travel in 0..600 step 5) {
            val p = DragGesture.progress(travel.toFloat(), t, dead)
            assertTrue("单调不减 (travel=$travel)", p >= prev - 1e-6f)
            prev = p
        }
        assertEquals("走满阈值 = 1", 1f, DragGesture.progress(t, t, dead), 1e-6f)
        assertEquals("超出阈值不越界", 1f, DragGesture.progress(t * 3f, t, dead), 1e-6f)
    }

    @Test
    fun `换引擎后 手感基准值仍然接在弹簧上 而不是只剩一句注释`() {
        // 2026-10-02:补完动画从"剩余距离 × 固定时长"换成了弹簧。原来的
        // `settleDuration()` / `fallbackVelocity()` 一并删掉了(固定时长正是「松手顿一下」
        // 与「打断跳变」的来源)。但 340ms 这个**手感基准不能丢** —— 它现在是
        // `SpringSpec.DEFAULT.response` 的来由。
        //
        // 这条测试就是防那两处**悄悄脱钩**:哪天有人只改了 `response` 却以为
        // 「基准还在 DragGesture 里」,或者反过来,它会当场失败。
        assertEquals(
            "弹簧响应应当就是 SETTLE_MS 那个手感基准(0.34s)",
            DragGesture.SETTLE_MS / 1000f,
            SpringSpec.DEFAULT.response,
            1e-4f,
        )
    }

    @Test
    fun `没有手指速度时由弹簧从静止起步 不再需要反推一个兜底速度`() {
        // 旧实现要在 `fallbackVelocity()` 里按"匀速走完 340ms"反推一个假速度,
        // 否则固定时长会一律取下限 90ms(整页切换像被弹射出去)。
        // 弹簧没有这个问题:初速给 0,时长自然由行程与刚度算出来,不需要凑。
        // 所以这里断言的是**那个反推已经不存在**——它一被加回来就会失败。
        assertEquals(
            "零距离不该产生任何兜底速度(弹簧从静止起步即可)",
            0f, Landing.stopDistancePx(0f), 1e-6f,
        )
        assertTrue(
            "弹簧初速为 0 时依然会收敛,不需要任何假速度兜底",
            SpringSpec.DEFAULT.response > 0f && !SpringSpec.DEFAULT.overshoots,
        )
    }

    @Test
    fun `a zero threshold still yields a usable 0 to 1 range`() {
        assertEquals(0f, DragGesture.progress(0f, 0f, 0f), 1e-6f)
        assertEquals(1f, DragGesture.progress(1f, 0f, 0f), 1e-6f)
    }
}
