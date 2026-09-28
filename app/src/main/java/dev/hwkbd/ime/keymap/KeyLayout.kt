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

    /**
     * L1/L2/L3 三层**已经能敲出来**的取值(文本与功能键都算,去掉重复)。
     * 状态行中区(L4)只保留这些之外的键——L1-L3 敲得出的就不占中区了。
     */
    fun producible(): Set<String> = Layer.values().flatMap { layer ->
        maps[layer]?.values?.map { it.format() } ?: emptyList()
    }.toSet()

    /**
     * 状态行中区(L4)实际显示的键序:**固定核心键([L4_CORE],L1-L3 敲不出的那几个)在前,
     * 键位编辑器 [L4] 段的自定义键在后,重复(标签+输出相同)的只留一个**。
     * 核心键不随编辑器增删消失——这是"固定显示"的语义;自定义键可自由增删。
     */
    fun l4Combined(): List<L4Key> {
        val keys = LinkedHashMap<String, L4Key>()
        for (k in L4_CORE + l4) {
            keys.putIfAbsent("${k.label} ${k.out.format()}", k)
        }
        return keys.values.toList()
    }

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
            // 标签要转义:标签里有 '=' 时(如 `!=`),不转义会被 parseText 当成分界符切成两半
            l4.forEach { append(escapeLabel(it.label)).append('=').append(it.out.format()).append('\n') }
        }
    }

    companion object {

        /**
         * L4 **固定核心键**:L1/L2/L3 三层敲不出的**单个字符**。
         * 状态行中区永远显示它们(键位编辑器里删掉也会被 [l4Combined] 合并回来);
         * Tab/Esc/Home/End/PU/PD/Ins/方向键 L3 全有,不重复占用中区。
         *
         * **不放组合按键**——`-> => != ==` 这类多字符串键已按用户要求移除(2026-09-28):
         * 组合串要去就去 L3 或键位编辑器 [L4] 段自定义,状态行中区只留单字符标点。
         */
        val L4_CORE: List<L4Key> = listOf(
            L4Key("；", KeyOut.Text(";")),
            L4Key("$", KeyOut.Text("\$")),
        )

        /** 标签里的 `\` 与 `=` 转义(层映射的键名不含这两个字符,转义只对 L4 标签有意义)。 */
        private fun escapeLabel(s: String): String = s.replace("\\", "\\\\").replace("=", "\\=")

        /** [escapeLabel] 的逆运算。 */
        private fun unescapeLabel(s: String): String {
            if ('\\' !in s) return s
            val sb = StringBuilder(s.length)
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    sb.append(s[i + 1])
                    i += 2
                } else {
                    sb.append(c)
                    i++
                }
            }
            return sb.toString()
        }

        /** 标签/取值分界:第一个**没被转义**的 `=`;没有则 -1。 */
        private fun splitLabelValue(line: String): Int {
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '\\' -> i += 2
                    '=' -> return i
                    else -> i++
                }
            }
            return -1
        }

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
                val eq = splitLabelValue(line)
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
                    val label = unescapeLabel(line.substring(0, eq)).trim()
                    if (label.isEmpty()) errors += lineNo else l4 += L4Key(label, out)
                } else {
                    val code = KeyNames.NAME_TO_CODE[unescapeLabel(line.substring(0, eq))]
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

            // L4 预置 = 固定核心键;键位编辑器 [L4] 段可在其后追加自定义键(核心键删不掉)
            val l4 = L4_CORE

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
