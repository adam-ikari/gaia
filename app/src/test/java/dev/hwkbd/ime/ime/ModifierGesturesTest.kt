package dev.hwkbd.ime.ime

import dev.hwkbd.ime.ime.ModifierGestures.Mod
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 修饰键手势的时序单测。全部用 [runTest] 虚拟时间:时钟注入 [ModifierGestures.now],
 * 所以这些断言既确定又不慢(没有一处 `Thread.sleep`)。
 *
 * 这里只测**判定**。2026-09-30 起这里也有一条"敲击窗口开合"的观测(供状态行藏暂定态),
 * 连同 `ModifierGestures.settled` 事件流一起删了——状态行改成只吃状态,
 * 手势对象不必再知道谁在显示它(见 `StatusRow`)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModifierGesturesTest {

    // ---- 敲击计数 ---------------------------------------------------------------

    @Test
    fun `窗口内连击计数 窗口外重新从一开始`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        assertEquals(1, g.tap(Mod.ALT))
        advanceTimeBy(100)
        assertEquals(2, g.tap(Mod.ALT)) // 双击
        advanceTimeBy(100)
        assertEquals(3, g.tap(Mod.ALT)) // 连击
        // 窗口外:新的一次独立敲击
        advanceTimeBy(g.tapWindowMs + 1)
        assertEquals(1, g.tap(Mod.ALT))
    }

    @Test
    fun `三个键各数各的`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        assertEquals(1, g.tap(Mod.ALT))
        assertEquals(1, g.tap(Mod.SYM))
        assertEquals(1, g.tap(Mod.SHIFT))
        advanceTimeBy(50)
        assertEquals(2, g.tap(Mod.ALT)) // Alt 进了第二击
        assertEquals(1, g.peek(Mod.SYM)) // Sym/Shift 各自还停在第一击
        assertEquals(1, g.peek(Mod.SHIFT))
    }

    @Test
    fun `resetTaps 清掉这一键的连击`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        g.tap(Mod.ALT)
        advanceTimeBy(50)
        g.tap(Mod.ALT)
        assertEquals(2, g.peek(Mod.ALT))
        g.resetTaps(Mod.ALT)
        assertEquals(0, g.peek(Mod.ALT))
        assertEquals(1, g.tap(Mod.ALT))
    }

    // ---- 按住时长 ---------------------------------------------------------------

    @Test
    fun `按住不足阈值算快速敲击 超过就不算`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        g.down(Mod.ALT)
        advanceTimeBy(g.holdMs - 50)
        assertTrue("299ms 的按压是敲击", g.up(Mod.ALT))

        g.down(Mod.ALT)
        advanceTimeBy(g.holdMs + 50)
        assertFalse("超时是长按,松开即取消", g.up(Mod.ALT))
    }

    @Test
    fun `markLongPress 让这次按下立刻算长按`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        g.down(Mod.SYM)
        g.markLongPress(Mod.SYM) // 长按期间按过别的键 = 这次已被"用掉"
        assertFalse(g.up(Mod.SYM))
    }

    // ---- 解锁吸收窗 -------------------------------------------------------------

    @Test
    fun `吸收窗只挡紧接着的那一下`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        g.suppress(Mod.ALT)
        assertTrue("解锁后的连击要被吞", g.consumeSuppressed(Mod.ALT))
        assertFalse("只挡一下", g.consumeSuppressed(Mod.ALT))
    }

    @Test
    fun `吸收窗不挡别的键 过期后不挡`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        g.suppress(Mod.ALT)
        assertFalse("SYM 不受 Alt 的吸收窗影响", g.consumeSuppressed(Mod.SYM))
        advanceTimeBy(ModifierGestures.SUPPRESS_MS + 10)
        assertFalse("过期后不再吸收", g.consumeSuppressed(Mod.ALT))
    }

    // ---- 全清 -------------------------------------------------------------------

    @Test
    fun `resetAll 把所有计时清干净`() = runTest {
        val g = ModifierGestures(now = { testScheduler.currentTime })
        g.tap(Mod.ALT)
        g.tap(Mod.SHIFT)
        g.down(Mod.SYM)
        g.suppress(Mod.SYM)
        g.resetAll()
        assertEquals(0, g.peek(Mod.ALT))
        assertEquals(0, g.peek(Mod.SHIFT))
        assertFalse("长按标记也清了 ⇒ 下一次 up 判不出快速", g.up(Mod.SYM))
        assertFalse(g.consumeSuppressed(Mod.SYM))
        // 计数清了 ⇒ 下一次敲击从 1 开始
        assertEquals(1, g.tap(Mod.ALT))
    }
}
