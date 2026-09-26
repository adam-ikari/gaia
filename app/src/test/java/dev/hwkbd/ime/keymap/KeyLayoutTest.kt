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
    fun `L2 符号数字层默认映射`() {
        assertEquals(KeyOut.Text("1"), d.lookup(Layer.L2, KeyEvent.KEYCODE_Q))
        assertEquals(KeyOut.Text("0"), d.lookup(Layer.L2, KeyEvent.KEYCODE_P))
        assertEquals(KeyOut.Text("!"), d.lookup(Layer.L2, KeyEvent.KEYCODE_A))
        assertEquals(KeyOut.Text(")"), d.lookup(Layer.L2, KeyEvent.KEYCODE_SEMICOLON))
    }

    @Test
    fun `L3 编程层默认映射`() {
        assertEquals(KeyOut.Text("{"), d.lookup(Layer.L3, KeyEvent.KEYCODE_Q))
        assertEquals(KeyOut.Text("}"), d.lookup(Layer.L3, KeyEvent.KEYCODE_W))
        assertEquals(KeyOut.Text("|"), d.lookup(Layer.L3, KeyEvent.KEYCODE_L))
        assertEquals(KeyOut.Text("->"), d.lookup(Layer.L3, KeyEvent.KEYCODE_SPACE))
        assertEquals(KeyOut.Text("\\"), d.lookup(Layer.L3, KeyEvent.KEYCODE_Z))
    }

    @Test
    fun `L1 字母与标点默认映射`() {
        assertEquals(KeyOut.Text("a"), d.lookup(Layer.L1, KeyEvent.KEYCODE_A))
        assertEquals(KeyOut.Text(","), d.lookup(Layer.L1, KeyEvent.KEYCODE_COMMA))
    }

    @Test
    fun `L2 未映射的键回落 L1`() {
        assertEquals(KeyOut.Text("z"), d.lookup(Layer.L2, KeyEvent.KEYCODE_Z))
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
}
