package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.keymap.KeyLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeControllerTest {

    private class RecHost : ImeHost {
        val commits = mutableListOf<String>()
        val keys = mutableListOf<String>()

        override fun commitText(text: String) {
            commits += text
        }

        override fun sendKey(code: Int, ctrl: Boolean, shift: Boolean) {
            keys += "$code/$ctrl/$shift"
        }

        override fun render() {}
    }

    private val dictLines = listOf(
        "ni\t你\t500",
        "hao\t好\t400",
        "nihao\t你好\t300",
        "shijie\t世界\t250",
        "zhongguo\t中国\t600",
    )

    private class Rig(val c: ImeController, val h: RecHost) {
        fun type(s: String) {
            for (ch in s) c.onKeyDown(kc(ch), false, false)
        }

        fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false, caps: Boolean = false): Boolean =
            c.onKeyDown(code, ctrl, shift, caps)
    }

    private fun rig(pageSize: Int = 5): Rig {
        val dict = PinyinDict()
        dict.load(dictLines)
        val h = RecHost()
        val c = ImeController(h, KeyLayout.default(), PinyinEngine(dict, dev.hwkbd.ime.engine.UserDict()))
        c.state.pageSize = pageSize
        return Rig(c, h)
    }

    companion object {
        fun kc(ch: Char): Int = KeyEvent.KEYCODE_A + (ch - 'a')
    }

    // ---------------------------------------------------------------- 中文拼音

    @Test
    fun `中文输入字母进缓冲空格上屏首选`() {
        val r = rig()
        r.type("nihao")
        assertEquals("nihao", r.c.state.raw)
        assertTrue(r.key(KeyEvent.KEYCODE_SPACE))
        assertEquals(listOf("你好"), r.h.commits)
        assertEquals("", r.c.state.raw)
    }

    @Test
    fun `回车上屏原字母`() {
        val r = rig()
        r.type("nihao")
        r.key(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("nihao"), r.h.commits)
        assertEquals("", r.c.state.raw)
    }

    @Test
    fun `退格编辑缓冲空缓冲时透传`() {
        val r = rig()
        r.type("ni")
        assertTrue(r.key(KeyEvent.KEYCODE_DEL))
        assertEquals("n", r.c.state.raw)
        r.key(KeyEvent.KEYCODE_DEL)
        assertEquals("", r.c.state.raw)
        assertTrue(r.key(KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("${KeyEvent.KEYCODE_DEL}/false/false"), r.h.keys)
    }

    @Test
    fun `空缓冲时空格上屏半角空格`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf(" "), r.h.commits)
    }

    @Test
    fun `无候选时空格上屏原字母`() {
        val r = rig()
        r.type("zzz")
        assertTrue(r.c.state.candidates.isEmpty())
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("zzz"), r.h.commits)
    }

    @Test
    fun `中文态标点全角逗号句号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_COMMA)
        r.key(KeyEvent.KEYCODE_PERIOD)
        assertEquals(listOf("\uFF0C", "\u3002"), r.h.commits)
    }

    @Test
    fun `组词被符号打断先上屏首选`() {
        val r = rig()
        r.type("nihao")
        r.key(KeyEvent.KEYCODE_COMMA)
        assertEquals(listOf("你好", "\uFF0C"), r.h.commits)
    }

    // ---------------------------------------------------------------- 选字/翻页

    @Test
    fun `方向键翻页与高亮确认`() {
        val r = rig(pageSize = 1)
        r.type("ni") // 候选:你 / 你好(前缀) → pageSize=1 → 2 页
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, r.c.state.page)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(0, r.c.state.highlight)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(listOf("你好"), r.h.commits)
    }

    @Test
    fun `候选为空时方向键透传`() {
        val r = rig()
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `ctrl 加 qwe rt 五键选字`() {
        val r = rig(pageSize = 5)
        r.type("ni")
        assertTrue(r.key(kc('q'), ctrl = true))
        assertEquals(listOf("你"), r.h.commits)

        r.type("ni")
        r.key(kc('w'), ctrl = true)
        assertEquals(listOf("你", "你好"), r.h.commits)
    }

    @Test
    fun `其他 ctrl 组合原样转发`() {
        val r = rig()
        assertTrue(r.key(kc('c'), ctrl = true))
        assertEquals(listOf("${kc('c')}/true/false"), r.h.keys)
    }

    @Test
    fun `翻页方向反转`() {
        val r = rig(pageSize = 1)
        r.c.pageReverse = true
        r.type("ni")
        r.key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, r.c.state.page)
    }

    // ---------------------------------------------------------------- 中英模式

    @Test
    fun `shift 加空格切换中英`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals(ImeMode.EN, r.c.state.mode)
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals(ImeMode.CN, r.c.state.mode)
    }

    @Test
    fun `英文态字母直接上屏并跟随大写`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        r.key(kc('a'))
        r.key(kc('b'), shift = true)
        assertEquals(listOf("a", "B"), r.h.commits)
    }

    @Test
    fun `英文态组词中切换先上屏首选`() {
        val r = rig()
        r.type("nihao")
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals(listOf("你好"), r.h.commits)
        assertEquals(ImeMode.EN, r.c.state.mode)
    }

    // ---------------------------------------------------------------- 层与锁定

    @Test
    fun `Alt 单击一次性进 L2 输出后自动回 L1`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        assertTrue(r.key(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        r.key(kc('q'))
        assertEquals(listOf("1"), r.h.commits)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Alt 双击锁定 L2 连续输出单击解锁`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('q'))
        r.key(kc('w'))
        assertEquals(listOf("1", "2"), r.h.commits)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        // 真实节奏:锁定后先输入符号,再次敲 Alt 时早已超出双击窗口 → 单击解锁
        Thread.sleep(450)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `锁定态下 Alt Sym 互切层`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lock)
        r.key(KeyEvent.KEYCODE_PICTSYMBOLS)
        r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS)
        r.key(KeyEvent.KEYCODE_PICTSYMBOLS)
        r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock)
    }

    @Test
    fun `中文态 L2 输出全角符号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('q'))
        assertEquals(listOf("\uFF11"), r.h.commits) // 全角 1
    }

    @Test
    fun `英文态 L2 输出半角符号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('q'))
        assertEquals(listOf("1"), r.h.commits)
    }

    @Test
    fun `L3 空格输出箭头`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_PICTSYMBOLS)
        r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS)
        r.key(KeyEvent.KEYCODE_PICTSYMBOLS)
        r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock)
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("->"), r.h.commits)
    }

    @Test
    fun `Sym 三击循环层`() {
        val r = rig()
        repeat(3) {
            r.key(KeyEvent.KEYCODE_PICTSYMBOLS)
            r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS)
        }
        // tap1 一次性 L3,tap2 锁定 L3,tap3 循环 → 解除锁定回 L1
        assertEquals(null, r.c.state.lock)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Sym 单击 keyUp 才生效进 L3 一次性`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        assertTrue(r.key(KeyEvent.KEYCODE_PICTSYMBOLS))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        r.key(kc('q'))
        assertEquals(listOf("{"), r.h.commits)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `按住 Sym 加键等于 Ctrl 组合松开不切层`() {
        val r = rig()
        r.type("ni")
        r.key(KeyEvent.KEYCODE_PICTSYMBOLS) // 按住
        assertTrue(r.key(kc('q'))) // Sym+q = 选候选1
        assertEquals(listOf("你"), r.h.commits)
        assertTrue(r.key(kc('c'))) // Sym+c = 透传 Ctrl+C
        assertEquals(listOf("${kc('c')}/true/false"), r.h.keys)
        r.c.onKeyUp(KeyEvent.KEYCODE_PICTSYMBOLS)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer) // 松开不切层
    }

    @Test
    fun `Alt 键值可配置`() {
        val r = rig()
        r.c.altKeycodes = intArrayOf(999)
        assertTrue(r.key(999))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
    }

    // ---------------------------------------------------------------- Shift/Caps

    @Test
    fun `软键盘 Shift 单击下一键大写双击 Caps`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.c.onShiftTap() // 单击:下一键大写
        r.key(kc('a'))
        assertEquals(listOf("A"), r.h.commits)
        r.c.onShiftTap() // 窗口内第二次:双击 = Caps
        r.key(kc('b'))
        assertEquals(listOf("A", "B"), r.h.commits)
        r.key(kc('c')) // Caps 持续
        assertEquals(listOf("A", "B", "C"), r.h.commits)
    }

    // ---------------------------------------------------------------- 透传

    @Test
    fun `未映射键透传给应用`() {
        val r = rig()
        assertFalse(r.key(KeyEvent.KEYCODE_ESCAPE))
        assertFalse(r.key(KeyEvent.KEYCODE_F5))
        assertFalse(r.key(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun `切换输入框丢弃组词但保留模式与层`() {
        val r = rig()
        r.type("ni")
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.c.resetForNewInput()
        assertEquals("", r.c.state.raw)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lock)
    }
}
