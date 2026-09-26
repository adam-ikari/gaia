package dev.hwkbd.ime.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PinyinEngineTest {

    private fun engine(vararg lines: String): PinyinEngine {
        val dict = PinyinDict()
        dict.load(lines.toList())
        return PinyinEngine(dict, UserDict())
    }

    @Test
    fun `空词库返回空`() {
        val e = engine()
        assertTrue(e.query("ni", 10).isEmpty())
    }

    @Test
    fun `精确命中排最前`() {
        val e = engine(
            "ni\t你\t500",
            "nihao\t你好\t300",
            "nihaoshijie\t你好世界\t5",
        )
        val r = e.query("nihao", 10)
        assertEquals("你好", r.first().word)
        assertEquals(CandidateKind.EXACT, r.first().kind)
        // 前缀命中(拼音比输入长)随后
        assertEquals("你好世界", r[1].word)
        assertEquals(CandidateKind.PREFIX, r[1].kind)
    }

    @Test
    fun `短输入的前缀与排序`() {
        val e = engine(
            "ni\t你\t500",
            "nihao\t你好\t300",
            "nihaoshijie\t你好世界\t5",
        )
        val r = e.query("ni", 10)
        assertEquals(listOf("你", "你好", "你好世界"), r.map { it.word })
    }

    @Test
    fun `无整词条时 DP 分段组词`() {
        val e = engine(
            "ni\t你\t500",
            "hao\t好\t400",
            "shi\t世\t100",
            "jie\t界\t90",
            "shijie\t世界\t250",
        )
        val r = e.query("nihaoshijie", 10)
        assertEquals("你好世界", r.first().word)
        assertEquals(CandidateKind.SEGMENT, r.first().kind)
    }

    @Test
    fun `有整词条时优先整词`() {
        val e = engine(
            "ni\t你\t500",
            "hao\t好\t400",
            "shijie\t世界\t250",
            "nihaoshijie\t你好世界\t5",
        )
        val r = e.query("nihaoshijie", 10)
        assertEquals("你好世界", r.first().word)
    }

    @Test
    fun `用户频次把用过的词顶到最前`() {
        val e = engine(
            "ni\t你\t500",
            "nihao\t你好\t300",
        )
        assertEquals("你", e.query("ni", 10).first().word)
        e.onCommitted("你好")
        assertEquals("你", e.query("ni", 10).first().word) // 一次使用不足以跨档
        e.onCommitted("你好")
        assertEquals("你好", e.query("ni", 10).first().word) // 两次后跨档
    }

    @Test
    fun `高频前缀词不压过精确命中`() {
        val e = engine(
            "ni\t你\t234587",
            "nian\t年\t734437",
            "niandai\t年代\t8000",
        )
        val r = e.query("ni", 10)
        assertEquals(listOf("你", "年", "年代"), r.map { it.word })
        assertEquals(CandidateKind.EXACT, r.first().kind)
    }

    @Test
    fun `导入词条参与查询`() {
        val e = engine("ni\t你\t500")
        e.user.addEntry("coco", "可可", 10)
        val r = e.query("coco", 10)
        assertEquals(listOf("可可"), r.map { it.word })
    }

    @Test
    fun `结果条数受 limit 约束`() {
        val e = engine(
            "ni\t你\t500",
            "nihao\t你好\t300",
            "nihaoshijie\t你好世界\t5",
        )
        assertEquals(2, e.query("ni", 2).size)
    }
}
