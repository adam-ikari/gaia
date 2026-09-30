package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/**
 * 语言策略:一种语言的全部行为集中在一个实现里,新增语言只需 [ImeRegistry.registerLanguage]
 * 注册,核心状态机([dev.hwkbd.ime.ime.ImeController])与 UI 一行都不用改。
 */
interface Language {

    /** 唯一 id,[ImeState.languageId] 记的就是它。 */
    val id: String

    /** 状态行上语言那一项的文字(拼 / En)。 */
    val statusLabel: String

    /** 绑定的输入法 id,由 [ImeRegistry.method] 解析。 */
    val methodId: String

    /** 是否显示大小写状态(拼音不需要大小写)。 */
    val showShift: Boolean

    /** Shift 敲击是否生效(单击大写一次 / 双击 Caps)。 */
    val shiftTapsEnabled: Boolean

    /**
     * 该语言在状态行 L4 追加的键(排在核心键与键位套**之后**;拼音的分节符走 `v` 键,不占 L4)。
     *
     * [fullWidth] 是设置里的「中文全角」开关 —— **追加的键必须跟着它走**:半角态的状态行上
     * 摆一把全角符号是自相矛盾(用户 2026-09-29「半角的时候不应该有全角符号显示在 L4-L5 上」)。
     * 半角态该追加的是同一批符号的**半角写法**。
     */
    fun l4Extras(fullWidth: Boolean = true): List<L4Key> = emptyList()

    /**
     * 该语言对 L4 **固定核心键**的最终处置:传入出厂核心键,返回本语言该显示的那些。
     *
     * 2026-09-29 用户定案后由三个小钩子(`l4Replace` 换 / `l4Drop` 退场 / 原来的 `l4Extras`)
     * 收成这一个 —— 键帽画什么、敲出什么、还是根本不上屏,是**同一个决定**,拆成三处参数
     * 迟早会写出"换了标签但没换输出"这种自相矛盾(真出过一次:键帽画全角分号却输出半角)。
     *
     * 默认原样返回(英文态就走这条:它不需要动核心键)。可以整把退场(币种交给物理键),
     * 也可以换键帽与输出(中文里的分号是全角)。[fullWidth] 同 [l4Extras]。
     */
    fun coreKeys(ins: List<L4Key>, fullWidth: Boolean): List<L4Key> = ins

    /**
     * **该语言专属的层键表**(L2/L3),覆盖键位表里同层的出厂值。
     *
     * 2026-09-29 用户定案:固定键的**符号**跟着语言走 —— 同一颗物理键,拼音态敲出中文标点、
     * 英文态敲出 ASCII。中文里 `,` 本就该是 `，` 而非半角逗号;`《》【】""''` 这些 ASCII
     * **根本打不出来**,只能靠这一层新增。返回 `null` = 用键位表出厂值(英文态)。
     *
     * [fullWidth] 是设置里的「中文全角」开关([dev.hwkbd.ime.ime.ImeController.fullWidthSymbolsInCn]):
     * 关掉就退回出厂 ASCII —— 那个开关的语义就是"中文态的标点要不要全角",硬编码全角会让
     * 它变成一个死开关(用户按了没反应)。
     *
     * **数字位与功能键位必须原样保留**:数字框会锁 L2 输数字(见 [FieldProfile.digitsOnly]),
     * Tab / 方向键 / Home / End 也在 L3 上,动了会让这些失灵(见 [ChineseLanguage.layerMap])。
     */
    fun layerMap(layer: Layer, fullWidth: Boolean = true): Map<Int, KeyOut>? = null

    /**
     * 该语言专属层表里**所有文本键的输出取值**,给 L4 那把筛用([ImeRegistry.l4Keys]):
     * 键位表的 [KeyLayout.producible] 只知道出厂那套,不知道拼音态已经把 L2/L3 换成中文标点,
     * 不并进来的话 L4 会摆出与 L2/L3 重复的键(屏上两把同字的键,只有其中一把是活的)。
     */
    fun layerSymbols(fullWidth: Boolean = true): Set<String> =
        Layer.values().flatMap { l -> layerMap(l, fullWidth)?.values.orEmpty() }
            .filterIsInstance<KeyOut.Text>()
            .map { it.text }
            .toSet()

    /** 切入该语言时的收尾钩子(拼音进来清掉英文态的大写锁定)。 */
    fun onEnter(state: ImeState) {}

    /** 按键产出文本的改写钩子,**进组词/上屏判定之前**生效;默认原样(拼音:v → 分节符)。 */
    fun mapText(text: String, state: ImeState): String = text

    /** 这段文本是否进组词缓冲;[state] 供策略按当前组词状态判定(分隔符只在组词中追加)。 */
    fun composes(text: String, state: ImeState): Boolean

    /** 不进组词缓冲的文本如何上屏:大小写([upper])与全角([fullWidth],配合 [layer])由策略自己取舍。 */
    fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String
}
