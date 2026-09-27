package dev.hwkbd.ime.keymap

import android.view.KeyEvent

/** 三个键盘层:L1 字母 / L2 符号数字(Alt) / L3 编程(Sym)。 */
enum class Layer { L1, L2, L3 }

/** 解析结果:全部行合法时 layout 非空,否则 errors 里带 1 起始行号。 */
data class KeyLayoutParse(val layout: KeyLayout?, val errors: List<Int>)

/** L4 虚拟按键:屏幕面板上的一个可配置键(标签 + 输出)。 */
data class L4Key(val label: String, val out: KeyOut)

/**
 * 键位表:L1/L2/L3 物理层 + L4 虚拟按键(屏幕面板,每个键 = 自由标签 + 文本/功能键输出)。
 * 文本格式(层映射编辑器与 filesDir/keymap.txt 共用):
 *
 * ```
 * # 注释
 * [L1]
 * a=a
 * [L2]
 * q=1
 * [L3]
 * q={
 * [L4]
 * Tab=key:tab
 * ->=->        ← 标签「->」输出「->」(按第一个 = 分割)
 * ```
 *
 * L2/L3 中未出现的键回落到 L1(字母在任意层都可输入),与"Alt = 键帽上档符号"的直觉一致。
 */
class KeyLayout(
    private val maps: Map<Layer, Map<Int, KeyOut>>,
    val l4: List<L4Key> = emptyList(),
) {

    fun mapFor(layer: Layer): Map<Int, KeyOut> = maps[layer] ?: emptyMap()

    fun lookup(layer: Layer, code: Int): KeyOut? =
        maps[layer]?.get(code) ?: if (layer != Layer.L1) maps[Layer.L1]?.get(code) else null

    fun formatText(): String = buildString {
        for (layer in Layer.values()) {
            append('[').append(layer.name).append("]\n")
            val names = KeyNames.CODE_TO_NAME
            maps[layer]?.entries
                ?.sortedWith(compareBy({ names[it.key] == null }, { names[it.key] ?: "" }))
                ?.forEach { (code, out) ->
                    val name = names[code] ?: return@forEach
                    append(name).append('=').append(out.format()).append('\n')
                }
        }
        if (l4.isNotEmpty()) {
            append("[L4]\n")
            l4.forEach { append(it.label).append('=').append(it.out.format()).append('\n') }
        }
    }

    companion object {

        fun parseText(text: String): KeyLayoutParse {
            val maps = mutableMapOf<Layer, MutableMap<Int, KeyOut>>()
            val l4 = mutableListOf<L4Key>()
            val errors = mutableListOf<Int>()
            var section = "L1"
            var inL4 = false
            text.lineSequence().forEachIndexed { idx, rawLine ->
                val lineNo = idx + 1
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                if (line.startsWith("[") && line.endsWith("]")) {
                    section = line.removeSurrounding("[", "]")
                    inL4 = section == "L4"
                    if (!inL4) {
                        val l = runCatching { Layer.valueOf(section) }.getOrNull()
                        if (l == null) {
                            errors += lineNo
                        } else {
                            maps.getOrPut(l) { mutableMapOf() }
                        }
                    }
                    return@forEachIndexed
                }
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    errors += lineNo
                    return@forEachIndexed
                }
                val out = KeyOut.parse(line.substring(eq + 1))
                if (out == null) {
                    errors += lineNo
                    return@forEachIndexed
                }
                if (inL4) {
                    val label = line.substring(0, eq).trim()
                    if (label.isEmpty()) errors += lineNo else l4 += L4Key(label, out)
                } else {
                    val code = KeyNames.NAME_TO_CODE[line.substring(0, eq)]
                    if (code == null) errors += lineNo
                    else maps.getOrPut(Layer.valueOf(section)) { mutableMapOf() }[code] = out
                }
            }
            return if (errors.isEmpty()) {
                // 缺 [L1] 段时补默认字母层(自定义布局通常只改 L2/L3)
                maps.getOrPut(Layer.L1) { default().mapFor(Layer.L1).toMutableMap() }
                KeyLayoutParse(KeyLayout(maps, l4), emptyList())
            } else KeyLayoutParse(null, errors)
        }

        fun default(): KeyLayout {
            val l1 = mutableMapOf<Int, KeyOut>()
            for (i in 0..25) l1[KeyEvent.KEYCODE_A + i] = KeyOut.Text(('a' + i).toString())

            // L2 符号数字层(Alt):与 TitanKey.kcm 厂商键帽定案完全一致——
            // 数字 0-9 九宫格排在 WER/SDF/XCV(Q=0),标点在右手区;本机无独立标点键。
            val l2 = mutableMapOf<Int, KeyOut>()
            for ((k, s) in listOf(
                "q" to "0", "w" to "1", "e" to "2", "r" to "3", "t" to "(",
                "y" to ")", "u" to "-", "i" to "_", "o" to "/", "p" to ":",
                "a" to "@", "s" to "4", "d" to "5", "f" to "6", "g" to "*",
                "h" to "#", "j" to "+", "k" to "\"", "l" to "\\",
                "z" to "!", "x" to "7", "c" to "8", "v" to "9", "b" to ".",
                "n" to ",", "m" to "?",
            )) l2[KeyNames.NAME_TO_CODE[k]!!] = KeyOut.Text(s)

            // L3 编程层(Sym):与可可扩展字符默认布局完全一致(功能键+成对括号相邻)
            val l3 = mutableMapOf<Int, KeyOut>()
            for ((k, s) in listOf(
                "q" to "key:esc", "w" to "key:up", "e" to "~", "r" to "©",
                "t" to "<", "y" to ">", "u" to "^", "i" to "key:pgup",
                "o" to "key:pgdn", "p" to "key:tab",
                "a" to "key:left", "s" to "key:down", "d" to "key:right", "f" to "%",
                "g" to "[", "h" to "]", "j" to "`", "k" to "key:move_home",
                "l" to "key:move_end",
                "z" to "\\", "x" to "&", "c" to "|", "v" to "{", "b" to "}",
                "n" to "=", "m" to "key:insert",
            )) l3[KeyNames.NAME_TO_CODE[k]!!] = KeyOut.parse(s)!!

            // L4 虚拟按键预置:物理键盘缺失的开发常用键与高频组合符(面板上点击输出)
            val l4 = listOf(
                "Tab", "Esc", "Home", "End", "PU", "PD", "Ins",
                "↑", "↓", "←", "→",
            ).map { label ->
                val out = when (label) {
                    "Tab" -> KeyOut.Special("tab")
                    "Esc" -> KeyOut.Special("esc")
                    "Home" -> KeyOut.Special("move_home")
                    "End" -> KeyOut.Special("move_end")
                    "PU" -> KeyOut.Special("pgup")
                    "PD" -> KeyOut.Special("pgdn")
                    "Ins" -> KeyOut.Special("insert")
                    "↑" -> KeyOut.Special("up")
                    "↓" -> KeyOut.Special("down")
                    "←" -> KeyOut.Special("left")
                    else -> KeyOut.Special("right")
                }
                L4Key(label, out)
            } + listOf(
                L4Key("->", KeyOut.Text("->")),
                L4Key("＝>", KeyOut.Text("=>")),
                L4Key("!=", KeyOut.Text("!=")),
                L4Key("＝＝", KeyOut.Text("==")),
                L4Key("；", KeyOut.Text(";")),
                L4Key("$", KeyOut.Text("\$")),
            )

            return KeyLayout(
                mapOf(
                    Layer.L1 to l1.toMap(),
                    Layer.L2 to l2.toMap(),
                    Layer.L3 to l3.toMap(),
                ),
                l4,
            )
        }
    }
}
