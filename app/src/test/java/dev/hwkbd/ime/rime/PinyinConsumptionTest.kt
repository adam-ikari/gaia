package dev.hwkbd.ime.rime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「按实际选词消费」的两个纯函数。
 *
 * 这条链路上一版**一次都没生效**却看不出来:引擎层读错了字段
 * (`get_commit` 恒 null),而单测用的替身压根没走引擎 ⇒ 全绿。
 * 所以这里把「剥取」与「坐标映射」的每一步都单独钉住。
 */
class PinyinConsumptionTest {

    // ---------------- unconsumedFromPreview

    /**
     * `nihao` 选「好」:preview = `"ni好"` ⇒ 剥掉末尾的「好」⇒ 剩下 `"ni"`。
     *
     * 这是整条链路的**核心断言**:preview 里末尾那个词就是刚被吃掉的,
     * 其余是没被吃掉的(证据见 unconsumedFromPreview 的 KDoc)。
     */
    @Test
    fun `剥掉末尾的已选词 就是没被吃掉的部分`() {
        assertEquals("ni", unconsumedFromPreview("ni好", "好"))
    }

    /** 单音节:全部被吃掉 ⇒ 剩空串。空串是合法结果(上层据此清空)。 */
    @Test
    fun `单音节剥完是空串`() {
        assertEquals("", unconsumedFromPreview("你", "你"))
    }

    /**
     * 用 `lastIndexOf`:未消费的部分里若也含同名词,**末尾**那个才是刚选中的。
     *
     * 例:raw = `hao`,选中「好」,而未消费部分末尾也恰好是「好」——
     * 取第一个会把该留的吃掉。
     */
    @Test
    fun `同名词出现两次时剥末尾那个`() {
        assertEquals("好", unconsumedFromPreview("好好", "好"))
    }

    /** 剥不出来就返回 null —— 上层据此走旧路径。留错拼音比清空更糟。 */
    @Test
    fun `剥不出那个词就给 null 不猜`() {
        assertNull(unconsumedFromPreview("ni", "好"))
    }

    @Test
    fun `候选为空串时也给 null`() {
        assertNull(unconsumedFromPreview("ni", ""))
    }

    // ---------------- rawPrefixOf

    /** 无分节符时就是前 n 个字符。 */
    @Test
    fun `无分节符就是前 n 个字符`() {
        assertEquals("ni", rawPrefixOf("nihao", 2))
    }

    /**
     * 引擎坐标**不含**分节符,所以 n=2 在 `ni'hao` 里要取到 `ni` 而不是 `ni'`。
     *
     * 这正是 v0.7.7 那版的隐雷:长度直接当 raw 下标用,带分节符时必错位。
     */
    @Test
    fun `带分节符时末尾的分节符要一并去掉`() {
        assertEquals("ni", rawPrefixOf("ni'hao", 2))
    }

    @Test
    fun `分节符在中间时只数非分节符`() {
        // raw = "ni'hao":非分节符序列是 "nihao",n=4 ⇒ "ni'h"
        assertEquals("ni'h", rawPrefixOf("ni'hao", 4))
    }

    @Test
    fun `n 为 0 或负数得空串`() {
        assertEquals("", rawPrefixOf("ni'hao", 0))
        assertEquals("", rawPrefixOf("ni'hao", -3))
    }

    @Test
    fun `n 超过长度时给整个 raw`() {
        assertEquals("ni'hao", rawPrefixOf("ni'hao", 99))
    }
}
