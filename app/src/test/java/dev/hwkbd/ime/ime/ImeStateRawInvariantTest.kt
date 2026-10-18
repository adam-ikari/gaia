package dev.hwkbd.ime.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [ImeState.appendRaw] 的**分隔符不变量**(2026-10-17 喂撇定案的坐标恒等性前提):
 *
 * raw 里 `'` **不开头、不连续** —— 这条不成立,喂引擎的串(经
 * `PinyinInputMethod.normalize` 折叠)就会与 state.raw 不同,引擎坐标
 * (consumed / `SyllableSpelling.start|end`)便不能再当 raw 的**直接下标**用,
 * `rawAfterConsuming` / `displaySpelling` 两处消费/显示会一起错位。
 * 所以入口吞基线在这里钉死;字母与混合追加不受去重影响。
 */
class ImeStateRawInvariantTest {

    @Test
    fun `appendRaw normal letters accumulate`() {
        val s = ImeState()
        s.appendRaw("n"); s.appendRaw("ih"); s.appendRaw("ao")
        assertEquals("nihao", s.raw)
    }

    @Test
    fun `separator is swallowed when it would start the buffer`() {
        val s = ImeState()
        // 组词外的 `'` 本来就被 composes 门拦住,这里钉 appendRaw 自己的兜底
        s.appendRaw("'")
        assertEquals("", s.raw)
    }

    @Test
    fun `separator works mid-composition`() {
        val s = ImeState()
        s.appendRaw("ni"); s.appendRaw("'")
        assertEquals("ni'", s.raw)
    }

    @Test
    fun `consecutive separators collapse to one boundary`() {
        val s = ImeState()
        s.appendRaw("ni")
        s.appendRaw("'"); s.appendRaw("'")
        assertEquals("ni'", s.raw)
    }

    @Test
    fun `letter after separator resets the no-double state`() {
        val s = ImeState()
        s.appendRaw("ni"); s.appendRaw("'")
        s.appendRaw("h"); s.appendRaw("'")
        assertEquals("ni'h'", s.raw)
    }

    @Test
    fun `multi-char append is not deduplicated`() {
        val s = ImeState()
        s.appendRaw("ni'a")
        assertEquals("ni'a", s.raw)
    }

    @Test
    fun `dropLastRaw can re-expose the separator then append again`() {
        val s = ImeState()
        s.appendRaw("ni"); s.appendRaw("'")
        s.dropLastRaw()
        s.appendRaw("'")
        assertEquals("ni'", s.raw)
    }
}
