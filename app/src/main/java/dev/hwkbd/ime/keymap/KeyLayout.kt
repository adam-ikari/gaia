package dev.hwkbd.ime.keymap

import android.view.KeyEvent

/** 三个键盘层:L1 字母 / L2 符号数字(Alt) / L3 编程(Sym)。 */
enum class Layer { L1, L2, L3 }

/** 解析结果:全部行合法时 layout 非空,否则 errors 里带 1 起始行号。 */
data class KeyLayoutParse(val layout: KeyLayout?, val errors: List<Int>)

/**
 * 三层键位表。文本格式(层映射编辑器与 filesDir/keymap.txt 共用):
 *
 * ```
 * # 注释
 * [L1]
 * a=a
 * [L2]
 * q=1
 * [L3]
 * q={
 * space=->
 * esc=key:esc
 * ```
 *
 * L2/L3 中未出现的键回落到 L1(字母在任意层都可输入),与"Alt = 键帽上档符号"的直觉一致。
 */
class KeyLayout(private val maps: Map<Layer, Map<Int, KeyOut>>) {

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
    }

    companion object {

        fun parseText(text: String): KeyLayoutParse {
            val maps = mutableMapOf<Layer, MutableMap<Int, KeyOut>>()
            val errors = mutableListOf<Int>()
            var layer = Layer.L1
            text.lineSequence().forEachIndexed { idx, rawLine ->
                val lineNo = idx + 1
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                when (line) {
                    "[L1]", "[L2]", "[L3]" -> {
                        layer = Layer.valueOf(line.removeSurrounding("[", "]"))
                        maps.getOrPut(layer) { mutableMapOf() }
                        return@forEachIndexed
                    }
                }
                val eq = line.indexOf('=')
                if (eq <= 0) {
                    errors += lineNo
                    return@forEachIndexed
                }
                val code = KeyNames.NAME_TO_CODE[line.substring(0, eq)]
                val out = KeyOut.parse(line.substring(eq + 1))
                if (code == null || out == null) {
                    errors += lineNo
                } else {
                    maps.getOrPut(layer) { mutableMapOf() }[code] = out
                }
            }
            return if (errors.isEmpty()) KeyLayoutParse(KeyLayout(maps), emptyList()) else KeyLayoutParse(null, errors)
        }

        fun default(): KeyLayout {
            val l1 = mutableMapOf<Int, KeyOut>()
            for (i in 0..25) l1[KeyEvent.KEYCODE_A + i] = KeyOut.Text(('a' + i).toString())
            for ((c, s) in listOf(
                KeyEvent.KEYCODE_COMMA to ",", KeyEvent.KEYCODE_PERIOD to ".",
                KeyEvent.KEYCODE_SLASH to "/", KeyEvent.KEYCODE_SEMICOLON to ";",
                KeyEvent.KEYCODE_APOSTROPHE to "'", KeyEvent.KEYCODE_GRAVE to "`",
                KeyEvent.KEYCODE_MINUS to "-", KeyEvent.KEYCODE_EQUALS to "=",
                KeyEvent.KEYCODE_LEFT_BRACKET to "[", KeyEvent.KEYCODE_RIGHT_BRACKET to "]",
                KeyEvent.KEYCODE_BACKSLASH to "\\",
            )) l1[c] = KeyOut.Text(s)

            // L2 符号数字层:q..p = 1234567890,键帽上档符号
            // 注意字母 keycode 按字母表序(A=29..Z=54),不是 QWERTY 排列,必须按键名映射
            val l2 = mutableMapOf<Int, KeyOut>()
            "qwertyuiop".forEachIndexed { i, c ->
                l2[KeyEvent.KEYCODE_A + (c - 'a')] = KeyOut.Text("1234567890"[i].toString())
            }
            for ((k, s) in listOf(
                "a" to "!", "s" to "@", "d" to "#", "f" to "$", "g" to "%",
                "h" to "^", "j" to "&", "k" to "*", "l" to "(", "semicolon" to ")",
                "minus" to "_", "equals" to "+", "lbracket" to "{", "rbracket" to "}",
                "bkslash" to "|", "grave" to "~", "apostrophe" to "\"",
                "comma" to "<", "period" to ">", "slash" to "?",
            )) l2[KeyNames.NAME_TO_CODE[k]!!] = KeyOut.Text(s)

            // L3 编程层:高频开发符号,成对括号相邻
            val l3 = mutableMapOf<Int, KeyOut>()
            for ((k, s) in listOf(
                "q" to "{", "w" to "}", "e" to "[", "r" to "]", "t" to "(", "y" to ")",
                "u" to "<", "i" to ">", "o" to "\"", "p" to "'",
                "a" to ":", "s" to ";", "d" to "=", "f" to "+", "g" to "-",
                "h" to "*", "j" to "/", "k" to "_", "l" to "|",
                "z" to "\\", "x" to "`", "c" to "&", "v" to "%", "b" to "#",
                "n" to "@", "m" to "$", "comma" to "^", "period" to "~", "slash" to "|",
                "space" to "->",
            )) l3[KeyNames.NAME_TO_CODE[k]!!] = KeyOut.Text(s)

            return KeyLayout(
                mapOf(
                    Layer.L1 to l1.toMap(),
                    Layer.L2 to l2.toMap(),
                    Layer.L3 to l3.toMap(),
                )
            )
        }
    }
}
