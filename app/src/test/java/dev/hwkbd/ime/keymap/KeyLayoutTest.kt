package dev.hwkbd.ime.keymap

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLayoutTest {

    private val d = KeyLayout.default()

    @Test
    fun `L2 符号数字层默认映射与 TitanKey 一致`() {
        // 九宫格数字:W=1 E=2 R=3 / S=4 D=5 F=6 / X=7 C=8 V=9,Q=0
        assertEquals(KeyOut.Text("0"), d.lookup(Layer.L2, KeyEvent.KEYCODE_Q))
        assertEquals(KeyOut.Text("1"), d.lookup(Layer.L2, KeyEvent.KEYCODE_W))
        assertEquals(KeyOut.Text("2"), d.lookup(Layer.L2, KeyEvent.KEYCODE_E))
        assertEquals(KeyOut.Text("3"), d.lookup(Layer.L2, KeyEvent.KEYCODE_R))
        assertEquals(KeyOut.Text("4"), d.lookup(Layer.L2, KeyEvent.KEYCODE_S))
        assertEquals(KeyOut.Text("5"), d.lookup(Layer.L2, KeyEvent.KEYCODE_D))
        assertEquals(KeyOut.Text("6"), d.lookup(Layer.L2, KeyEvent.KEYCODE_F))
        assertEquals(KeyOut.Text("7"), d.lookup(Layer.L2, KeyEvent.KEYCODE_X))
        assertEquals(KeyOut.Text("8"), d.lookup(Layer.L2, KeyEvent.KEYCODE_C))
        assertEquals(KeyOut.Text("9"), d.lookup(Layer.L2, KeyEvent.KEYCODE_V))
        // 右区标点(本机无独立标点键)
        assertEquals(KeyOut.Text("@"), d.lookup(Layer.L2, KeyEvent.KEYCODE_A))
        assertEquals(KeyOut.Text("("), d.lookup(Layer.L2, KeyEvent.KEYCODE_T))
        assertEquals(KeyOut.Text(")"), d.lookup(Layer.L2, KeyEvent.KEYCODE_Y))
        assertEquals(KeyOut.Text(","), d.lookup(Layer.L2, KeyEvent.KEYCODE_N))
        assertEquals(KeyOut.Text("."), d.lookup(Layer.L2, KeyEvent.KEYCODE_B))
        assertEquals(KeyOut.Text("?"), d.lookup(Layer.L2, KeyEvent.KEYCODE_M))
        assertEquals(KeyOut.Text("/"), d.lookup(Layer.L2, KeyEvent.KEYCODE_O))
        assertEquals(KeyOut.Text(":"), d.lookup(Layer.L2, KeyEvent.KEYCODE_P))
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
    fun `L4 只该显示 L1-L3 敲不出的键`() {
        val p = d.producible()
        // L1/L2/L3 各有代表:字母、L2 的 !、L3 的功能键
        assertTrue(p.contains("a"))
        assertTrue(p.contains("!"))
        assertTrue(p.contains("key:esc"))
        // 固定核心键全部落在 L1-L3 之外(否则中区就该被筛掉)
        for (k in KeyLayout.L4_CORE) {
            assertTrue("core ${k.label}", !p.contains(k.out.format()))
        }
        // 自定义键若是 L1-L3 已有的取值,会被筛掉
        assertTrue(p.contains(KeyOut.Text("!").format()))
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
}
