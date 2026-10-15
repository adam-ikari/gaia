package dev.hwkbd.ime.strategy

import android.text.InputType
import android.view.KeyEvent
import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.CandidateKind
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

        // 长按相关(ImeController 的通用收尾会问 host):本类不验长按,空实现即可
        override fun scheduleLongPress(code: Int) {}
        override fun cancelLongPress() {}
    }

    private class Rig(val c: ImeController, val h: RecHost) {
        fun type(s: String) {
            // down+up 一对(2026-10-15 长按上屏把提交挪到 UP 后必须成对)
            for (ch in s) {
                c.onKeyDown(KeyEvent.KEYCODE_A + (ch - 'a'), false, false)
                c.onKeyUp(KeyEvent.KEYCODE_A + (ch - 'a'))
            }
        }
    }

    /**
     * 用 [FakePinyinInputMethod](查表)而不是真引擎(2026-10-03 换 librime)。
     *
     * 本类验的是「注册表能不能按 id 找到方法、语言切换后候选来源对不对」,
     * 与拼音解码无关。把词典建进来只会让换引擎时重写构造。
     */
    private fun rig(): Rig {
        val h = RecHost()
        val registry = ImeRegistry.builtin()
            .registerMethod(FakePinyinInputMethod.common())
        return Rig(ImeController(h, KeyLayout.default(), registry), h)
    }

    /** 语言策略样例:上屏形态来自策略(可选:字母进组词缓冲,用于绑新输入法)。 */
    private class BracketLanguage(
        override val id: String,
        override val methodId: String,
        private val extras: List<L4Key> = emptyList(),
        private val compose: Boolean = false,
    ) : Language {
        override val statusLabel = "[$id]"
        override val showShift = false
        override val shiftTapsEnabled = false
        override fun l4Extras(fullWidth: Boolean): List<L4Key> = extras
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


    @Test
    fun `输入类型决定 L4 键位套与 Alt 文字`() {
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
        assertEquals("0-9", reg.fieldProfile(FieldKind.NUMBER).altLabel)
        assertEquals("alt", reg.fieldProfile(FieldKind.TEXT).altLabel)
        // Alt 那一项按实际可输字符集标注:decimal/signed/phone/date 光秃秃的 0-9 不恰当(2026-09-29)
        assertEquals("0-9-", reg.fieldProfile(FieldKind.NUMBER_SIGNED).altLabel)
        assertEquals("0-9.", reg.fieldProfile(FieldKind.NUMBER_DECIMAL).altLabel)
        assertEquals("0-9-.", reg.fieldProfile(FieldKind.NUMBER_SIGNED_DECIMAL).altLabel)
        assertEquals("0-9+*#", reg.fieldProfile(FieldKind.PHONE).altLabel)
        assertEquals("0-9-/:.", reg.fieldProfile(FieldKind.DATETIME).altLabel)
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
    fun `网址框出网址键 数字框只有电话有短语组合 拼音 L4 不放分隔符`() {
        val r = rig()
        r.c.state.fieldKind = FieldKind.URL
        assertEquals(
            // 拼音态四档优先级(2026-09-30 用户定案):固定核心键 → 通用高频标点(hot 补差键)
            // → 输入类型选的键位套 → 冷门补差键。币种归物理键所以核心 `$` 退场(见 coreKeys)。
            // 分节符不上屏(L1 的 v 键才是)。`/` 也不放(出厂 L2 的 O 位就有)。
            // 顿号也不在:2026-09-30 挪上 Sym+Z(L3),被"L1-L3 敲不出就不显示"那把筛带走了。
            listOf("；", "…", "——") + listOf(
                "http://", "https://", "www.", "localhost", "127.0.0.1", "192.168.",
                ".com", ".cn", ".net", ".org",
                ".io", ".dev", ".app", ".co",
                "index.html", ".html", ".php", ".json", ".xml", ".js", ".css", ".htm",
            ) + listOf("「", "」", "『", "』", "※"),
            r.c.l4Keys().map { it.label },
        )
        // 数字框:核心键全非法被滤;合法单字符全在 L2(被 producible 筛掉,不上 L4),
        // L4 放的是 L1-L3 敲不出的短语组合——出厂**电话**(国际区号)与**日期时间**(时刻串)有,
        // 其余数字类型没有组合可放 → 出厂为空(不是被筛空的结果)
        r.c.state.fieldKind = FieldKind.NUMBER
        assertEquals(emptyList<String>(), r.c.l4Keys().map { it.label })
        r.c.state.fieldKind = FieldKind.NUMBER_SIGNED
        assertEquals(emptyList<String>(), r.c.l4Keys().map { it.label })
        r.c.state.fieldKind = FieldKind.NUMBER_DECIMAL
        assertEquals(emptyList<String>(), r.c.l4Keys().map { it.label })
        r.c.state.fieldKind = FieldKind.NUMBER_SIGNED_DECIMAL
        assertEquals(emptyList<String>(), r.c.l4Keys().map { it.label })
        r.c.state.fieldKind = FieldKind.DATETIME
        // 日期时间:L4 **空**(2026-10-02 用户「所以不需要快捷短语」)。
        // `0-9 - / : .` 全部按得出,而要什么格式由 App 决定 —— 那些整串(`00:00` 之类)
        // 经常和 App 要的值对不上,要 `12:30` 的框里点 `00:00` 反而要接着改。
        assertEquals(emptyList<String>(), r.c.l4Keys().map { it.label })
        r.c.state.fieldKind = FieldKind.PHONE
        // 电话:区号次序 2026-10-02 用户定「大陆香港澳门美国日本放前面」。
        // **澳门 +853 是这次补上的** —— 此前整份表里没有它,而香港在,这是漏的。
        assertEquals(
            listOf("+86", "+852", "+853", "+1", "+81"),
            r.c.l4Keys().take(5).map { it.label },
        )
        assertTrue("区号该有一批(溢出到 L5 是既有机制)", r.c.l4Keys().size >= 15)
        // 回归:澳门补上之后,原有的常用区号一个都不能少
        assertTrue(
            r.c.l4Keys().map { it.label }.containsAll(
                listOf("+886", "+44", "+65", "+60", "+82", "+66", "+63", "+84", "+62", "+7", "+49", "+33", "+61", "+971")
            ),
        )
        // 回归:L4 显示的键必须 L1-L3 按不出(否则就该被筛掉,而不是"显示着却不按不出")
        val producible = KeyLayout.default().producible()
        for (kind in FieldKind.values()) {
            val fp = r.c.registry.fieldProfile(kind)
            if (!fp.digitsOnly) continue
            r.c.state.fieldKind = kind
            for (k in r.c.l4Keys()) {
                assertTrue(
                    "$kind 的 L4 键 ${k.label} 三层按得出,不该显示",
                    k.out.format() !in producible,
                )
            }
        }
        // 键位套换了,签名(hash of 键序)必须跟着变(service 据此决定要不要重建键帽)
        r.c.state.fieldKind = FieldKind.NUMBER
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
        r.c.state.languageId = "en" // 邮箱框默认英文态(见 FieldProfile.defaultLanguageId)
        r.c.state.fieldKind = FieldKind.EMAIL
        assertEquals(
            // `$` 是 RFC 5322 atext 合法字符(2026-09-29 定案),但它是**物理键**——
            // 出厂 L3 的 R 位就是它,按「L1-L3 敲不出的才上 L4」这把尺,它不上 L4;
            // 邮箱框照样按 RFC 5322 的合法字符集放行它敲(见 FieldProfile.digitChars 那把尺的同源口径)。
            // 短域名后缀优先级高排前(占 L4),完整域名排后(L4 装不下的尾巴进 L5,同日定案);
            // 2026-09-30「补充常用的」把完整域名按国内常用度补齐(163/qq/126 在国外那几家前面)
            listOf(
                ".com", ".net", ".org", ".cn", ".edu",
                "@163.com", "@qq.com", "@126.com",
                "@gmail.com", "@hotmail.com", "@outlook.com",
                "@yahoo.com", "@icloud.com", "@foxmail.com", "@live.com",
            ),
            r.c.l4Keys().map { it.label },
        )
        // 签名 = 键序内容的 hash:换套必变;同样的键序重复取必须一样(否则每次按键都重建键帽)
        val sigEmail = r.c.l4Signature()
        assertTrue(sigEmail == r.c.l4Signature())
        r.c.state.fieldKind = FieldKind.URL
        assertTrue(sigEmail != r.c.l4Signature())
        // 切到拼音:补差键那批「L1-L3 敲不出」的符号照常上屏——**邮箱允许中文**
        // (2026-09-30 用户「允许邮箱输入中文」:显示名 `张三 <…@…>`、备注里中文是真会出现的;
        // 曾按"地址是纯 ASCII"滤掉全角补差键,那是误伤,ASCII 过滤已撤)
        r.c.state.languageId = "zh"
        r.c.state.fieldKind = FieldKind.EMAIL
        val zh = r.c.l4Keys().map { it.label }
        assertTrue("邮箱框该有全角省略号", zh.contains("…"))
        assertTrue("邮箱框该有破折号", zh.contains("——"))
        // 半角开关关掉 ⇒ 同一批键换半角写法(状态行不该出现与开关矛盾的字形)
        r.c.fullWidthSymbolsInCn = false
        val halfZh = r.c.l4Keys().map { it.label }
        assertTrue(halfZh.contains("..."))
        assertTrue(halfZh.contains("--"))
        assertFalse(halfZh.contains("…"))
        // 币种归物理键,状态行不摆;`;` 不在 RFC 5322 atext,核心键里也不进
        assertFalse(zh.contains("￥"))
        assertFalse(zh.contains("$"))
        assertFalse(zh.contains("；"))
    }

    @Test
    fun `固定键优先级最高 排在模式组合短语之前`() {
        val r = rig()
        // 2026-09-30 用户定案「L4L5 显示的按键是有优先级的」:
        // 固定键(L1-L3 敲不出、按输入框合法值过滤过)> 模式组合短语 > 冷门符号 > (将来)用户短语。
        // L4/L5 是**前缀切**(L4PanelView 取装得下的最长前缀),所以优先级就是"留在 L4 还是
        // 被推进 L5"的判据 —— 键序必须严格按优先级升序。
        val layout = KeyLayout.default()
        for (langId in listOf("zh", "en")) {
            val lang = r.c.registry.language(langId)
            // 与 l4Keys 内部同一把尺:当前语言 + 全角开关下 L1-L3 真正敲得出的取值
            val producible = Layer.values().flatMap { l ->
                val over = lang.layerMap(l, true)
                if (over != null) over.values else layout.mapFor(l).values
            }.map { it.format() }.toSet()
            for (kind in FieldKind.values()) {
                val profile = r.c.registry.fieldProfile(kind)
                // 固定键过语言处置(可换键帽/退场)再过 L1-L3 那把筛,剩下的就是"该排最前"的那些
                val expectedFixed = lang.coreKeys(profile.l4CoreKeys, true)
                    .filterNot { it.out.format() in producible }
                    .map { it.label }
                r.c.state.languageId = langId
                r.c.state.fieldKind = kind
                val keys = r.c.l4Keys()
                val labels = keys.map { it.label }
                assertEquals(
                    "$langId/$kind 的固定键必须排在最前",
                    expectedFixed,
                    labels.take(expectedFixed.size),
                )
                // 整条键序按优先级升序(不光是固定键在前,档位之间也不能乱序)
                assertEquals(
                    "$langId/$kind 的键序没有按优先级排",
                    keys.map { it.priority }.sorted(),
                    keys.map { it.priority },
                )
                // 数字类固定键全非法(见 FieldProfile.l4CoreKeys = emptyList)⇒ 没有固定键,
                // L4 摆的全是模式组合短语(`+86` 那类),不违反规则
                if (profile.digitsOnly) assertTrue(expectedFixed.isEmpty())
            }
        }
    }

    @Test
    fun `通用高频标点排模式词组之前 半角省略号破折号上 L4 开头`() {
        val r = rig()
        // 用户报障原文:「在拼音输入法邮箱模式 ... 和 -- 就显示到 L5 的末尾了,
        // 我希望显示在 L4 的开头」—— 整批补差键排在词组之后导致的。
        r.c.state.languageId = "zh"
        r.c.state.fieldKind = FieldKind.EMAIL
        r.c.fullWidthSymbolsInCn = false // 半角态:补差键是 `...` / `--`(ASCII,邮箱里合法)
        val half = r.c.l4Keys().map { it.label }
        assertTrue("`...` 该在 L4 开头", half.indexOf("...") in 0..1)
        assertTrue("`--` 该紧跟它", half.indexOf("--") in 1..2)
        assertTrue(
            "高频标点要排在邮箱词组前面",
            half.indexOf("...") < half.indexOf(".com"),
        )
        // 普通文本框(没有模式词组可抢位):全角高频标点同样排最前,冷门 CJK 括号排最后
        r.c.state.fieldKind = FieldKind.TEXT
        r.c.fullWidthSymbolsInCn = true
        val text = r.c.l4Keys().map { it.label }
        assertEquals(listOf("；", "…", "——"), text.take(3))
        assertTrue(text.indexOf("「") > text.indexOf("；"))
        // 撞车时优先级高的赢:键位套里写一把与固定键同名同输出的键,留的还是固定键那把
        val dup = KeyLayout.parseText(
            """
            [L1]
            a=a
            [L4]
            ；=;
            """.trimIndent(),
        ).layout!!
        val keys = r.c.registry.l4Keys(dup, FieldKind.TEXT, "zh", true)
        assertEquals(1, keys.count { it.label == "；" }) // 没有并排两把
        assertEquals("；", keys.first().label) // 而且是它打头
    }

    @Test
    fun `邮箱框允许中文 全角补差键照常上屏且排在词组前`() {
        val r = rig()
        // 2026-09-30 用户定案「允许邮箱输入中文」。曾经按 RFC 5321/5322「地址是纯 ASCII」给邮箱
        // 加过一道全键集 ASCII 过滤,把 `… —— 、 「」` 全滤掉——那是误伤:邮箱框里中文真会出现
        // (显示名 `张三 <zhangsan@…>`、收件人备注)。过滤已撤,补差键回到 FIXED 档,
        // 于是"排在模式词组之前"这条优先级规则在邮箱框里也照常生效。
        r.c.state.languageId = "zh"
        r.c.state.fieldKind = FieldKind.EMAIL
        r.c.fullWidthSymbolsInCn = true
        val full = r.c.l4Keys().map { it.label }
        assertTrue("全角省略号该在", full.contains("…"))
        assertTrue("破折号该在", full.contains("——"))
        assertFalse("顿号上了 Sym+Z(L3),状态行不再摆", full.contains("、"))
        assertTrue("高频标点要排在邮箱词组前面", full.indexOf("…") < full.indexOf(".com"))
        assertTrue("冷门 CJK 括号排最后", full.indexOf("「") > full.indexOf(".com"))
        // 核心键那把尺照旧:`;` 不在 RFC 5322 atext,邮箱里不摆
        assertFalse(full.contains("；"))
        r.c.fullWidthSymbolsInCn = false
        val half = r.c.l4Keys().map { it.label }
        assertTrue(half.indexOf("...") in 0..1)
        assertTrue(half.indexOf("...") < half.indexOf(".com"))
    }

    @Test
    fun `核心键按语言换 键帽与输出始终一致`() {
        val r = rig()
        // 拼音态:核心分号换成全角输出(键帽画的也是全角,原先键帽全角却输出半角是骗人)
        val zh = r.c.l4Keys()
        assertTrue(zh.any { it.label == "；" && it.out == KeyOut.Text("；") })
        // 英文态:半角 `;` + `$`,键帽与输出都对得上(出厂核心键键帽画的是全角,
        // 英文态必须连键帽一起换,否则又是一把"画归正字敲出半角"的键)。
        // `$` 两态都**不在** L4 —— 它是物理键:出厂 L3 的 R 位就是它(见 `Sym 加 R 是币种位`),
        // 按「L1-L3 敲不出的才上 L4」这把尺,它两态都被筛掉。
        r.c.state.languageId = "en"
        val en = r.c.l4Keys()
        assertTrue(en.any { it.label == ";" && it.out == KeyOut.Text(";") })
        assertFalse("`$` 是物理键(Sym+R),不该在 L4", en.any { it.label == "\$" })
        assertFalse(en.any { it.label == "；" })
    }

    @Test
    fun `L4 装 L1-L3 敲不出的 成对符号在物理键 冷门符号在这儿`() {
        val r = rig()
        val labels = r.c.l4Keys().map { it.label }
        // `《》【】` 上了物理键(Sym+T/Y/G/H),所以**不该**在状态行重复出现
        for (k in listOf("《", "》", "【", "】")) {
            assertFalse("Sym 就能敲出 $k,L4 不该重复摆", labels.contains(k))
        }
        // 顿号 2026-09-30 也上了物理键(Sym+Z,全角态把 `\` 换成中文真用的顿号):
        // 用户「拼音模式全角的、是不需要在 L4L5 上显示的,因为 L3 能打出来」
        assertFalse("Sym+Z 就能敲出 `、`,L4 不该摆", labels.contains("、"))
        // 真正没着落的都在这儿:省略号、破折号 + 冷门的 CJK 括号/引号那一族
        for (k in listOf("…", "——", "「", "」", "『", "』", "※", "；")) {
            assertTrue("L1-L3 敲不出 $k,该在 L4", labels.contains(k))
        }
        // 引号有两把物理键(Alt+K / Alt+L)就够,不在状态行再摆
        for (k in listOf("“", "‘", "”", "’")) {
            assertFalse("引号 Alt+K/L 敲得出,L4 不该摆", labels.contains(k))
        }
        // 反证:能敲出的一个都不该上来,否则屏上两把同义的键、只有一把是活的
        for (sym in listOf("（", "）", "：", "。", "，", "？", "！", "～", "＝")) {
            assertFalse("L2/L3 已经能敲出 $sym,L4 不该重复摆", labels.contains(sym))
        }
    }

    @Test
    fun `半角态状态行上没有全角符号`() {
        val r = rig()
        r.c.fullWidthSymbolsInCn = true
        val full = r.c.l4Keys().map { it.label }
        assertTrue("全角态该有全角分号", full.contains("；"))
        assertTrue(full.contains("…"))
        // 切到半角:状态行上的字形必须跟着换 —— 摆一把全角符号在半角态是自相矛盾
        // (用户 2026-09-29「半角的时候不应该有全角符号显示在 L4-L5 上」)
        r.c.fullWidthSymbolsInCn = false
        val half = r.c.l4Keys().map { it.label }
        assertEquals("半角态分号是半角,且排最前", ";", half.first())
        for (fw in listOf("…", "——", "、", "「", "」", "『", "』", "※", "；")) {
            assertFalse("半角态不该摆全角的 $fw", half.contains(fw))
        }
        // 同一批符号的半角写法在位上
        assertTrue("半角态省略号是三个点", half.contains("..."))
        assertTrue("半角态破折号是两个连字符", half.contains("--"))
    }

    @Test
    fun `币种归物理键 状态行两态都不摆`() {
        val r = rig()
        // Sym+R 承担币种(半角/英文 `$`、拼音全角 `￥`),状态行那把核心 `$` 在拼音态退场 ——
        // 屏上不出现第二把币种键(用户 2026-09-29:「`￥` 符号 L3 的 R 已经映射了」)
        for (fw in listOf(true, false)) {
            r.c.fullWidthSymbolsInCn = fw
            val labels = r.c.l4Keys().map { it.label }
            assertFalse("全角开关=$fw 状态行都不该有 `￥`", labels.contains("￥"))
            assertFalse("全角开关=$fw 状态行都不该有 `$`", labels.contains("$"))
        }
        // 分号那把两态都在(丝印上本来就没有分号,任何一态都敲不出),字形跟着开关走
        r.c.fullWidthSymbolsInCn = true
        assertTrue(r.c.l4Keys().any { it.label == "；" })
        r.c.fullWidthSymbolsInCn = false
        assertTrue(r.c.l4Keys().any { it.label == ";" })
        // 英文态:币种同样归物理键(出厂 L3 的 R 位就是 `$`,Sym+R 敲得出)→ 不上屏;
        // 分号是半角。英文没有全角概念,不看那个开关。
        r.c.state.languageId = "en"
        assertEquals(listOf(";"), r.c.l4Keys().map { it.label })
    }

    @Test
    fun `全角开关翻两次 L4 完全复原`() {
        val r = rig()
        // 翻过去再翻回来必须一模一样 —— 状态行的键帽按 [ImeController.l4Signature] 决定重建,
        // 翻回来若键序不一致就会重建一次、还可能停在错的那份上
        val full = r.c.l4Keys().map { "${it.label}=${it.out.format()}" }
        r.c.fullWidthSymbolsInCn = false
        assertFalse("半角与全角该有差别(字形不同)", full == r.c.l4Keys().map { "${it.label}=${it.out.format()}" })
        r.c.fullWidthSymbolsInCn = true
        assertEquals(full, r.c.l4Keys().map { "${it.label}=${it.out.format()}" })
    }

    @Test
    fun `数字框里没有语言补差键`() {
        val r = rig()
        // 核心键在数字类全非法、被 [FieldProfile.l4CoreKeys] 圈掉;语言的补差键该走同一把尺,
        // 不然数字框里会摆一把 `￥`
        for (kind in listOf(
            FieldKind.NUMBER, FieldKind.NUMBER_SIGNED, FieldKind.NUMBER_DECIMAL,
            FieldKind.NUMBER_SIGNED_DECIMAL, FieldKind.DATETIME,
        )) {
            r.c.state.fieldKind = kind
            assertFalse("数字框 $kind 不该出 `￥`", r.c.l4Keys().any { it.label == "￥" })
        }
    }

    @Test
    fun `拼音组词中分节符进缓冲 查询按分节位匹配`() {
        val r = rig()
        r.type("ni")
        val before = r.c.state.candidates.map { it.word }
        assertTrue(before.isNotEmpty())
        r.c.commitL4Key(KeyOut.Text(ChineseLanguage.SEPARATOR))
        assertEquals("ni'", r.c.state.raw) // 分节符显示在组词区(xi'an 语义)
        assertTrue("加末尾分节后仍应有候选", r.c.state.candidates.isNotEmpty())
        // 内部分节符的组词缓冲:xi'an 完整拼出来
        //
        // **不断言「只中西安、不中先」**(2026-10-03 换 librime):那是旧引擎词库
        // 索引的分节位行为,现在由方案的 alphabet 规则决定,不是状态机的性质。
        // 这条测试现在守的是**状态机该守的东西**:分节符进缓冲、缓冲内容正确、
        // 组词不被打断。引擎侧的分节行为只能真机验,见 `docs/rime.md`。
        r.c.abandonComposition()
        r.type("xi")
        r.c.commitL4Key(KeyOut.Text(ChineseLanguage.SEPARATOR))
        r.type("an")
        assertEquals("xi'an", r.c.state.raw)
        assertTrue("分节拼出的 xi'an 仍有候选", r.c.state.candidates.isNotEmpty())
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
        assertEquals("[ja]", r.c.registry.language(r.c.state.languageId).statusLabel)

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
