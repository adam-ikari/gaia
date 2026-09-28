package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.ShiftState
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/**
 * 拼音(中文):字母进组词缓冲,L2 标点按开关全角,没有大小写。
 * 分节符走 **`v` 键**([mapText]:v → `'`,组词中进缓冲给连读分节 **xi'an ≠ xian**,查询时
 * [PinyinInputMethod] 原样交给引擎、按分节位约束匹配——`xi'an` 只中西安、不中先;
 * 空闲时按 v 就是一次普通上屏),**状态行 L4 不再放分隔符键**——
 * 屏上不占位,v 顺手在字母区。注意:v 记分节符后,ü 词(女/绿,词库 v 记 ü)靠 n/l 前缀候选列出。
 */
object ChineseLanguage : Language {

    /** 拼音音节分隔符。 */
    const val SEPARATOR = "'"

    override val id = "zh"
    override val chipLabel = "拼"
    override val methodId = "pinyin"
    override val showShiftChip = false
    override val shiftTapsEnabled = false
    override val l4Extras: List<L4Key> = emptyList() // 分节符走 v 键,不占 L4

    /** 切入拼音时的收尾:清掉英文态可能挂着的锁定/一次性。 */
    override fun onEnter(state: ImeState) {
        state.shift = ShiftState.OFF
    }

    /** v 键 = 分节符(在字母区,组词中顺手);其余文本原样。 */
    override fun mapText(text: String, state: ImeState): String =
        if (text == "v") SEPARATOR else text

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
