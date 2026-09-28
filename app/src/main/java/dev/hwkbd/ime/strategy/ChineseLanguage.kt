package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.ShiftState
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/**
 * 拼音(中文):字母进组词缓冲,L2 标点按开关全角,没有大小写。
 * L4 追加分隔符 `'`——组词中插入可给连读分节(xi'an ≠ xian),查询时由 [PinyinInputMethod] 剥掉。
 */
object ChineseLanguage : Language {

    /** 拼音音节分隔符。 */
    const val SEPARATOR = "'"

    override val id = "zh"
    override val chipLabel = "拼"
    override val methodId = "pinyin"
    override val showShiftChip = false
    override val shiftTapsEnabled = false
    override val l4Extras = listOf(L4Key(SEPARATOR, KeyOut.Text(SEPARATOR)))

    /** 拼音态没有大小写一说:切入时把英文态可能挂着的锁定/一次性清掉。 */
    override fun onEnter(state: ImeState) {
        state.shift = ShiftState.OFF
    }

    override fun composes(text: String, state: ImeState): Boolean = when {
        text.length == 1 && text[0] in 'a'..'z' -> true
        // 分隔符只在组词中追加(空闲时按 `'` 就是一次普通上屏,不新开组词)
        text == SEPARATOR && state.hasComposition -> true
        else -> false
    }

    /** 中文标点:Alt(L2)层按开关全角(`.→。`,ASCII 标点全角,数字保持半角以便输号码)。 */
    override fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String {
        if (!(fullWidth && layer == Layer.L2)) return text
        return text.map { c ->
            when {
                c == '.' -> '。'
                c.code in 0x21..0x2F || c.code in 0x3A..0x40 ||
                    c.code in 0x5B..0x60 || c.code in 0x7B..0x7E -> (c.code + 0xFEE0).toChar()
                else -> c
            }
        }.joinToString("")
    }
}
