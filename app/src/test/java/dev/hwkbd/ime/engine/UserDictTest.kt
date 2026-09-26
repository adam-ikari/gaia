package dev.hwkbd.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserDictTest {

    @Test
    fun `导入行三种形态`() {
        val u = UserDict()
        assertTrue(u.importLine("可可\tcoco\t5"))
        assertTrue(u.importLine("可可\tkeke"))
        assertFalse(u.importLine("可可")) // 无拼音被拒
        assertFalse(u.importLine("可可\tCOCO")) // 拼音含大写被拒
        assertEquals(2, u.entryCount)
    }

    @Test
    fun `JSON 往返`() {
        val u = UserDict()
        u.bump("你好")
        u.bump("你好")
        u.bump("世界")
        u.addEntry("coco", "可可", 5)
        u.addEntry("ni", "你", 1)
        val json = u.toJson()

        val v = UserDict()
        assertTrue(v.loadJson(json))
        assertEquals(2, v.count("你好"))
        assertEquals(1, v.count("世界"))
        assertEquals(listOf("可可" to 5), v.exactEntries("coco"))
        assertEquals(2, v.entryCount)
    }

    @Test
    fun `损坏 JSON 拒绝且保持原状`() {
        val u = UserDict()
        u.addEntry("ni", "你", 1)
        assertFalse(u.loadJson("{broken"))
        assertEquals(1, u.entryCount)
    }

    @Test
    fun `前缀查询`() {
        val u = UserDict()
        u.addEntry("ni", "你", 10)
        u.addEntry("nihao", "你好", 20)
        val r = u.prefixEntries("ni")
        assertEquals(2, r.size)
    }

    @Test
    fun `删除用户词清除频次与词条`() {
        val u = UserDict()
        u.bump("你好")
        u.addEntry("coco", "可可", 5)
        assertTrue(u.removeWord("你好"))
        assertTrue(u.removeWord("可可"))
        assertEquals(0, u.count("你好"))
        assertEquals(0, u.entryCount)
        assertFalse(u.removeWord("没词")) // 不存在的词返回 false
    }
}

class MiniJsonTest {

    @Test
    fun `字符串转义往返`() {
        val s = "a\"b\\c\nd\u0001"
        val json = MiniJson.write(mapOf("k" to s))
        @Suppress("UNCHECKED_CAST")
        val back = MiniJson.parse(json) as Map<String, String>
        assertEquals(s, back["k"])
    }

    @Test
    fun `嵌套结构往返`() {
        val v: Any = linkedMapOf(
            "list" to listOf(1L, 2L, "x", true, null),
            "obj" to mapOf("n" to -5L),
        )
        assertEquals(v, MiniJson.parse(MiniJson.write(v)))
    }
}
