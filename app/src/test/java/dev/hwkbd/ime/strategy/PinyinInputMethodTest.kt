package dev.hwkbd.ime.strategy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 拼音策略层:查询前只做归一(滤杂质、收敛分隔符)。
 *
 * ## 为什么只剩归一这一件事可测(2026-10-03 换 librime)
 *
 * 原先这里有两条测试,第二条是「分隔符透传引擎,`xi'an` 只中西安」——
 * 它验的是**引擎的分节位约束**:带分节的 `xi'an` 不该命中不带分节的 `xian` 词条。
 *
 * 换 librime 之后那段逻辑在 C++ 里(方案自己的 speller/alphabet 规则),JVM 上跑不了,
 * 所以**这条测试无法保留**。它也不该保留:那本来是「词典怎么建索引」的实现细节,
 * 不是「策略层要做什么」。策略层的职责只有一个 —— **归一,并把归一后的串原样交给引擎**。
 *
 * 分节能力本身现在是 `assets/rime/luna_pinyin.schema.yaml` 的 `alphabet` 规则,
 * 只能在真机上验。见 `docs/rime.md`。
 */
class PinyinInputMethodTest {

    @Test
    fun `查询前归一 滤杂质并收敛分隔符`() {
        assertEquals("xi'an", PinyinInputMethod.normalize("xi''an")) // 连续分节并成一个
        assertEquals("xi'an", PinyinInputMethod.normalize("'xi'an")) // 开头分节无意义,丢掉
        assertEquals("xi'an'", PinyinInputMethod.normalize("xi'an'")) // 末尾分节有语义,保留
        assertEquals("xian", PinyinInputMethod.normalize("xi-an 3")) // 非字母非分隔符滤掉
    }

    @Test
    fun `归一后的串才交给引擎 不是原始输入`() {
        // 这条替换掉原来那条「分节能不能匹配到词」的测试:分节解析归引擎了,
        // 但**归一必须发生**这件事仍是策略层的责任,而且是可测的。
        // librime 不认识 `'`,所以如果这里把原始串送过去,引擎会把它当普通字符。
        val fake = FakePinyinInputMethod(table = mapOf("xian" to listOf("先")))
        val m = PinyinInputMethod.forTest(fake)
        m.query("xi-an 3", 10)
        assertEquals("引擎收到的是归一后的串", listOf("xian"), fake.queried)
    }

    @Test
    fun `分隔符保留在归一结果里`() {
        // 末尾 `'` 表示"本音节到此为止",语义上要传给引擎,由引擎的分节规则兑现
        val fake = FakePinyinInputMethod(table = mapOf("xi'" to listOf("西")))
        PinyinInputMethod.forTest(fake).query("xi'", 10)
        assertEquals(listOf("xi'"), fake.queried)
    }

    @Test
    fun `不支持逐词删除 时接口如实报告`() {
        // librime 的用户词频在 LevelDB 里,没有删单个词的 API。
        // 这里钉住的是「**不许假装成功**」—— 返回 true 会让调用方告诉用户"删掉了",
        // 而词还在,下次输出来又冒出来,比没有这个入口更糟。
        val m = PinyinInputMethod.forTest(FakePinyinInputMethod())
        assertEquals(false, m.supportsWordRemoval)
        assertEquals(false, m.removeUserWord("你好"))
    }

    /**
     * 装饰器必须把 `isReady` 转发给引擎(2026-10-04)。
     *
     * 漏了转发时接口默认值 `true` 会生效 ⇒ 状态机的就绪门永远不触发 ⇒
     * 候选行照旧「清空↔填满」地闪。生产注册的就是这一层装饰器,
     * 所以只测裸引擎是测不到这个 bug 的。
     */
    @Test
    fun `引擎没就绪 装饰器也要如实报告`() {
        val method = PinyinInputMethod.forTest(FakePinyinInputMethod.common().withReady(false))
        assertFalse("装饰器不能拿接口默认的 true 糊弄过去", method.isReady)
    }

    /** 引擎就绪时照实报 true —— 别把上一条修成永远 false。 */
    @Test
    fun `引擎就绪 装饰器照实报告`() {
        assertTrue(PinyinInputMethod.forTest(FakePinyinInputMethod.common().withReady(true)).isReady)
    }

    /** 选后剩余拼音也要转发(拼音消费按段那条依赖它)。 */
    @Test
    fun `选后剩余拼音经装饰器转发`() {
        val engine = FakePinyinInputMethod.common().withRemainAfterSelect("hao")
        // 替身的 `selectAndRemain` 靠 `queried` 里最后一次查询来定位候选,
        // 所以得先真查一次(这是替身的实现约定,不是被测行为)。
        assertTrue("前置条件:引擎真的查得到候选", engine.query("ni", 5).isNotEmpty())
        val r = PinyinInputMethod.forTest(engine).selectAndRemain(0, "你")
        assertEquals("预览串应是「未消费 + 已选词」", "hao你", r?.preview)
        assertEquals("剥掉已选词后剩下", "hao", r?.unconsumed)
    }
}
