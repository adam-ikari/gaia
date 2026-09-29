package dev.hwkbd.ime.playground

import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyNames
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry

/**
 * **L1–L5 键位总览**(实验场「键位总览」按钮):把五层现在到底有哪些键一次打印出来,
 * 免得每次改键位都要靠真机截图反推。
 *
 * 打印内容:
 * - L1/L2/L3 物理三层:按 QWERTY 位置逐键 `键=输出`(读的是**运行时** [KeyLayout],
 *   所以换键位表/换手机打印出来的就是那台机器真实在用的表);
 * - L4 状态行中区:**按每种输入框类型**列出合成→筛后的实际显示键,并单列被
 *   「L1-L3 已有的按键不进 L4」筛掉的键(能直接看出哪把键为什么没出现);
 * - L5 状态行上方行:与 L4 同一份键序,只装 L4 一行装不下的尾巴(左侧对齐、键宽用自然宽)。
 */
object KeymapOverview {

    /** 打印顺序:按键盘上的行,读起来跟看键盘一样。 */
    private val QWERTY: List<Char> = "qwertyuiopasdfghjklzxcvbnm".toList()

    fun build(layout: KeyLayout, registry: ImeRegistry): String = buildString {
        val layerNames = mapOf(
            Layer.L1 to "L1 字母层(默认)",
            Layer.L2 to "L2 符号数字层(Alt,严格按 Titan 丝印)",
            Layer.L3 to "L3 编程层(Sym)",
        )
        for (layer in Layer.values()) {
            appendLine("── ${layerNames.getValue(layer)} ──")
            val map = layout.mapFor(layer)
            val pairs = QWERTY.mapNotNull { name ->
                val code = KeyNames.NAME_TO_CODE[name.toString()] ?: return@mapNotNull null
                val out = map[code] ?: return@mapNotNull null
                "$name=${render(out)}"
            }
            appendLine(if (pairs.isEmpty()) "(空)" else pairs.joinToString("  "))
        }

        val lang = registry.language(registry.primaryLanguageId)
        val producible = layout.producible() + lang.layerSymbols()
        // 语言的专属层表(拼音态 L2/L3 换成中文标点)会盖掉上面那几行的部分槽位,
        // 不单独列出来的话这份总览看着跟实际敲出来的不一样
        val langLines = buildString {
            for (layer in Layer.values()) {
                val over = lang.layerMap(layer) ?: continue
                val pairs = QWERTY.mapNotNull { name ->
                    val code = KeyNames.NAME_TO_CODE[name.toString()] ?: return@mapNotNull null
                    val out = over[code] ?: return@mapNotNull null
                    "$name=${render(out)}"
                }
                if (pairs.isNotEmpty()) {
                    appendLine("── ${layerNames.getValue(layer)}·${lang.chipLabel}态覆盖 ──")
                    appendLine(pairs.joinToString("  "))
                }
            }
        }
        if (langLines.isNotEmpty()) {
            appendLine("── 语言专属层表(盖在上面的物理三层之上)──")
            append(langLines)
        }
        appendLine("── L4 状态行中区(按输入框类型;L1-L3 已有的键不显示)──")
        for (kind in FieldKind.values()) {
            val keys = registry.l4Keys(layout, kind, registry.primaryLanguageId)
            val hidden = (registry.fieldProfile(kind).l4CoreKeys +
                layout.l4Combined(registry.fieldProfile(kind).l4ProfileId))
                .filter { it.out.format() in producible }
            appendLine("${kind.name}: " + (keys.joinToString(" ") { it.label }.ifEmpty { "(空)" }))
            if (hidden.isNotEmpty()) {
                appendLine("    被 L1-L3 已有筛掉: " + hidden.joinToString(" ") { it.label })
            }
        }
        appendLine("── L5 状态行上方行 ──")
        appendLine("内容 = L4 同一份键序里 L4 一行装不下的尾巴(左侧对齐、键宽用自然宽);")
        appendLine("无溢出时不出现(键盘图标隐藏),所以实际显示哪几把取决于当时屏幕宽度。")
    }

    private fun render(out: KeyOut): String = when (out) {
        is KeyOut.Text -> out.text
        is KeyOut.Special -> "键:${out.name}"
    }
}
