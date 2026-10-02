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
 * 行宽取 Titan 2 真机候选行:面板 1440×1440,候选行**通栏**(两端只露底色不缩进,
 * 见 ime_root.xml)⇒ 1440px。px 是物理值,与用户改过最小逻辑宽度无关。
 */
class GesturePhysicsTest {

    private val viewW = 1440f
    private val threshold = viewW * PAGE_COMMIT_FRAC // ≈449px

    /**
     * 死区常量换算的 px(density 假设 2.0 ⇒ 32px)。**不再按阈值取比例**
     * (2026-10-02 用户「死区不使用比例计算 采用固定值」)。
     */
    private val dead = DragGesture.DEAD_DP * 2f

    private fun slide(dxLeft: Float) =
        pageDragSlide(
            x = 1000f - dxLeft, baseX = 1000f, dir = 1f, viewW = viewW,
            commitPx = threshold, dead = dead,
        )

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
        assertEquals(0f, pageDragSlide(1300f, 1000f, 1f, viewW, threshold, dead), 0.01f)
        assertEquals(0f, pageDragSlide(700f, 1000f, -1f, viewW, threshold, dead), 0.01f)
    }

    @Test
    fun `两条翻页路共用跟手与橡皮筋 但提交线各按各的量纲`() {
        // 键盘表面上左右划也是翻页(2026-10-02 修:此前它只在抬手时 pageStep 一下,
        // 整条拖动不画位移、到头什么都不发生 ⇒ 「弹性动画和跟手动画还是没有」)。
        // 现在它走候选栏同一套 begin/move/end,所以跟手与橡皮筋只有一份实现。
        //
        // **但提交线必须各按各的**:键盘面能划的距离小,按行宽 1/3(=475px)取的话
        // 轻轻一划根本翻不动 —— 所以那边用指尖位移档位(默认 60px)注入。
        val kbTotal = 60f // = FlyDetector.pageThresholdPx 默认档,口径是**手指总行程**

        // 设置页的档位换算到"从基线量":基线落在死区被跨过的那一帧,所以要减掉死区
        val kbFromBase = externalCommitFromBase(kbTotal, dead)

        fun progressAt(travelFromBase: Float, commitFromBase: Float) =
            pageDragProgress(1000f - travelFromBase, 1000f, 1f, viewW, commitFromBase, 0f)

        assertEquals(
            "键盘面上划满 ${kbTotal}px(总行程)就该翻页 —— 死区那截不能额外加算进去",
            1f, progressAt(kbFromBase, kbFromBase), 1e-3f,
        )
        assertTrue(
            "还没划满就不该翻",
            progressAt(kbFromBase - 1f, kbFromBase) < 1f,
        )
        assertTrue(
            "但同一段行程在候选行的提交线(行宽 1/3)下过不了 —— 这正是量纲必须分开的原因",
            progressAt(kbFromBase, threshold) < 1f,
        )
        // 跟手是两处共有的:位移 = 手指位移,与提交线无关
        assertEquals(200f, pageDragSlide(1000f - 200f, 1000f, 1f, viewW, kbFromBase, 0f), 0.01f)
        assertEquals(200f, pageDragSlide(1000f - 200f, 1000f, 1f, viewW, threshold, dead), 0.01f)
    }

    @Test
    fun `键盘表面的死区只算一次 不在基线之后再扣一遍`() {
        // 基线落在"死区被跨过的那一帧",所以那 32px 死在基线**之前**。
        // 从基线量的行程里若再传一次死区,progress 的分母 (threshold - dead)
        // 就是 28 - 32 = -4 ⇒ 进度 NaN。所以外部驱动时死区传 0。
        val kbTotal = 60f
        val fromBase = externalCommitFromBase(kbTotal, dead) // 28
        assertEquals(28f, fromBase, 1e-4f)

        // 死区 0、提交线 28:总行程 60px(=32 死区 + 28)恰好翻页
        assertEquals(1f, DragGesture.progress(fromBase, fromBase, 0f), 1e-3f)
        assertTrue("差 1px 就不翻", DragGesture.progress(fromBase - 1f, fromBase, 0f) < 1f)

        // 而候选行自己的拖动死区照常生效(基线就在 downX,死区没被提前吃掉)
        assertEquals(0f, DragGesture.progress(dead - 1f, threshold, dead), 1e-6f)
        assertTrue("过死区才有进度", DragGesture.progress(dead + 1f, threshold, dead) > 0f)
    }

    @Test
    fun `档位比死区还小时 夹到最小可用值而不是负数`() {
        // 死区 32px、档位只有 20px:换算是 20-32 = -12,直接用会让分母翻负号、
        // 进度恒 0(永远翻不了页)。夹到 1 ⇒ 总行程 = 33px,是这个配置下能有的最小值。
        val clamped = externalCommitFromBase(20f, dead)
        assertEquals(1f, clamped, 1e-4f)
        assertEquals("总行程 = 死区 + 1", dead + 1f, dead + clamped, 1e-4f)
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
