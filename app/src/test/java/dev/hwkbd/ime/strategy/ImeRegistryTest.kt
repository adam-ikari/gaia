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
import org.junit.Assert.assertNull
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
        dict.load(
            listOf(
                "ni\t你\t500",
                // 分节符语义用例:先(xian) 与 西安(xi'an) 不同键,xi'an 不中 xian
                "xian\t先\t5000",
                "xi'an\t西安\t3000",
            ),
        )
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
        assertEquals(
            FieldKind.EMAIL,
            FieldKind.from(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS),
        )
        assertEquals(FieldKind.NUMBER, FieldKind.from(InputType.TYPE_CLASS_NUMBER))
        // 同理调查的其他输入类型:电话/日期时间/数字签名与小数变体(flag 不是 variation)
        assertEquals(FieldKind.PHONE, FieldKind.from(InputType.TYPE_CLASS_PHONE))
        assertEquals(FieldKind.DATETIME, FieldKind.from(InputType.TYPE_CLASS_DATETIME))
        assertEquals(
            FieldKind.NUMBER_SIGNED,
            FieldKind.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED),
        )
        assertEquals(
            FieldKind.NUMBER_DECIMAL,
            FieldKind.from(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL),
        )
        assertEquals(
            FieldKind.NUMBER_SIGNED_DECIMAL,
            FieldKind.from(
                InputType.TYPE_CLASS_NUMBER or
                    InputType.TYPE_NUMBER_FLAG_SIGNED or
                    InputType.TYPE_NUMBER_FLAG_DECIMAL,
            ),
        )
        assertEquals(FieldKind.TEXT, FieldKind.from(InputType.TYPE_CLASS_TEXT))

        val reg = ImeRegistry.builtin()
        assertEquals(KeyLayout.L4_PROFILE_URL, reg.fieldProfile(FieldKind.URL).l4ProfileId)
        assertEquals(KeyLayout.L4_PROFILE_EMAIL, reg.fieldProfile(FieldKind.EMAIL).l4ProfileId)
        // 核心键子集按合法性圈定(2026-09-29 同一把尺):邮箱只留 `$`(RFC 5322 atext 合法)、
        // 网址留 `; $`(sub-delim 合法),非法的 `\` 两框都不进
        assertEquals(listOf("\$"), reg.fieldProfile(FieldKind.EMAIL).l4CoreKeys.map { it.label })
        assertEquals(
            listOf("；", "$"),
            reg.fieldProfile(FieldKind.URL).l4CoreKeys.map { it.label },
        )
        assertEquals("0-9", reg.fieldProfile(FieldKind.NUMBER).altChipLabel)
        assertEquals("alt", reg.fieldProfile(FieldKind.TEXT).altChipLabel)
        // 芯片按实际可输字符集标注:decimal/signed/phone/date 光秃秃的 0-9 不恰当(2026-09-29)
        assertEquals("0-9-", reg.fieldProfile(FieldKind.NUMBER_SIGNED).altChipLabel)
        assertEquals("0-9.", reg.fieldProfile(FieldKind.NUMBER_DECIMAL).altChipLabel)
        assertEquals("0-9-.", reg.fieldProfile(FieldKind.NUMBER_SIGNED_DECIMAL).altChipLabel)
        assertEquals("0-9+*#", reg.fieldProfile(FieldKind.PHONE).altChipLabel)
        assertEquals("0-9-/:.", reg.fieldProfile(FieldKind.DATETIME).altChipLabel)
        // 邮箱框默认英文态,其余输入框不动当前语言
        assertEquals("en", reg.fieldProfile(FieldKind.EMAIL).defaultLanguageId)
        assertNull(reg.fieldProfile(FieldKind.TEXT).defaultLanguageId)
        assertEquals("en", reg.secureLanguageId) // 密码框强制的语言
        // 数字类限定 0-9 + 各类型合法字符,其余输入框不限
        assertTrue(reg.fieldProfile(FieldKind.NUMBER).digitsOnly)
        assertEquals("-", reg.fieldProfile(FieldKind.NUMBER_SIGNED).digitChars)
        assertEquals(".", reg.fieldProfile(FieldKind.NUMBER_DECIMAL).digitChars)
        assertEquals("+*#", reg.fieldProfile(FieldKind.PHONE).digitChars)
        assertEquals("-/:.", reg.fieldProfile(FieldKind.DATETIME).digitChars)
        assertTrue(reg.fieldProfile(FieldKind.DATETIME).digitsOnly)
        assertFalse(reg.fieldProfile(FieldKind.URL).digitsOnly)
        assertFalse(reg.fieldProfile(FieldKind.TEXT).digitsOnly)
        // 密码变体判定含以前漏掉的数字密码
        assertTrue(
            FieldKind.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD),
        )
        assertTrue(
            FieldKind.isPassword(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            ),
        )
        assertTrue(
            FieldKind.isPassword(
                InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD,
            ),
        )
        assertFalse(FieldKind.isPassword(InputType.TYPE_CLASS_NUMBER))
    }

    @Test
    fun `网址框出网址键 数字框走默认套 拼音 L4 不放分隔符`() {
        val r = rig()
        r.c.state.fieldKind = FieldKind.URL
        assertEquals(
            // 核心键子集并入(网址框 = `； $`,非法的 `\` 不进网址键帽)+ 输入类型选的键位套;
            // 拼音没注册补差键——分节符走 v 键,不占 L4(`/` 也不放,L2 的)
            listOf("；", "$") + listOf("http://", "https://", ".com"),
            r.c.l4Keys().map { it.label },
        )
        // 数字框没有自己的 L4 套(L4 面板在数字态整块隐藏,键计算仍是默认核心键)
        r.c.state.fieldKind = FieldKind.NUMBER
        assertEquals(
            KeyLayout.L4_CORE.map { it.label },
            r.c.l4Keys().map { it.label },
        )
        // 键位套换了,面板签名必须跟着变(service 据此决定要不要重建键帽)
        val sigNumber = r.c.l4Signature()
        r.c.state.fieldKind = FieldKind.URL
        assertTrue(sigNumber != r.c.l4Signature())
        // 拼音/英文的 L4 都没有分隔符键(v 键才是拼音的分隔符)
        assertFalse(r.c.l4Keys().any { it.out == KeyOut.Text(ChineseLanguage.SEPARATOR) })
        r.c.state.languageId = "en"
        assertFalse(r.c.l4Keys().any { it.out == KeyOut.Text(ChineseLanguage.SEPARATOR) })
    }

    @Test
    fun `邮件框出美元键 短后缀在前完整域名在后`() {
        val r = rig()
        r.c.state.fieldKind = FieldKind.EMAIL
        assertEquals(
            // `$` 是 RFC 5322 atext 合法字符,进邮箱键帽(2026-09-29 定案);
            // 短域名后缀优先级高排前(占 L4),完整域名排后(L4 装不下的尾巴进 L5,同日定案)
            listOf(
                "\$", ".com", ".net", ".org", ".cn", ".edu",
                "@gmail.com", "@hotmail.com", "@outlook.com",
            ),
            r.c.l4Keys().map { it.label },
        )
        assertTrue(r.c.l4Signature().startsWith(KeyLayout.L4_PROFILE_EMAIL)) // 换套要触发键帽重建
    }

    @Test
    fun `拼音组词中分节符进缓冲 查询按分节位匹配`() {
        val r = rig()
        r.type("ni")
        val before = r.c.state.candidates.map { it.word }
        assertTrue(before.isNotEmpty())
        r.c.commitL4Key(KeyOut.Text(ChineseLanguage.SEPARATOR))
        assertEquals("ni'", r.c.state.raw) // 分节符显示在组词区(xi'an 语义)
        // 末尾分节符:ni' 仍中 你(全串已输完,末尾分节只约束后续前缀)
        assertEquals(before, r.c.state.candidates.map { it.word })
        // 内部分节符才是要害:xi'an 按分节位切,只中西安、不中 xian 的先
        r.c.abandonComposition()
        r.type("xi")
        r.c.commitL4Key(KeyOut.Text(ChineseLanguage.SEPARATOR))
        r.type("an")
        assertEquals("xi'an", r.c.state.raw)
        assertEquals(listOf("西安"), r.c.state.candidates.map { it.word })
        // 不带分隔符歧义照旧:先 词频高排前,西安 照样在列
        r.c.abandonComposition()
        r.type("xian")
        assertEquals(listOf("先", "西安"), r.c.state.candidates.map { it.word })
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
