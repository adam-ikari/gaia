package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/**
 * 语言策略:一种语言的全部行为集中在一个实现里,新增语言只需 [ImeRegistry.registerLanguage]
 * 注册,核心状态机([dev.hwkbd.ime.ime.ImeController])与 UI 一行都不用改。
 */
interface Language {

    /** 唯一 id,[ImeState.languageId] 记的就是它。 */
    val id: String

    /** 状态行语言芯片文字(拼 / En)。 */
    val chipLabel: String

    /** 绑定的输入法 id,由 [ImeRegistry.method] 解析。 */
    val methodId: String

    /** 是否显示大写芯片(拼音不需要大小写)。 */
    val showShiftChip: Boolean

    /** Shift 敲击是否生效(单击大写一次 / 双击 Caps)。 */
    val shiftTapsEnabled: Boolean

    /** 该语言在状态行 L4 追加的键(拼音:分隔符 `'`,用于 xi'an 分节)。 */
    val l4Extras: List<L4Key>

    /** 切入该语言时的收尾钩子(拼音进来清掉英文态的大写锁定)。 */
    fun onEnter(state: ImeState) {}

    /** 这段文本是否进组词缓冲;[state] 供策略按当前组词状态判定(分隔符只在组词中追加)。 */
    fun composes(text: String, state: ImeState): Boolean

    /** 不进组词缓冲的文本如何上屏:大小写([upper])与全角([fullWidth],配合 [layer])由策略自己取舍。 */
    fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String
}
