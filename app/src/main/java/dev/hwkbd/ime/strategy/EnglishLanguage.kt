package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/** 英文:直接上屏,跟随大写状态;有大小写状态与 Shift 敲击。 */
object EnglishLanguage : Language {

    override val id = "en"
    override val statusLabel = "En"
    override val methodId = "direct"
    override val showShift = true
    override val shiftTapsEnabled = true

    /**
     * 核心键的分号在英文态就该是**半角 `;`**,键帽也得画半角。
     *
     * 出厂核心键的键帽是全角 `;` 而输出是半角(键帽骗人,2026-09-29 修);中文态由
     * [ChineseLanguage.coreKeys] 换成全角那一把,英文态就在这里换回半角 ——
     * 两边键帽与输出都对得上,不用"将就哪边"。**英文没有全角概念**,所以不看那个开关。
     */
    override fun coreKeys(ins: List<L4Key>, fullWidth: Boolean): List<L4Key> =
        ins.map { if (it.label == "；") L4Key(";", KeyOut.Text(";")) else it }

    override fun composes(text: String, state: ImeState): Boolean = false

    override fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String =
        if (upper) text.uppercase() else text
}
