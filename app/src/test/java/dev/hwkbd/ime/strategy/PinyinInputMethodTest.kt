package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.engine.UserDict
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拼音策略层:查询前只做归一(滤杂质、收敛分隔符),**分隔符原样进引擎**——
 * 词库键带音节分隔符,xi'an ≠ xian 由引擎的分节位约束兑现。
 */
class PinyinInputMethodTest {

    private fun method(vararg lines: String): PinyinInputMethod {
        val dict = PinyinDict()
        dict.load(lines.toList())
        return PinyinInputMethod(PinyinEngine(dict, UserDict()))
    }

    @Test
    fun `查询前归一 滤杂质并收敛分隔符`() {
        assertEquals("xi'an", PinyinInputMethod.normalize("xi''an")) // 连续分节并成一个
        assertEquals("xi'an", PinyinInputMethod.normalize("'xi'an")) // 开头分节无意义,丢掉
        assertEquals("xi'an'", PinyinInputMethod.normalize("xi'an'")) // 末尾分节有语义,保留
        assertEquals("xian", PinyinInputMethod.normalize("xi-an 3")) // 非字母非分隔符滤掉
    }

    @Test
    fun `分隔符透传引擎 xi'an 只中西安`() {
        val m = method("xian\t先\t25558", "xi'an\t西安\t2576")
        assertEquals(listOf("西安"), m.query("xi'an", 10).map { it.word })
        assertEquals(listOf("先", "西安"), m.query("xian", 10).map { it.word })
    }
}
