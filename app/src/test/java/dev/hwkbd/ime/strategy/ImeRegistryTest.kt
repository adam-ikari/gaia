package dev.hwkbd.ime.strategy

import android.text.InputType
import android.view.KeyEvent
import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.CandidateKind
import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.engine.UserDict
import dev.hwkbd.ime.ime.ImeController
import dev.hwkbd.ime.ime.ImeHost
import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 策略注册表:加语言 / 加输入法 / 加输入框表现都只注册,状态机与 UI 不改(本文件即证明)。
 */
class ImeRegistryTest {

    private class RecHost : ImeHost {
        val commits = mutableListOf<String>()
        override fun commitText(text: String) {
            commits += text
        }

        override fun sendKey(code: Int, ctrl: Boolean, shift: Boolean) {}
        override fun render() {}
    }

    private class Rig(val c: ImeController, val h: RecHost) {
        fun type(s: String) {
            for (ch in s) c.onKeyDown(KeyEvent.KEYCODE_A + (ch - 'a'), false, false)
        }
    }

    /** 语言策略样例:上屏形态来自策略(可选:字母进组词缓冲,用于绑新输入法)。 */
    private class BracketLanguage(
        override val id: String,
        override val methodId: String,
        override val l4Extras: List<L4Key> = emptyList(),
        private val compose: Boolean = false,
    ) : Language {
        override val chipLabel = "[$id]"
        override val showShiftChip = false
        override val shiftTapsEnabled = false
        override fun composes(text: String, state: ImeState): Boolean =
            compose && text.length == 1 && text[0] in 'a'..'z'

        override fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String =
            "[$text]"
    }

    /** 输入法样例:候选固定一条,并记录上屏/频次调用。 */
    private class FixedMethod : InputMethod {
        override val id = "fixed"
        var committed = ""
        override fun query(raw: String, limit: Int): List<Candidate> =
            listOf(Candidate("固定", raw, CandidateKind.EXACT, 1L))

        override fun onCommitted(word: String) {
            committed = word
        }

        override fun userCount(word: String): Int = 7
    }

    private fun rig(): Rig {
        val dict = PinyinDict()
        dict.load(listOf("ni\t你\t500"))
        val h = RecHost()
        val registry = ImeRegistry.builtin()
            .registerMethod(PinyinInputMethod(PinyinEngine(dict, UserDict())))
        return Rig(ImeController(h, KeyLayout.default(), registry), h)
    }

    @Test
    fun `输入类型决定 L4 键位套与 Alt 芯片文字`() {
        assertEquals(
            FieldKind.URL,
            FieldKind.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI),
        )
        assertEquals(FieldKind.NUMBER, FieldKind.from(InputType.TYPE_CLASS_NUMBER))
        assertEquals(FieldKind.NUMBER, FieldKind.from(InputType.TYPE_CLASS_PHONE))
        assertEquals(FieldKind.TEXT, FieldKind.from(InputType.TYPE_CLASS_TEXT))

        val reg = ImeRegistry.builtin()
        assertEquals(KeyLayout.L4_PROFILE_URL, reg.fieldProfile(FieldKind.URL).l4ProfileId)
        assertEquals("0-9", reg.fieldProfile(FieldKind.NUMBER).altChipLabel) // alt 锁定的数字变种
        assertEquals("ALT", reg.fieldProfile(FieldKind.TEXT).altChipLabel)
        assertEquals("en", reg.secureLanguageId) // 密码框强制的语言
    }

    @Test
    fun `网址框出网址键 数字框走默认套 拼音补分节符`() {
        val r = rig()
        r.c.state.fieldKind = FieldKind.URL
        assertEquals(
            // 语言补差键**排最左**(输入法注册的键),其后才是输入类型选的键位套
            listOf(ChineseLanguage.SEPARATOR, "http://", "https://", ".com", "/"),
            r.c.l4Keys().map { it.label },
        )
        // 数字框没有自己的 L4 套(那是 Alt 芯片的活):补差键 + 默认核心键
        r.c.state.fieldKind = FieldKind.NUMBER
        assertEquals(
            listOf(ChineseLanguage.SEPARATOR) + KeyLayout.L4_CORE.map { it.label },
            r.c.l4Keys().map { it.label },
        )
        // 键位套换了,面板签名必须跟着变(service 据此决定要不要重建键帽)
        val sigNumber = r.c.l4Signature()
        r.c.state.fieldKind = FieldKind.URL
        assertTrue(sigNumber != r.c.l4Signature())
        // 英文态没有分节符(那是拼音的补差键)
        r.c.state.languageId = "en"
        assertFalse(r.c.l4Keys().any { it.out == KeyOut.Text(ChineseLanguage.SEPARATOR) })
    }

    @Test
    fun `拼音组词中分节符进缓冲 候选按无分隔符查询`() {
        val r = rig()
        r.type("ni")
        val before = r.c.state.candidates.map { it.word }
        assertTrue(before.isNotEmpty())
        r.c.commitL4Key(KeyOut.Text(ChineseLanguage.SEPARATOR))
        assertEquals("ni'", r.c.state.raw) // 分节符显示在组词区(xi'an 语义)
        assertEquals(before, r.c.state.candidates.map { it.word }) // 查询剥掉分隔符,候选不变
    }

    @Test
    fun `空闲时 L4 分节符原样上屏 不新开组词`() {
        val r = rig()
        r.c.commitL4Key(KeyOut.Text(ChineseLanguage.SEPARATOR))
        assertEquals(listOf("'"), r.h.commits)
        assertFalse(r.c.state.hasComposition)
    }

    @Test
    fun `注册新语言后按它的策略走 不改状态机`() {
        val r = rig()
        r.c.registry.registerLanguage(BracketLanguage("ja", "direct", listOf(L4Key("あ", KeyOut.Text("あ")))))
        assertEquals(3, r.c.registry.languages().size)

        r.c.cycleLanguage() // zh → en
        r.c.cycleLanguage() // en → ja
        assertEquals("ja", r.c.state.languageId)
        assertEquals("[ja]", r.c.registry.language(r.c.state.languageId).chipLabel)

        r.type("a")
        assertEquals(listOf("[a]"), r.h.commits) // 上屏形态来自新语言策略
        assertTrue(r.c.l4Keys().any { it.label == "あ" }) // L4 补差键也来自策略

        r.c.cycleLanguage() // ja → zh,循环回首位
        assertEquals("zh", r.c.state.languageId)
    }

    @Test
    fun `注册新输入法后候选来自新方法`() {
        val r = rig()
        val fixed = FixedMethod()
        r.c.registry.registerMethod(fixed)
        r.c.registry.registerLanguage(BracketLanguage("xx", fixed.id, compose = true))
        r.c.cycleLanguage() // zh → en
        r.c.cycleLanguage() // en → xx

        r.type("ni")
        assertEquals(listOf("固定"), r.c.state.candidates.map { it.word }) // 候选来自新方法
        r.c.selectCandidate(2) // 中间槽 = 首选
        assertEquals(listOf("固定"), r.h.commits)
        assertEquals("固定", fixed.committed) // 上屏反馈打到新方法
        assertEquals(7, r.c.userCountOf("固定")) // 频次查询同样走新方法
    }
}
