package dev.hwkbd.ime.keymap

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutTest {

    private val d = KeyLayout.default()

    @Test
    fun `L2 符号数字层默认映射逐键对齐 TitanKey kcm 丝印`() {
        // 2026-09-29 用户定案:L2 必须**严格按 Titan 2 物理键盘丝印**,即仓库根目录 `.TitanKey.kcm`
        // 每个 key 的 `alt:` 值(该文件只有 A-Z + SPACE/ENTER,26 个字母键全列,逐键断言防再错)。
        // 此前唯一的错项:`l` 印的是撇号,代码写成了反斜杠(2026-09-29 用户纠正)。
        val kcmAlt = mapOf(
            'A' to "@", 'B' to ".", 'C' to "8", 'D' to "5", 'E' to "2", 'F' to "6",
            'G' to "*", 'H' to "#", 'I' to "_", 'J' to "+", 'K' to "\"", 'L' to "'",
            'M' to "?", 'N' to ",", 'O' to "/", 'P' to ":", 'Q' to "0", 'R' to "3",
            'S' to "4", 'T' to "(", 'U' to "-", 'V' to "9", 'W' to "1", 'X' to "7",
            'Y' to ")", 'Z' to "!",
        )
        for ((letter, symbol) in kcmAlt) {
            assertEquals(
                "L2 的 $letter 键应按 kcm 丝印输出 $symbol",
                KeyOut.Text(symbol),
                d.lookup(Layer.L2, KeyEvent.KEYCODE_A + (letter - 'A')),
            )
        }
        // 丝印上**没有反斜杠**:L2 全层 26 键都不该出现 `\`
        assertFalse(kcmAlt.containsValue("\\"))
    }

    @Test
    fun `L3 编程层默认映射与可可一致`() {
        assertEquals(KeyOut.Special("esc"), d.lookup(Layer.L3, KeyEvent.KEYCODE_Q))
        assertEquals(KeyOut.Special("up"), d.lookup(Layer.L3, KeyEvent.KEYCODE_W))
        assertEquals(KeyOut.Text("~"), d.lookup(Layer.L3, KeyEvent.KEYCODE_E))
        assertEquals(KeyOut.Text("©"), d.lookup(Layer.L3, KeyEvent.KEYCODE_R))
        assertEquals(KeyOut.Text("<"), d.lookup(Layer.L3, KeyEvent.KEYCODE_T))
        assertEquals(KeyOut.Text(">"), d.lookup(Layer.L3, KeyEvent.KEYCODE_Y))
        assertEquals(KeyOut.Special("pgup"), d.lookup(Layer.L3, KeyEvent.KEYCODE_I))
        assertEquals(KeyOut.Special("tab"), d.lookup(Layer.L3, KeyEvent.KEYCODE_P))
        assertEquals(KeyOut.Special("left"), d.lookup(Layer.L3, KeyEvent.KEYCODE_A))
        assertEquals(KeyOut.Special("move_home"), d.lookup(Layer.L3, KeyEvent.KEYCODE_K))
        assertEquals(KeyOut.Special("move_end"), d.lookup(Layer.L3, KeyEvent.KEYCODE_L))
        assertEquals(KeyOut.Special("insert"), d.lookup(Layer.L3, KeyEvent.KEYCODE_M))
        assertEquals(KeyOut.Text("["), d.lookup(Layer.L3, KeyEvent.KEYCODE_G))
        assertEquals(KeyOut.Text("]"), d.lookup(Layer.L3, KeyEvent.KEYCODE_H))
        assertEquals(KeyOut.Text("{"), d.lookup(Layer.L3, KeyEvent.KEYCODE_V))
        assertEquals(KeyOut.Text("}"), d.lookup(Layer.L3, KeyEvent.KEYCODE_B))
        assertEquals(KeyOut.Text("\\"), d.lookup(Layer.L3, KeyEvent.KEYCODE_Z))
        assertEquals(KeyOut.Text("="), d.lookup(Layer.L3, KeyEvent.KEYCODE_N))
        // 空格/回车/删除在 L3 保持原功能
        assertNull(d.lookup(Layer.L3, KeyEvent.KEYCODE_SPACE))
    }

    @Test
    fun `L1 只有字母,标点全在 L2`() {
        assertEquals(KeyOut.Text("a"), d.lookup(Layer.L1, KeyEvent.KEYCODE_A))
        assertNull(d.lookup(Layer.L1, KeyEvent.KEYCODE_COMMA))
    }

    @Test
    fun `L2 未映射的键回落 L1`() {
        // 默认表 L2 覆盖了全部 26 字母,用自建布局验证回落逻辑
        val parsed = KeyLayout.parseText("[L2]\nq=1")
        assertEquals(KeyOut.Text("1"), parsed.layout!!.lookup(Layer.L2, KeyEvent.KEYCODE_Q))
        assertEquals(KeyOut.Text("z"), parsed.layout!!.lookup(Layer.L2, KeyEvent.KEYCODE_Z))
    }

    @Test
    fun `文本格式往返一致`() {
        val text = d.formatText()
        val parsed = KeyLayout.parseText(text)
        assertNotNull(parsed.layout)
        assertTrue(parsed.errors.isEmpty())
        for (layer in Layer.values()) {
            for ((code, out) in d.mapFor(layer)) {
                assertEquals("layer=$layer code=$code", out, parsed.layout!!.lookup(layer, code))
            }
        }
    }

    @Test
    fun `功能键写法 key 冒号`() {
        val parsed = KeyLayout.parseText("[L3]\nesc=key:esc\nf5=key:f5")
        assertTrue(parsed.errors.isEmpty())
        assertEquals(KeyOut.Special("esc"), parsed.layout!!.lookup(Layer.L3, KeyEvent.KEYCODE_ESCAPE))
    }

    @Test
    fun `非法行报告行号`() {
        val parsed = KeyLayout.parseText("[L2]\nqq=1\nw=2\nbad")
        assertNull(parsed.layout)
        assertEquals(listOf(2, 4), parsed.errors)
    }

    @Test
    fun `L4 固定核心键永远在前 自定义键在后 重复去重`() {
        // 核心键内容定点:； 与 $(2026-09-29 用户定案:`\` 从固定键删除——L3 的 Sym+z 能敲出它)
        assertEquals(listOf("；", "\$"), KeyLayout.L4_CORE.map { it.label })
        // 编辑器里 [L4] 段空/被删光,固定核心键照样显示
        assertEquals(
            KeyLayout.L4_CORE.map { it.label },
            KeyLayout(emptyMap()).l4Combined().map { it.label },
        )
        // 自定义键追加在核心键之后;与核心重复的只留一个
        val combined = KeyLayout(
            emptyMap(),
            listOf(
                L4Key("foo", KeyOut.Text("foo")),
                L4Key("；", KeyOut.Text(";")), // 与核心键重复
            ),
        ).l4Combined()
        assertEquals(KeyLayout.L4_CORE.size + 1, combined.size)
        assertEquals(
            KeyLayout.L4_CORE.map { it.label },
            combined.take(KeyLayout.L4_CORE.size).map { it.label },
        )
        assertEquals("foo", combined.last().label)
        // 默认布局 = 核心键(去重后与核心完全一致)
        assertEquals(KeyLayout.L4_CORE.map { it.label }, d.l4Combined().map { it.label })
    }

    @Test
    fun `L4 上下文键位套按字面返回 核心键并入由注册表决定`() {
        // 出厂网址套 = 字面三把(不带 `/`——那是 L2 的键帽);并入哪些核心键由 FieldProfile.l4CoreKeys 决定
        assertEquals(
            listOf("http://", "https://", ".com"),
            d.l4Combined(KeyLayout.L4_PROFILE_URL).map { it.label },
        )
        // 邮件套 = 字面键序(短域名后缀优先在前、完整域名在后——L4 优先显示后缀;
        // 本布局默认 l4CoreKeys 空,不带 `$`)
        assertEquals(
            listOf(
                ".com", ".net", ".org", ".cn", ".edu",
                "@gmail.com", "@hotmail.com", "@outlook.com",
            ),
            d.l4Combined(KeyLayout.L4_PROFILE_EMAIL).map { it.label },
        )
        // 编辑器写了 [L4 url] 段 → 该段字面键(配置什么显示什么,程序不筛选)
        val parsed = KeyLayout.parseText(
            """
            [L2]
            q=1
            [L4]
            foo=foo
            [L4 url]
            http://=http://
            """.trimIndent(),
        )
        assertTrue(parsed.errors.isEmpty())
        assertEquals(
            listOf("http://"),
            parsed.layout!!.l4Combined(KeyLayout.L4_PROFILE_URL).map { it.label },
        )
        // 默认套不受上下文套影响(核心键 + 自定义键)
        assertEquals(
            KeyLayout.L4_CORE.map { it.label } + "foo",
            parsed.layout!!.l4Combined().map { it.label },
        )
    }

    @Test
    fun `格式文本往返带上下文键位套 缺段回落出厂预置`() {
        val text = d.formatText()
        assertTrue(text.contains("[L4 ${KeyLayout.L4_PROFILE_URL}]"))
        val parsed = KeyLayout.parseText(text)
        assertTrue(parsed.errors.isEmpty())
        assertEquals(
            d.l4Combined(KeyLayout.L4_PROFILE_URL),
            parsed.layout!!.l4Combined(KeyLayout.L4_PROFILE_URL),
        )
        // 老 keymap.txt 没有 [L4 url] 段 → 回落出厂预置,网址键照样拿得到
        val legacy = KeyLayout.parseText("[L2]\nq=1")
        assertEquals(
            KeyLayout.default().l4Combined(KeyLayout.L4_PROFILE_URL),
            legacy.layout!!.l4Combined(KeyLayout.L4_PROFILE_URL),
        )
    }

    @Test
    fun `格式文本往返后 L4 键原样回来 不多不少`() {
        // 回归:以前 `!=` 标签写成 `!==!=`,再解析成 标签`!`+输出`=!=`,
        // 和核心键对不上 → l4Combined 多出一把坏键(状态行凭空多一个键帽)。
        val parsed = KeyLayout.parseText(d.formatText())
        assertTrue(parsed.errors.isEmpty())
        assertEquals(d.l4Combined(), parsed.layout!!.l4Combined())
        assertEquals(KeyLayout.L4_CORE.size, parsed.layout!!.l4Combined().size)
    }

    @Test
    fun `L4 标签里带等号也能往返`() {
        val kl = KeyLayout(
            emptyMap(),
            listOf(L4Key("!=", KeyOut.Text("!=")), L4Key("a=b", KeyOut.Text("x"))),
        )
        val parsed = KeyLayout.parseText(kl.formatText())
        assertTrue(parsed.errors.isEmpty())
        assertEquals(kl.l4Combined(), parsed.layout!!.l4Combined())
    }

    @Test
    fun `producible 收本机三层输出 键位表换了就跟着变`() {
        val d = KeyLayout.default()
        // 出厂键位:L2 的符号(`- / : . + * #`)与 L1 字母都算"L1-L3 已有的"
        val p = d.producible()
        for (s in listOf("-", "/", ":", ".", "+", "*", "#", "a")) assertTrue(s in p)
        // 短语组合按不出(物理键一次只出一个单字符)
        for (s in listOf("-/", "+86", "-.")) assertFalse(s in p)
        // 自定义键位表:某层没有的键就不算 producible —— 筛随本机键位走(换手机自动适配)
        val custom = KeyLayout.parseText("[L2]\nq=0").layout!!
        assertFalse("-" in custom.producible())
        assertTrue("a" in custom.producible()) // 缺 [L2]/[L3] 段时回落默认 L1
    }
}
