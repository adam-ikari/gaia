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
 * [L4 url]     ← 上下文键位套(输入框是网址时显示,id = url)
 * http://=http://
 * ```
 *
 * L2/L3 中未出现的键回落到 L1(字母在任意层都可输入),与"Alt = 键帽上档符号"的直觉一致。
 */
class KeyLayout(
    private val maps: Map<Layer, Map<Int, KeyOut>>,
    /** 默认键位套([L4] 段)。 */
    val l4: List<L4Key> = emptyList(),
    /** 上下文键位套(`[L4 xxx]` 段 → id `xxx`),缺段时 [l4Combined] 回落出厂预置。 */
    val l4Profiles: Map<String, List<L4Key>> = emptyMap(),
) {

    fun mapFor(layer: Layer): Map<Int, KeyOut> = maps[layer] ?: emptyMap()

    fun lookup(layer: Layer, code: Int): KeyOut? =
        maps[layer]?.get(code) ?: if (layer != Layer.L1) maps[Layer.L1]?.get(code) else null

    /**
     * 状态行中区(L4)实际显示的键序,按键位套 [profile] 取:
     *
     * - **默认套**([L4_DEFAULT]):固定核心键([L4_CORE],L1-L3 敲不出的那几个)在前,
     *   键位编辑器 [L4] 段的自定义键在后,重复(标签+输出相同)的只留一个。核心键删不掉;
     * - **上下文套**(如 [L4_PROFILE_URL]):该段的字面键,配置什么显示什么;
     *   文件里没写这段时回落出厂预置(老 keymap.txt 也拿得到网址键)。
     *   **要不要并入核心键由注册表 [dev.hwkbd.ime.strategy.FieldProfile.l4Core] 决定**
     *   (网址框要、邮件框不要),这里只管字面。
     */
    fun l4Combined(profile: String = L4_DEFAULT): List<L4Key> {
        val list = if (profile == L4_DEFAULT) {
            L4_CORE + l4
        } else {
            l4Profiles[profile] ?: builtinProfile(profile)
        }
        val keys = LinkedHashMap<String, L4Key>()
        for (k in list) {
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
            appendL4Section(L4_DEFAULT, l4)
        }
        for ((id, keys) in l4Profiles) {
            if (keys.isNotEmpty()) appendL4Section(id, keys)
        }
    }

    private fun StringBuilder.appendL4Section(id: String, keys: List<L4Key>) {
        if (id == L4_DEFAULT) append("[L4]\n") else append("[L4 ").append(id).append("]\n")
        // 标签要转义:标签里有 '=' 时(如 `!=`),不转义会被 parseText 当成分界符切成两半
        keys.forEach { append(escapeLabel(it.label)).append('=').append(it.out.format()).append('\n') }
    }

    companion object {

        /** 默认键位套 id(编辑器 `[L4]` 段)。 */
        const val L4_DEFAULT = "default"

        /** 网址键位套 id(编辑器 `[L4 url]` 段,[dev.hwkbd.ime.strategy.FieldKind.URL] 选用)。 */
        const val L4_PROFILE_URL = "url"

        /** 邮件键位套 id(编辑器 `[L4 email]` 段,[dev.hwkbd.ime.strategy.FieldKind.EMAIL] 选用)。 */
        const val L4_PROFILE_EMAIL = "email"

        /**
         * L4 **固定核心键**:默认键位套永远显示它们(键位编辑器里删掉也会被 [l4Combined] 合并回来)。
         * `;`/`$` 是 L1/L2/L3 三层敲不出的**单个字符**;`\` 是点名固定的(2026-09-28:
         * 前三层拿它不顺手,L4 按下就有,L4 也不做事后筛选)。
         * Tab/Esc/Home/End/PU/PD/Ins/方向键 L3 全有,不重复占用中区。
         *
         * **不放组合按键**——`-> => != ==` 这类多字符串键已按用户要求移除(2026-09-28):
         * 组合串要去就去 L3 或键位编辑器 [L4] 段自定义,状态行中区只留单字符标点。
         */
        val L4_CORE: List<L4Key> = listOf(
            L4Key("；", KeyOut.Text(";")),
            L4Key("$", KeyOut.Text("\$")),
            L4Key("\\", KeyOut.Text("\\")),
        )

        /**
         * 出厂网址键位套:输入框是网址时的一键短语。量过状态行中区约 264dp、键宽下限 36dp,
         * 这里只放最常用的三把(再加就该压键宽了),`[L4 url]` 段可整段改写。
         * **不放 `/`**——那是 L2 的键帽(Alt+o),L4 不重复(2026-09-28 用户定案)。
         */
        val L4_PROFILE_URL_KEYS: List<L4Key> = listOf(
            L4Key("http://", KeyOut.Text("http://")),
            L4Key("https://", KeyOut.Text("https://")),
            L4Key(".com", KeyOut.Text(".com")),
        )

        /**
         * 出厂邮件键位套:输入框是邮箱时的一键域名(2026-09-28 用户点名三把);
         * **不带核心键**(邮箱合法字符用不上 `; $ \`——`FieldProfile.l4Core = false`),
         * `[L4 email]` 段可整段改写。
         */
        val L4_PROFILE_EMAIL_KEYS: List<L4Key> = listOf(
            L4Key("@gmail", KeyOut.Text("@gmail")),
            L4Key("@hotmail", KeyOut.Text("@hotmail")),
            L4Key("@outlook", KeyOut.Text("@outlook")),
        )

        /** 上下文键位套的出厂预置(编辑器里没写该段时用它)。 */
        private fun builtinProfile(id: String): List<L4Key> = when (id) {
            L4_PROFILE_URL -> L4_PROFILE_URL_KEYS
            L4_PROFILE_EMAIL -> L4_PROFILE_EMAIL_KEYS
            else -> emptyList()
        }

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
            val profiles = mutableMapOf<String, MutableList<L4Key>>()
            val errors = mutableListOf<Int>()
            var section = "L1"
            var l4Id: String? = null
            text.lineSequence().forEachIndexed { idx, rawLine ->
                val lineNo = idx + 1
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
                if (line.startsWith("[") && line.endsWith("]")) {
                    val name = line.removeSurrounding("[", "]")
                    section = name
                    // L4 段:`[L4]` = 默认套,`[L4 url]` = 上下文套 id=url
                    l4Id = when {
                        name == "L4" -> L4_DEFAULT
                        name.startsWith("L4 ") -> name.substring(3).trim().ifEmpty { L4_DEFAULT }
                        else -> null
                    }
                    if (l4Id == null) {
                        val l = runCatching { Layer.valueOf(name) }.getOrNull()
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
                val id = l4Id
                if (id != null) {
                    val label = unescapeLabel(line.substring(0, eq)).trim()
                    if (label.isEmpty()) {
                        errors += lineNo
                    } else if (id == L4_DEFAULT) {
                        l4 += L4Key(label, out)
                    } else {
                        profiles.getOrPut(id) { mutableListOf() } += L4Key(label, out)
                    }
                } else {
                    val code = KeyNames.NAME_TO_CODE[unescapeLabel(line.substring(0, eq))]
                    if (code == null) errors += lineNo
                    else maps.getOrPut(Layer.valueOf(section)) { mutableMapOf() }[code] = out
                }
            }
            return if (errors.isEmpty()) {
                // 缺 [L1] 段时补默认字母层(自定义布局通常只改 L2/L3)
                maps.getOrPut(Layer.L1) { default().mapFor(Layer.L1).toMutableMap() }
                KeyLayoutParse(KeyLayout(maps, l4, profiles), emptyList())
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
                // 上下文键位套出厂预置:网址框的 http:// 等([L4 url] 段可整段改写)
                l4Profiles = mapOf(L4_PROFILE_URL to L4_PROFILE_URL_KEYS),
            )
        }
    }
}
