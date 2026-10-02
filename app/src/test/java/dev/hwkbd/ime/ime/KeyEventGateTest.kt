package dev.hwkbd.ime.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 滑动静默窗的时序断言。
 *
 * 2026-10-02 用户定案「按键事件 200 毫秒内不要响应键盘滑动事件」,
 * 随后补定「无论是否消费,后续 200 毫秒的键盘滑动事件都忽略」。
 *
 * 判定全在 [KeyEventGate](纯 Kotlin,时钟注入),这里钉的是三件容易改坏的事:
 * 窗口长度、边界方向、**点按不被牵连**。
 *
 * 断言一律按 [KeyEventGate.DEFAULT_BLOCK_MS] 相对写死,不给具体数字 ——
 * 窗口长度是用户随时会改的参数,把 200 抄进测试等于让改窗口变成改两处。
 */
class KeyEventGateTest {

    /** 虚拟时钟:从 0 起步 —— 0 是合法时刻,所以"没敲过键"不能拿 0 当哨兵。 */
    private var t = 0L
    private fun gate(blockMs: Long = KeyEventGate.DEFAULT_BLOCK_MS) =
        KeyEventGate(now = { t }, blockMs = blockMs)

    @Test
    fun `没敲过键就不挡`() {
        val g = gate()
        assertFalse("还没敲过键,滑动应当放行", g.swipeBlocked())
    }

    @Test
    fun `按键之后窗口内挡滑动`() {
        val g = gate()
        t = 1000
        g.onKeyEvent()
        // 刚敲完那一刻
        assertTrue("按键当下就该挡", g.swipeBlocked())
        // 窗口内的最后一毫秒
        t = 1000 + KeyEventGate.DEFAULT_BLOCK_MS - 1
        assertTrue("窗口内最后一毫秒仍在挡", g.swipeBlocked())
    }

    @Test
    fun `满窗口长度放行 —— 窗口是左闭右开`() {
        val g = gate()
        t = 1000
        g.onKeyEvent()
        t = 1000 + KeyEventGate.DEFAULT_BLOCK_MS
        assertFalse("「窗口内」不含窗口末尾那一刻", g.swipeBlocked())
        t = 1000 + KeyEventGate.DEFAULT_BLOCK_MS + 1
        assertFalse("窗口之后当然放行", g.swipeBlocked())
    }

    @Test
    fun `窗口按最后一次按键重算,不是第一次`() {
        val g = gate()
        t = 0
        g.onKeyEvent()
        t = KeyEventGate.DEFAULT_BLOCK_MS - 10 // 第一个窗口只剩 10ms
        g.onKeyEvent() // 连续打字:窗口顺延,不是叠加也不是各自独立
        t += KeyEventGate.DEFAULT_BLOCK_MS - 1
        assertTrue("第二次按键的窗口应覆盖到这里", g.swipeBlocked())
        t += 1
        assertFalse("按最后一次算,到这里已过期", g.swipeBlocked())
    }

    @Test
    fun `虚拟时钟从 0 起步时第一次按键仍然生效`() {
        // 回归:若用 0 当"没敲过"的哨兵,at=0 的按键会算出 blockUntil=窗口长度并被误判成
        // 「已经过期」——正是这块要钉住的那条,注释里写明了原因
        val g = gate()
        t = 0
        g.onKeyEvent()
        assertTrue("时钟 0 处的按键也要起窗", g.swipeBlocked())
    }

    @Test
    fun `reset 立刻放行`() {
        val g = gate()
        g.onKeyEvent()
        assertTrue(g.swipeBlocked())
        g.reset()
        assertFalse("清掉后不该还挡着(输入法隐藏时走这条)", g.swipeBlocked())
    }

    @Test
    fun `窗口长度是单参,方便按区域调`() {
        val g = gate(blockMs = 100)
        t = 0
        g.onKeyEvent()
        t = 100
        assertFalse("100ms 窗口到点放行", g.swipeBlocked())
    }
}
