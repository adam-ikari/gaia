package dev.hwkbd.ime.ime

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键与滑动之间的门的时序断言。
 *
 * 2026-10-02 用户定案「按键事件 200 毫秒内不要响应键盘滑动事件」,
 * 随后补定「无论是否消费,后续 200 毫秒的键盘滑动事件都忽略」;
 * 2026-10-16 改成两段并集:「按键 down 的时候忽略手势,up 之后 300 毫秒以内也忽略」(见 [KeyEventGate])。
 *
 * 判定全在 [KeyEventGate](纯 Kotlin,时钟注入),这里钉的是容易改坏的那几件事:
 * 窗口长度、窗口从**抬手**起算、按住期间一直挡、自动重复不重复计数、漏抬兜底、**点按不被牵连**。
 *
 * 断言一律按 [KeyEventGate.DEFAULT_BLOCK_MS] / [KeyEventGate.HELD_STALE_MS] 相对写死,
 * 不给具体数字 —— 这两个是用户随时会改的参数,把 300 抄进测试等于让改窗口变成改两处。
 */
class KeyEventGateTest {

    private val keyA = KeyEvent.KEYCODE_A
    private val keyB = KeyEvent.KEYCODE_B

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

    // ---------------- 按住那段(2026-10-16 用户「按键 down 的时候忽略手势」)

    @Test
    fun `按住键期间一直挡 与窗口长度无关`() {
        val g = gate()
        t = 1000
        g.onKeyDown(keyA)
        // 早就超出"抬手后窗口"的长度,但键还按着 —— 挡的依据是"手在键上",不是那个窗口
        // (还在 HELD_STALE_MS 之内;安全阀那条见下面的漏抬用例)
        t = 1000 + KeyEventGate.DEFAULT_BLOCK_MS * 3
        assertTrue("键还按着,超出窗口长度也该继续挡", g.swipeBlocked())
    }

    @Test
    fun `窗口从抬手起算 不是从按下起算`() {
        val g = gate()
        t = 1000
        g.onKeyDown(keyA)
        t = 2000 // 按了整整 1s
        g.onKeyUp(keyA)
        t = 2000 + KeyEventGate.DEFAULT_BLOCK_MS - 1
        assertTrue("抬手后窗口内仍挡", g.swipeBlocked())
        t = 2000 + KeyEventGate.DEFAULT_BLOCK_MS
        assertFalse("抬手后窗口到点放行", g.swipeBlocked())
    }

    @Test
    fun `自动重复的按下不重复计数 一次抬手就解开`() {
        // 真机长按字母键会一路送 repeatCount>0 的 DOWN。若按个数记,一次 UP 只减一个,
        // 剩下的会让手势一直哑到安全阀 —— 那是"划不动了"级别的 bug。
        val g = gate()
        t = 1000
        g.onKeyDown(keyA)
        t = 1050
        g.onKeyDown(keyA) // 自动重复
        t = 1100
        g.onKeyDown(keyA) // 还在重复
        t = 1200
        g.onKeyUp(keyA)
        t = 1200 + KeyEventGate.DEFAULT_BLOCK_MS
        assertFalse("抬一次手就该解开,而不是哑到安全阀", g.swipeBlocked())
    }

    @Test
    fun `多键按住时 最后一个抬手才起窗`() {
        val g = gate()
        t = 1000
        g.onKeyDown(keyA)
        t = 1050
        g.onKeyDown(keyB)
        t = 1100
        g.onKeyUp(keyA)
        assertTrue("B 还按着,仍该挡", g.swipeBlocked())
        t = 1200
        g.onKeyUp(keyB)
        t = 1200 + KeyEventGate.DEFAULT_BLOCK_MS - 1
        assertTrue("最后一个抬手起窗", g.swipeBlocked())
        t += 1
        assertFalse("窗口到点放行", g.swipeBlocked())
    }

    @Test
    fun `漏掉抬手不会把滑动永久哑掉`() {
        val g = gate()
        t = 1000
        g.onKeyDown(keyA) // UP 丢了
        t = 1000 + KeyEventGate.HELD_STALE_MS
        assertTrue("安全阀之内仍按按住处理", g.swipeBlocked())
        t = 1000 + KeyEventGate.HELD_STALE_MS + 1
        assertFalse("超过安全阀就当那次抬手丢了", g.swipeBlocked())
    }

    @Test
    fun `安全阀由后续键事件重置`() {
        val g = gate()
        t = 1000
        g.onKeyDown(keyA) // UP 丢了
        t = 1000 + KeyEventGate.HELD_STALE_MS // 差 1ms 到阀值
        g.onKeyDown(keyB) // 用户还在打字,这就是"事件流没断"的证据
        t = 1000 + KeyEventGate.HELD_STALE_MS + 1
        assertTrue("仍在打字,不该当漏抬解开", g.swipeBlocked())
    }

    @Test
    fun `reset 也清掉按住态`() {
        val g = gate()
        t = 1000
        g.onKeyDown(keyA)
        assertTrue(g.swipeBlocked())
        g.reset()
        assertFalse("输入法收起时按住态也要跟着清(那次 UP 永远不会来)", g.swipeBlocked())
    }

    @Test
    fun `窗口关掉只关抬手后那段 按住那段仍在`() {
        val g = gate(blockMs = 0)
        t = 1000
        g.onKeyDown(keyA)
        assertTrue("按住期间与窗口长度无关", g.swipeBlocked())
        t = 1100
        g.onKeyUp(keyA)
        assertFalse("窗口长度 0 = 抬手即放行", g.swipeBlocked())
    }

    @Test
    fun `窗口长度可以运行时改`() {
        // 设置页改档位走这条:applyPrefs 直接写 blockMs,不重建门
        val g = gate()
        g.blockMs = 100
        t = 1000
        g.onKeyDown(keyA)
        g.onKeyUp(keyA)
        t = 1100
        assertFalse("改小后按新值算", g.swipeBlocked())
    }

    @Test
    fun `屏上键帽的点按也起窗 但不进按住态`() {
        // L4/L5 键帽走 commitL4Tapped → onKeyEvent:点按在抬手那一刻结算,所以没有"按住"这段
        val g = gate()
        t = 1000
        g.onKeyEvent()
        t = 1000 + KeyEventGate.DEFAULT_BLOCK_MS - 1
        assertTrue("点按后窗口内挡", g.swipeBlocked())
        t += 1
        assertFalse("窗口到点就放行,不用等抬手", g.swipeBlocked())
    }

    @Test
    fun `设置页默认档与判定实现的默认值同值`() {
        // 两处各写一份"300":实现里的 DEFAULT_BLOCK_MS 和设置页的 KEY_GATE_MS_DEFAULT
        // (arrays.xml 的默认项也写着 300)。改一处漏另一处 = 默认手感与显示不一致。
        assertTrue(
            "ImeService.KEY_GATE_MS_DEFAULT 应等于 KeyEventGate.DEFAULT_BLOCK_MS",
            dev.hwkbd.ime.service.ImeService.KEY_GATE_MS_DEFAULT.toLong() ==
                KeyEventGate.DEFAULT_BLOCK_MS,
        )
    }
}
