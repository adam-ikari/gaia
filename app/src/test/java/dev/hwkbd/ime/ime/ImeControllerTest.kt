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

        override fun postDelayed(action: Runnable, delayMs: Long) {
            action.run()
        }
    }

    private val dictLines = listOf(
        "ni\t你\t500",
        "hao\t好\t400",
        "nihao\t你好\t300",
        "shijie\t世界\t250",
        "zhongguo\t中国\t600",
        "nian\t年\t900",
        "niang\t娘\t800",
        "niao\t鸟\t100",
        "nie\t捏\t50",
    )

    private class Rig(val c: ImeController, val h: RecHost) {
        fun type(s: String) {
            for (ch in s) c.onKeyDown(kc(ch), false, false)
        }

        fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false, caps: Boolean = false): Boolean =
            c.onKeyDown(code, ctrl, shift, caps)

        /** 完整敲击(按下 + 快速松开):修饰键的敲击/长按语义要到 keyUp 才定型。 */
        fun tap(code: Int): Boolean {
            val down = c.onKeyDown(code, false, false)
            c.onKeyUp(code)
            return down
        }
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
        // Titan2 无独立标点键:中文标点走 Alt 层 N=, B=.
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT); r.key(kc('n'))
        r.key(KeyEvent.KEYCODE_ALT_LEFT); r.key(kc('b'))
        assertEquals(listOf("\uFF0C", "\u3002"), r.h.commits)
    }

    // ---------------------------------------------------------------- 选字/翻页

    @Test
    fun `候选不足时从中位紧凑补位`() {
        val r = rig()
        r.type("ni")
        r.c.pageStep(1) // 第 2 页只剩「捏」
        val slots = r.c.state.pageSlots()
        assertEquals(listOf(null, null, "捏", null, null), slots.map { it?.word })
        r.c.selectCandidate(2) // 中间槽选字
        assertEquals(listOf("捏"), r.h.commits)
    }

    @Test
    fun `方向键翻页与高亮确认`() {
        val r = rig()
        r.type("ni") // 候选 6 个:你 年 娘 你好 鸟 捏 → 2 页
        assertEquals(6, r.c.state.candidates.size)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, r.c.state.page)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(2, r.c.state.highlight) // 第二页只有"捏",位于中间槽(空格位)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(listOf("捏"), r.h.commits)
    }

    @Test
    fun `首候选固定显示在中间`() {
        val r = rig()
        r.type("ni")
        val items = r.c.state.pageItems()
        assertEquals(5, items.size)
        assertEquals("你", items[2].word) // 中间 = 首选 = 空格键位
        // 与可可实测排列一致(ni → 呢 拟 你 尼 泥):
        // 测试词典 logical = [你, 年, 娘, 你好, 鸟, 捏] → 显示 [年, 鸟, 你, 你好, 娘]
        assertEquals(listOf("年", "鸟", "你", "你好", "娘"), items.map { it.word })
    }

    @Test
    fun `组词中五修饰键独立敲击选字`() {
        val r = rig()
        r.type("ni")
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(listOf("年"), r.h.commits) // Shift = 槽0 = 候选2

        r.type("ni")
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(listOf("年", "娘"), r.h.commits) // Alt = 槽4 = 候选3

        r.type("ni")
        r.key(KeyEvent.KEYCODE_CTRL_LEFT)
        r.c.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        assertEquals(listOf("年", "娘", "你好"), r.h.commits) // Ctrl = 槽3 = 候选4
    }

    @Test
    fun `空格选中间首选`() {
        val r = rig()
        r.type("ni")
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("你"), r.h.commits)
    }

    @Test
    fun `长按候选删除用户词刷新排序`() {
        val r = rig()
        repeat(2) { // 跨档需要两次使用
            r.type("nihao")
            r.key(KeyEvent.KEYCODE_SPACE) // 选"你好"(前缀候选) → 频次加权
        }
        r.type("ni")
        assertEquals("你好", r.c.state.candidates.first().word) // 被顶到第一(越过精确命中"你")
        val slot = r.c.state.pageItems().indexOfFirst { it.word == "你好" }
        r.c.removeUserWord(slot) // 长按删除
        assertEquals(0, r.c.userCountOf("你好")) // 用户频次清零
        assertEquals("你", r.c.state.candidates.first().word) // 精确命中"你"回到第一
        assertFalse(r.c.state.candidates.take(2).any { it.word == "你好" }) // "你好"跌出前二
    }

    @Test
    fun `候选为空时方向键透传`() {
        val r = rig()
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `ctrl 加 qwe rt 按槽位选字`() {
        val r = rig()
        r.type("ni")
        assertTrue(r.key(kc('q'), ctrl = true))
        assertEquals(listOf("年"), r.h.commits) // Q = 槽0(年被用过一次仍居 logical[1]? 不——年跨档后居首)
        // 年 count=1 → 3.5009M 仍低于 你 4.0005M → 首选还是你;槽0 = logical[1] = 年 ✓

        r.type("ni")
        r.key(kc('t'), ctrl = true)
        assertEquals(listOf("年", "娘"), r.h.commits) // T = 槽4 = logical[2] = 娘(年跨档后 logical[1]=你? —— 按实测排序)
    }

    @Test
    fun `其他 ctrl 组合原样转发`() {
        val r = rig()
        assertTrue(r.key(kc('c'), ctrl = true))
        assertEquals(listOf("${kc('c')}/true/false"), r.h.keys)
    }

    @Test
    fun `翻页方向反转`() {
        val r = rig()
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
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer) // 按下即进层(长按也从这里开始)
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 快速松开 = 敲击,一次性层保留
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits) // Alt 层 W=1(九宫格)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Alt 期间可连续输出 L2 松开取消`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.key(KeyEvent.KEYCODE_ALT_LEFT) // 按住不放
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        r.key(kc('w'))
        r.key(kc('e'))
        r.key(kc('r'))
        assertEquals(listOf("1", "2", "3"), r.h.commits) // 一次性被消费后长按层继续兜着
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 长按松开 = 取消
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Alt 不按任何键松开也取消`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(emptyList<String>(), r.h.commits)
    }

    @Test
    fun `Alt 双击锁定 L2 连续输出单击解锁`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        r.key(kc('e'))
        assertEquals(listOf("1", "2"), r.h.commits)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        // 真实节奏:锁定后先输入符号,再次敲 Alt 时早已超出双击窗口 → 单击解锁
        Thread.sleep(450)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `一次性层激活后再次独立按同键取消`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        Thread.sleep(450) // 窗口外独立敲击
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer) // 取消
        assertFalse(r.c.state.hasComposition)
    }

    @Test
    fun `Sym 一次性层激活后再次独立按取消`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        Thread.sleep(450)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer) // 取消
    }

    @Test
    fun `锁定态下 Alt Sym 互切层`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lock)
        r.tap(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock) // 锁定态下异层键一次切换
    }

    @Test
    fun `中文态 L2 数字保持半角`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits) // 九宫格数字用于输号码,保持半角
    }

    @Test
    fun `中文态 L2 标点全角`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('t'))
        assertEquals(listOf("\uFF08"), r.h.commits) // Alt 层 T=( → 全角(
    }

    @Test
    fun `英文态 L2 输出半角符号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits)
    }

    @Test
    fun `L3 空格保持原功能`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock)
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf(" "), r.h.commits) // 空格在 L3 不再特判(与可可一致)
    }

    @Test
    fun `Sym 连击三击为武装锁定解除`() {
        val r = rig()
        repeat(3) {
            r.key(KeyEvent.KEYCODE_SYM)
            r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        }
        // tap1 一次性 L3,tap2 锁定 L3,tap3 解锁回 L1
        assertEquals(null, r.c.state.lock)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Sym 锁定后再次双击应保持解除`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 第一击:解锁
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 第二击:连击被吸收,不重新武装
        assertEquals(null, r.c.state.lock)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Sym 单击进 L3 一次性 敲击语义在 keyUp 定型`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        assertTrue(r.key(KeyEvent.KEYCODE_SYM))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer) // 按住即进 L3(长按语义)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 快速松开 = 敲击,一次性 L3 保留
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        r.key(kc('q'))
        assertEquals(emptyList<String>(), r.h.commits) // L3 Q=Esc:功能键转发,不上屏文本
        assertEquals(listOf("${KeyEvent.KEYCODE_ESCAPE}/false/false"), r.h.keys)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Sym 期间按键走 L3 松开取消`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.key(KeyEvent.KEYCODE_SYM) // 按住不放
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        r.key(kc('x')) // L3 X=&
        assertEquals(listOf("&"), r.h.commits)
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 长按松开 = 取消,不再算敲击
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.lock)
    }

    @Test
    fun `长按 Sym 不按任何键松开也取消`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.oneShot)
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

    @Test
    fun `物理 Shift 单击大写一次 双击 Caps 再单击关掉`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 单击
        assertEquals(ShiftState.ONCE, r.c.state.shift)
        assertFalse(r.c.state.shiftHeld) // 松手后大写提示熄灭
        r.key(kc('a'))
        assertEquals(listOf("A"), r.h.commits)
        assertEquals(ShiftState.OFF, r.c.state.shift) // 输入一个字母就取消

        Thread.sleep(250) // 出双击窗口,重新起算
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 双击第一击:大写一次
        assertEquals(ShiftState.ONCE, r.c.state.shift)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 第二击:Caps 锁定
        assertEquals(ShiftState.CAPS, r.c.state.shift)
        r.key(kc('b'))
        r.key(kc('c'))
        assertEquals(listOf("A", "B", "C"), r.h.commits)

        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 已在 Caps:单击 = 关掉锁定
        assertEquals(ShiftState.OFF, r.c.state.shift)
    }

    @Test
    fun `物理 Shift 长按只在按住期间提示 松手不留下状态`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertTrue(r.c.state.shiftHeld) // 按住期间状态行亮大写芯片
        Thread.sleep(350)
        r.key(kc('a'), shift = true) // 长按大写由物理修饰键承担
        assertEquals(listOf("A"), r.h.commits)
        r.c.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertFalse(r.c.state.shiftHeld)
        assertEquals(ShiftState.OFF, r.c.state.shift) // 松手取消,不残留一次性/锁定
        r.key(kc('b'))
        assertEquals(listOf("A", "b"), r.h.commits)
    }

    @Test
    fun `Shift 当修饰键用过就不算敲击`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        r.key(kc('a'), shift = true) // 按住期间按了别的键
        r.c.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.OFF, r.c.state.shift) // 不武装大写一次
        r.key(kc('b'))
        assertEquals(listOf("A", "b"), r.h.commits) // 按住期间大写来自物理 shift,松开后回小写
    }

    @Test
    fun `拼音态忽略 Shift 敲击 不产生大小写锁定`() {
        val r = rig()
        assertEquals(ImeMode.CN, r.c.state.mode)
        r.c.onShiftTap()
        r.c.onShiftTap() // 双击也不给锁定:拼音不需要大小写
        assertEquals(ShiftState.OFF, r.c.state.shift)
        r.key(kc('n'))
        r.key(kc('i'))
        assertTrue(r.c.state.hasComposition)
        assertEquals("ni", r.c.state.raw) // 组词照常,不受大写状态影响
    }

    @Test
    fun `切进拼音态清掉英文态的大小写锁定`() {
        val r = rig()
        r.c.state.mode = ImeMode.EN
        r.c.onShiftTap()
        r.c.onShiftTap()
        assertEquals(ShiftState.CAPS, r.c.state.shift)
        r.c.toggleMode()
        assertEquals(ImeMode.CN, r.c.state.mode)
        assertEquals(ShiftState.OFF, r.c.state.shift)
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
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(KeyEvent.KEYCODE_ALT_LEFT) // 无组词时双击 Alt 锁 L2
        r.c.resetForNewInput()
        assertEquals("", r.c.state.raw)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lock)
    }
}
