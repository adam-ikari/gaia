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

    @Test
    fun `分隔符按分节位匹配 xi'an 不中 xian`() {
        val e = engine(
            "xian\t先\t25558",
            "xi'an\t西安\t2576",
            "xianming\t贤明\t100",
        )
        // 内部分节位:xi'an 只中 xi'an 词条,xian 键的先被分节位挡掉
        assertEquals(listOf("西安"), e.query("xi'an", 10).map { it.word })
        // 前缀同样不越过分节位:xian* 的贤明续不进 xi'an
        assertTrue(e.query("xi'an", 10).none { it.word == "贤明" })
    }

    @Test
    fun `不带分隔符歧义照旧`() {
        val e = engine(
            "xian\t先\t25558",
            "xi'an\t西安\t2576",
        )
        // xian 不带分节 → 去撇相等都中,词频决定次序(先、西安都在列)
        assertEquals(listOf("先", "西安"), e.query("xian", 10).map { it.word })
    }

    @Test
    fun `带撇与不带撇的键等价匹配`() {
        val e = engine("ni'hao\t你好\t725")
        assertEquals("你好", e.query("nihao", 10).first().word) // 词库键带撇,查询不带也能中
        assertEquals("你好", e.query("ni'hao", 10).first().word)
        assertEquals("你好", e.query("ni", 10).first().word) // 前缀同样去撇
    }

    @Test
    fun `末尾分隔符约束后续前缀不约束全串`() {
        val e = engine(
            "xi\t西\t900",
            "xian\t先\t25558",
            "xi'an\t西安\t2576",
        )
        val r = e.query("xi'", 10)
        // 全串:字母串 xi 的西仍中(全串输完,末尾分节不约束);
        // 前缀:必须在分节位有边界 → 只续 xi'an 的西安,续不了 xian 的先
        assertEquals(listOf("西", "西安"), r.map { it.word })
    }

    @Test
    fun `全拼都空时按音节首字母查 sm出什么`() {
        val e = engine(
            "shen'me\t什么\t600000",
            "sheng'ming\t生命\t80000",
            "xian\t先\t25558",
        )
        // sm 不是任何拼音串(第 1-3 路全空)→ 首字母退路;词频决定退路内次序
        val r = e.query("sm", 10)
        assertEquals(listOf("什么", "生命"), r.map { it.word })
        assertEquals(CandidateKind.INIT_EXACT, r.first().kind)
    }

    @Test
    fun `首字母精确优先于更长首字母`() {
        val e = engine(
            "shen'me\t什么\t600000",
            "shen'me'shi\t什么时候\t500000",
        )
        val r = e.query("sm", 10)
        assertEquals(listOf("什么", "什么时候"), r.map { it.word })
        assertEquals(CandidateKind.INIT_PREFIX, r[1].kind)
    }

    @Test
    fun `全拼能命中时不走首字母退路`() {
        val e = engine(
            "ni\t你\t500",
            "ni'hao\t你好\t725",
            "na'i\t泥\t5", // 首字母 ni:全拼命中时不允许退路结果混入
        )
        val r = e.query("ni", 10)
        assertEquals("你", r.first().word)
        assertTrue(r.none {
            it.kind == CandidateKind.INIT_EXACT || it.kind == CandidateKind.INIT_PREFIX
        })
    }

    @Test
    fun `首字母查询剥掉输入里的分节符`() {
        val e = engine("shen'me\t什么\t600000")
        assertEquals(listOf("什么"), e.query("s'm", 10).map { it.word })
    }
}
