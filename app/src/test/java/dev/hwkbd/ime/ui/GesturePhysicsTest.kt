package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 手势物理的钉桩:跟手位移、惯性落点、匀减速时长。
 *
 * 2026-09-30 真机报「翻页不跟手」「翻页动画太短」修。这些断言就是为了把当时的根因
 * (画面位移被放大、死区静默、松手零动画)永久钉死。
 *
 * 行宽取 Titan 2 真机候选行:面板 1440×1440、逻辑宽 576px ⇒ density 2.5,
 * 候选行通栏(no 左右外边距,见 ime_root.xml),故 1440px。
 */
class GesturePhysicsTest {

    private val viewW = 1440f
    private val threshold = viewW * PAGE_COMMIT_FRAC // ≈449px

    private fun slide(dxLeft: Float) =
        pageDragSlide(x = 1000f - dxLeft, baseX = 1000f, dir = 1f, viewW = viewW)

    private fun travel(dxLeft: Float) =
        pageDragTravel(x = 1000f - dxLeft, baseX = 1000f, dir = 1f)

    // ---------------------------------------------------------------- 跟手

    @Test
    fun `one to one - the screen moves exactly as far as the finger`() {
        // 这是核心断言:手指走 200px,画面就走 200px。此前是"进度 × 行宽",放大 3 倍。
        assertEquals(200f, slide(200f), 0.01f)
        assertEquals(600f, slide(600f), 0.01f)
        assertEquals(1000f, slide(1000f), 0.01f)
    }

    @Test
    fun `dragging the full row width does not exceed the page`() {
        assertEquals(viewW, slide(viewW * 2f), 0.01f)
    }

    @Test
    fun `dead zone still responds - no silent stretch while the finger moves`() {
        // 死区里画面也要动(按 DEAD_SLIP 打折)。此前死区内完全零变化,
        // 那段"手指在动、屏幕不动"正是"不跟手"的直接观感。
        val dead = DragGesture.dead(threshold)
        val s = slide(dead * 0.5f)
        assertTrue("死区内也应有最小响应,实际 $s", s > 0f)
        assertEquals("死区内按 DEAD_SLIP 打折", dead * 0.5f * DragGesture.DEAD_SLIP, s, 0.01f)
    }

    @Test
    fun `dragging back pulls the screen back`() {
        assertTrue("往回拖,画面跟着退", slide(200f) < slide(600f))
        assertEquals(0f, slide(0f), 0.01f)
    }

    @Test
    fun `opposite direction yields no progress`() {
        // dir=+1(下一页)时往右拖:不认成同一次翻页(方向由 beginPageDrag 定)
        assertEquals(0f, pageDragSlide(1300f, 1000f, 1f, viewW), 0.01f)
        assertEquals(0f, pageDragSlide(700f, 1000f, -1f, viewW), 0.01f)
    }

    // ---------------------------------------------------------------- 惯性

    @Test
    fun `a slow release does not coast`() {
        // 速度不足 → 落点就是当前位置,不做惯性补完
        assertEquals(300f, Fling.projectLanding(300f, viewW, v0 = 100f), 0.01f)
    }

    @Test
    fun `a fast release coasts forward but never past the target`() {
        // 快甩:v=3000px/s ⇒ 自由滑行 375px,但夹在目标内
        val landing = Fling.projectLanding(200f, viewW, v0 = 3000f)
        assertEquals(575f, landing, 1f)
        assertTrue("不能越过目标", landing <= viewW)
    }

    @Test
    fun `a fast release toward the origin coasts back and stops at the origin`() {
        // 往回甩:v=3000px/s ⇒ 自由滑行 375px,从 400 起滑 → 落 25,
        // 还没到 0 就被"不越过目标"夹住。**不夹的话会滑过头**(越过原页继续往回,像失控)
        val landing = Fling.projectLanding(400f, 0f, v0 = -3000f)
        assertEquals("滑行 375px 后应落在 25", 25f, landing, 1f)
        assertTrue("应往原页方向滑", landing < 400f)
        assertTrue("不越过目标", landing >= 0f)
    }

    @Test
    fun `a very fast release is still clamped at the target`() {
        // 甩到离谱的速度也不能滑过目标 —— 越界会画到不存在的第三页
        val landing = Fling.projectLanding(100f, viewW, v0 = 100_000f)
        assertEquals("夹在目标", viewW, landing, 0.01f)
    }

    @Test
    fun `velocity in the wrong direction is ignored`() {
        // 速度方向朝着目标才叫惯性,反着的不算
        assertEquals(300f, Fling.projectLanding(300f, viewW, v0 = -3000f), 0.01f)
    }

    @Test
    fun `faster fling means longer settle`() {
        assertTrue(
            "快甩的补完动画应比轻推更长",
            Fling.stopDurationMs(4000f) > Fling.stopDurationMs(800f),
        )
    }

    @Test
    fun `settle duration stays inside bounds`() {
        assertEquals(Fling.MIN_MS, Fling.stopDurationMs(1f))
        assertEquals(Fling.MAX_MS, Fling.stopDurationMs(1_000_000f))
    }

    @Test
    fun `deceleration physics is self consistent`() {
        // 匀减速模型:t = v/a,s = v²/2a,且 s/t = v/2(平均速度)
        val v = 3000f
        val t = Fling.stopDurationMs(v) / 1000f
        val s = Fling.stopDistancePx(v)
        assertEquals("停止时长 v/a", v / Fling.DECEL, t, 1e-3f)
        assertEquals("总位移 v²/2a", v * v / (2f * Fling.DECEL), s, 1e-3f)
        assertEquals("平均速度 v/2", v / 2f, s / t, 1f)
    }

    @Test
    fun `fling curve equals constant deceleration`() {
        // 归一化位移 2p − p² = DecelerateInterpolator 的曲线。
        // 抽样几个点,确认物理模型和实际用的插值器一致(否则"按物理算的时长"配错曲线)。
        val samples = floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f)
        for (p in samples) {
            val physical = 2f * p - p * p
            val interp = Fling.DECELERATE.getInterpolation(p)
            assertEquals("p=$p 处物理曲线应与插值器一致", physical, interp, 1e-3f)
        }
    }

    // ---------------------------------------------------------------- 速度跟踪

    @Test
    fun `speed tracker measures recent velocity`() {
        val t = SpeedTracker()
        // 100ms 内走了 300px ⇒ 3000px/s
        for (i in 0..5) t.add(1000L + i * 20L, i * 60f)
        assertEquals(3000f, t.velocity(), 1f)
    }

    @Test
    fun `speed tracker reads the instant velocity, not the average`() {
        // 快甩一段后手指停住再抬手:速度必须归零。
        // 若按窗口平均,这段的快速度会被带进来,松手时页面自己又滑出去半屏 ——
        // 这就是"手停了它还在动"。只看最近两点才能脱钩。
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1020L, 600f) // 前段极快:30 px/ms
        t.add(1040L, 601f)
        t.add(1060L, 602f) // 停住:每 20ms 挪 1px,远小于 2px 判据
        assertEquals("停住后速度应为 0", 0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `speed tracker reports a real fling`() {
        // 匀速快划:每 16ms 挪 48px ⇒ 3000px/s,读数应贴近真值
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1016L, 48f)
        assertEquals(3000f, t.velocity(), 1f)
    }

    @Test
    fun `speed tracker needs at least two samples`() {
        val t = SpeedTracker()
        assertEquals(0f, t.velocity(), 1e-6f)
        t.add(1000L, 0f)
        assertEquals("只有一个样本算不出速度", 0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `speed tracker rejects zero elapsed time`() {
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1000L, 500f) // 同时间戳
        assertEquals(0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `speed tracker rejects overly dense samples`() {
        // 4ms 间隔的样本差分噪声太大,不给速度
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1004L, 3f)
        assertEquals(0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `speed tracker reset clears the previous gesture`() {
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1016L, 48f)
        assertTrue(t.velocity() > 0f)
        t.reset()
        assertEquals("reset 后不应残留速度", 0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `reading velocity twice returns zero the second time`() {
        // 松手只有那一瞬的速度。第二次读必须给 0:否则读到的是"抬手之后过了多久"的
        // 平均速度,越晚读越慢,动画时长会跟着抖。
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1016L, 48f)
        assertEquals("第一次读 3000", 3000f, t.velocity(), 1f)
        assertEquals("第二次读 0", 0f, t.velocity(), 1e-6f)
    }

    @Test
    fun `reading velocity does not consume the samples`() {
        // 作废的是"速度",不是"样本" —— 再喂一个点应能重新算出新速度
        val t = SpeedTracker()
        t.add(1000L, 0f)
        t.add(1016L, 48f)
        t.velocity()
        t.add(1032L, 96f)
        assertEquals("新样本应给出新速度", 3000f, t.velocity(), 1f)
    }
}
