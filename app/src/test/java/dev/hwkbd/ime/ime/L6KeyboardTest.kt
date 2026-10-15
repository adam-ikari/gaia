package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.strategy.FakePinyinInputMethod
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.PinyinInputMethod
import dev.hwkbd.ime.strategy.withConsumed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L6 虚拟键盘的**状态机**断言(2026-10-15 用户「空闲状态 sym+space 打开虚拟键盘,
 * 这是 L6 键盘,支持多个 tab 页和上下滑动翻页」)。
 *
 * 四条定案各钉在这里:仅空闲态开关 / Sym+Space 抢键 / tab 内翻页 / 关闭不写字符。
 * 面板的绘制与滑动手感在真机上验(本地 E2E),本类只管**状态**—— 与项目里
 * 「状态机纯逻辑、绘制靠真机」的分工一致。
 */
class L6KeyboardTest {

    /** 敲 Sym(一次性借用 L3)+ 空格:这就是「Sym+Space」这一把键。 */
    private fun ImeController.symSpace() {
        onKeyDown(KeyEvent.KEYCODE_SYM, false, false)
        onKeyUp(KeyEvent.KEYCODE_SYM)
        onKeyDown(KeyEvent.KEYCODE_SPACE, false, false)
        onKeyUp(KeyEvent.KEYCODE_SPACE)
    }

    @Test
    fun `空闲态 Sym+Space 打开与关闭`() {
        val r = ImeControllerTestRig()
        val c = r.c
        assertFalse("初始关着", c.l6Open)
        c.symSpace()
        assertTrue("敲一次打开", c.l6Open)
        assertEquals("出厂第一个 tab", 0, c.state.l6!!.tabIndex)
        c.symSpace()
        assertFalse("再敲一次关闭", c.l6Open)
        assertTrue("开关不写任何字符", r.h.commits.isEmpty())
    }

    @Test
    fun `英文态也开`() {
        // 2026-10-15 用户定「两种语言都开」:面板是符号入口,与当前语言无关。
        val r = ImeControllerTestRig()
        val c = r.c
        c.cycleLanguage() // 拼 → En
        assertEquals(dev.hwkbd.ime.strategy.EnglishLanguage.id, c.state.languageId)
        c.symSpace()
        assertTrue("英文态同样能开 L6", c.l6Open)
    }

    @Test
    fun `组词中 Sym+Space 仍是空格 不开面板`() {
        // **这条与 `ImeControllerTest` 的 `L3 空格保持原功能` 是一对**:
        // 那条钉的是「面板不存在时 Sym+Space 是空格」,这条钉的是「组词中也是空格」。
        // 空闲态被抢走的那部分由上一条钉住。
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace() // 先打开面板
        assertTrue(c.l6Open)
        c.onKeyDown(KeyEvent.KEYCODE_A, false, false) // 敲个字母 → 组词 → 面板自动收
        assertTrue("组词了", c.state.hasComposition)
        assertFalse("组词开始面板就收掉", c.l6Open)
        assertEquals("a", c.state.raw)

        // 组词中 Sym+Space:走空格的老路
        c.symSpace()
        assertFalse("组词中不开面板", c.l6Open)
    }

    @Test
    fun `面板开着时敲任意字符自动关闭面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        assertTrue(c.l6Open)
        c.onKeyDown(KeyEvent.KEYCODE_N, false, false)
        assertFalse("一敲字就收", c.l6Open)
    }

    @Test
    fun `面板上敲符号直接上屏且面板留着`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        // 中文态标点不进组词缓冲(拼音只组字母 a-z):面板上点一颗「，」=直接上屏。
        // 面板是符号盘 —— 上屏一颗符号后留在原地,好接着点下一颗;组词一开始才收面板。
        c.commitL4Key(KeyOut.Text("，"))
        assertEquals(listOf("，"), r.h.commits)
        assertEquals("不进组词", "", c.state.raw)
        assertTrue("符号上屏后面板留着(接着敲下一颗)", c.l6Open)
    }

    @Test
    fun `英文态面板上敲字符直接上屏`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.cycleLanguage() // En
        c.symSpace()
        c.commitL4Key(KeyOut.Text("{}"))
        assertEquals(listOf("{}"), r.h.commits)
        assertEquals("不进组词", "", c.state.raw)
    }

    @Test
    fun `Esc 关面板且不转发给应用`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        c.commitL4Key(KeyOut.Special("esc"))
        assertFalse("面板上的 Esc 收面板", c.l6Open)
        assertTrue("Esc 不转发", r.h.keys.isEmpty())
    }

    @Test
    fun `物理 Sym+Q(Esc)也收面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        assertTrue(c.l6Open)
        // 出厂 L3 的 Q = Esc(照可可扩展字符表):**按住 Sym 再敲 Q** 才是真发 Esc 的手势。
        // 开面板那一下借的 L3 已用掉(见 toggleL6),不按住 Sym 的话 Q 只是字母 q。
        c.onKeyDown(KeyEvent.KEYCODE_SYM, false, false) // 按住 Sym → L3
        c.onKeyDown(KeyEvent.KEYCODE_Q, false, false) // L3 的 Q = Esc
        c.onKeyUp(KeyEvent.KEYCODE_Q)
        c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertFalse("Sym+Q 收面板", c.l6Open)
        assertTrue("不转发 Esc 给应用", r.h.keys.isEmpty())
    }

    @Test
    fun `面板开着时其他功能键照常转发`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        c.commitL4Key(KeyOut.Special("tab"))
        assertTrue("Tab 仍然转发", r.h.keys.any { it.startsWith(KeyEvent.KEYCODE_TAB.toString()) })
        assertTrue("Tab 不该收面板", c.l6Open)
    }

    @Test
    fun `切语言与换输入框都收面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        c.cycleLanguage()
        assertFalse("切语言收面板", c.l6Open)

        c.symSpace()
        assertTrue(c.l6Open)
        c.resetForNewInput()
        assertFalse("换输入框收面板", c.l6Open)
    }

    @Test
    fun `面板开着时方向键翻页与切 tab`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        assertTrue("出厂每 tab 至少两页(否则翻页无从验)", c.state.l6!!.pageCount >= 2)

        c.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, false, false)
        assertEquals("↓ 下一页", 1, c.state.l6!!.pageIndex)
        c.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, false, false)
        assertEquals("↑ 上一页", 0, c.state.l6!!.pageIndex)

        c.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, false, false)
        assertEquals("→ 下一个 tab", 1, c.state.l6!!.tabIndex)
        c.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, false, false)
        assertEquals("← 上一个 tab", 0, c.state.l6!!.tabIndex)

        assertTrue("方向键不写字符", r.h.commits.isEmpty())
    }

    @Test
    fun `翻页在 tab 内进行 不循环`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        val ui = c.state.l6!!
        val pages = ui.pageCount
        assertTrue("出厂每页至少两页(否则翻页无从验)", pages >= 2)

        assertTrue(c.l6StepPage(1))
        assertEquals(1, c.state.l6!!.pageIndex)
        // 翻到底:再往下不动,且返回 false(面板据此不给反馈)
        while (c.l6StepPage(1)) { /* 走到头 */ }
        assertEquals(pages - 1, c.state.l6!!.pageIndex)
        assertFalse("到底不循环", c.l6StepPage(1))
        assertTrue("往回能翻", c.l6StepPage(-1))
    }

    @Test
    fun `切 tab 时页码归零`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        c.l6StepPage(1)
        assertEquals(1, c.state.l6!!.pageIndex)
        c.l6SelectTab(2)
        assertEquals("切 tab 后回第 0 页", 0, c.state.l6!!.pageIndex)
        assertEquals(2, c.state.l6!!.tabIndex)
    }

    @Test
    fun `关着时翻页与切 tab 都是空操作`() {
        val r = ImeControllerTestRig()
        val c = r.c
        assertFalse(c.l6StepPage(1))
        c.l6SelectTab(1)
        assertNull(c.state.l6)
    }

    @Test
    fun `出厂内容里功能键走 Special 不是字面文本`() {
        // 「Tab」「Esc」当文本敲进去就是两个英文单词(与 L3 层空格那条同类的错)
        val tabs = L6Ui.factory().tabs
        val control = tabs.first { it.id == "control" }
        val second = control.pages[1].keys
        assertTrue("控制页第二页有功能键", second.isNotEmpty())
        for (k in second) {
            assertTrue("功能键 ${k.label} 应是 Special", k.out is KeyOut.Special)
        }
        // 文本键那一页反过来
        for (k in control.pages[0].keys) {
            assertTrue("字符键 ${k.label} 应是 Text", k.out is KeyOut.Text)
            assertEquals("字符键标签=输出", (k.out as KeyOut.Text).text, k.label)
        }
    }

    @Test
    fun `出厂 tab 都有内容且页数一致`() {
        val tabs = L6Ui.factory().tabs
        assertTrue("至少三个 tab", tabs.size >= 3)
        for (t in tabs) {
            assertTrue("${t.id} 有页", t.pages.isNotEmpty())
            for ((i, p) in t.pages.withIndex()) {
                assertTrue("${t.id} 第 ${i + 1} 页有键", p.keys.isNotEmpty())
            }
        }
        assertEquals("每个 tab 页数相同(便于翻页手感一致)", 1, tabs.map { it.pages.size }.distinct().size)
    }
}

/**
 * 共用的最小装置:控制器 + 记录上屏/按键的宿主。
 *
 * 注册方式照抄生产([ImeService] 里 `registerMethod(PinyinInputMethod(rime))`):
 * **注册装饰器,不是裸替身** —— 否则「装饰器漏转发某个成员」这类 bug 单测一律绿,
 * 生产上却静默走接口默认值(`isReady` 与 `topSpelling` 都这么漏过)。
 */
private class ImeControllerTestRig {
    val h = RecordingHost()
    private val engine = FakePinyinInputMethod.common().withConsumed(2)
    private val method = PinyinInputMethod.forTest(engine)
    private val registry = ImeRegistry.builtin().registerMethod(method)
    val c: ImeController = ImeController(h, KeyLayout.default(), registry)
}

/** 只记东西不做别的宿主;断言「写了什么 / 转发了什么」全看这两个表。 */
private class RecordingHost : ImeHost {
    val commits = mutableListOf<String>()
    val keys = mutableListOf<String>()
    override fun commitText(text: String) { commits += text }
    override fun sendKey(code: Int, ctrl: Boolean, shift: Boolean) { keys += "$code/$ctrl/$shift" }
    override fun render() {}
}