package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/** 英文:直接上屏,跟随大写状态;有大写芯片与 Shift 敲击。 */
object EnglishLanguage : Language {

    override val id = "en"
    override val chipLabel = "En"
    override val methodId = "direct"
    override val showShiftChip = true
    override val shiftTapsEnabled = true
    override val l4Extras: List<L4Key> = emptyList()

    override fun composes(text: String, state: ImeState): Boolean = false

    override fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String =
        if (upper) text.uppercase() else text
}
