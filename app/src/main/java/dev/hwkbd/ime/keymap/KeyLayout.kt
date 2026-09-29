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
     * L1/L2/L3 三层**已经能敲出来**的取值(文本与功能键都算,去重)。
     * L4 只保留这些之外的键——**L1-L3 已有的按键不进 L4**(2026-09-29 用户定案,
     * 推翻 2026-09-28"不做程序筛选":网址套手动去 `/` 的那把尺泛化到全部 L4)。
     * 由 [dev.hwkbd.ime.strategy.ImeRegistry.l4Keys] 在显示前统一过筛。
     */
    fun producible(): Set<String> = Layer.values().flatMap { layer ->
        maps[layer]?.values?.map { it.format() } ?: emptyList()
    }.toSet()

    /**
     * 状态行中区(L4)实际显示的键序,按键位套 [profile] 取:
     *
     * - **默认套**([L4_DEFAULT]):固定核心键([L4_CORE],L1-L3 敲不出的那几个)在前,
     *   键位编辑器 [L4] 段的自定义键在后,重复(标签+输出相同)的只留一个。核心键删不掉;
     * - **上下文套**(如 [L4_PROFILE_URL]):该段的字面键;
     *   文件里没写这段时回落出厂预置(老 keymap.txt 也拿得到网址键)。
     *   **并入哪些核心键由注册表 [dev.hwkbd.ime.strategy.FieldProfile.l4CoreKeys] 决定**
     *   (网址框要、邮件框不要),这里只管字面;
     * - **最终显示前再过一道 L1-L3 筛**([producible],L1-L3 已有的按键不进 L4,
     *   2026-09-29 定案)——筛在注册表 [dev.hwkbd.ime.strategy.ImeRegistry.l4Keys] 做,本函数不筛。
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

        /** 数字类键位套 id(编辑器 `[L4 number]` 段,[dev.hwkbd.ime.strategy.FieldKind.NUMBER] 选用)。 */
        const val L4_PROFILE_NUMBER = "number"

        /** `[L4 signed]` 段,[dev.hwkbd.ime.strategy.FieldKind.NUMBER_SIGNED] 选用。 */
        const val L4_PROFILE_SIGNED = "signed"

        /** `[L4 decimal]` 段,[dev.hwkbd.ime.strategy.FieldKind.NUMBER_DECIMAL] 选用。 */
        const val L4_PROFILE_DECIMAL = "decimal"

        /** `[L4 signeddecimal]` 段,[dev.hwkbd.ime.strategy.FieldKind.NUMBER_SIGNED_DECIMAL] 选用。 */
        const val L4_PROFILE_SIGNED_DECIMAL = "signeddecimal"

        /** `[L4 phone]` 段,[dev.hwkbd.ime.strategy.FieldKind.PHONE] 选用。 */
        const val L4_PROFILE_PHONE = "phone"

        /** `[L4 datetime]` 段,[dev.hwkbd.ime.strategy.FieldKind.DATETIME] 选用。 */
        const val L4_PROFILE_DATETIME = "datetime"

        /**
         * 各数字类型的合法单字符——白名单 [dev.hwkbd.ime.strategy.FieldProfile.digitChars] 与 Alt 芯片
         * 文字的**单源**(按各输入类型的合法格式定,见 [dev.hwkbd.ime.strategy.FieldKind])。
         * **只管物理键路径**:这些字符在 L2 上都有键帽、物理键直接敲得出,所以「L1-L3 已有的
         * 不进 L4」一把尺之下它们**不上 L4**(数字态 L4 放的是短语组合,见 [L4_PROFILE_PHONE_KEYS])。
         * NUMBER 只有 0-9,没有扩展字符。
         */
        const val DIGITS_NUMBER = ""
        const val DIGITS_SIGNED = "-"
        const val DIGITS_DECIMAL = "."
        const val DIGITS_SIGNED_DECIMAL = "-."
        const val DIGITS_PHONE = "+*#"
        const val DIGITS_DATETIME = "-/:."

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
         * 网址框适用的核心键子集(注册表 [dev.hwkbd.ime.strategy.FieldProfile.l4CoreKeys] 选用):
         * `\` 剔除——RFC 3986 非法(unreserved/sub-delim/gen-delim 都不含它),WHATWG 对
         * http/https 又把 `\` 归一成 `/`,在网址里敲它是错字符;
         * `;` `$` 是合法 sub-delim(`?a=1;b=2`)且 L1/L2/L3 三层敲不出,**保留**
         * (2026-09-29 用户定案:合法的留、非法的去)。
         */
        val L4_CORE_URL: List<L4Key> = L4_CORE.filter { it.label != "\\" }

        /**
         * 邮箱框适用的核心键子集(注册表 [dev.hwkbd.ime.strategy.FieldProfile.l4CoreKeys] 选用):
         * 只留 `$`——RFC 5322 的 atext 集合(`!#$%&'*+-/=?^_`{|}~` + 字母数字)含它,
         * `user$tag@example.com` 是合法地址;`;` `\` 不在 atext(只有引号形式才合法,供应商基本不收),
         * domain 段更是只允许字母/数字/`-`/`.`——**不合法的不进邮箱键帽**
         * (2026-09-29 用户定案,与网址框同一把尺:合法的留、非法的去)。
         */
        val L4_CORE_EMAIL: List<L4Key> = L4_CORE.filter { it.label == "\$" }

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
         * 出厂邮件键位套:输入框是邮箱时的一键域名——**按显示优先级排序**(2026-09-29 用户定案):
         * 短域名后缀(`.com` `.net` `.org` `.cn` `.edu`)优先级高排**前面**,L4 优先显示它们;
         * 完整域名(`@gmail.com` 等)优先级低排**后面**,L4 自然宽装不下的尾巴才落到 L5
         * (齿轮左侧键盘图标翻页)——"后缀占 L4、域名溢出 L5"由**键序 + 既有溢出规则**自然达成,
         * 不做硬分栏。**键位套本身不含核心键**(并入哪些由 `FieldProfile.l4CoreKeys` 定:邮箱只并
         * 合法的 `$`),`[L4 email]` 段可整段改写。
         */
        val L4_PROFILE_EMAIL_KEYS: List<L4Key> = listOf(
            L4Key(".com", KeyOut.Text(".com")),
            L4Key(".net", KeyOut.Text(".net")),
            L4Key(".org", KeyOut.Text(".org")),
            L4Key(".cn", KeyOut.Text(".cn")),
            L4Key(".edu", KeyOut.Text(".edu")),
            L4Key("@gmail.com", KeyOut.Text("@gmail.com")),
            L4Key("@hotmail.com", KeyOut.Text("@hotmail.com")),
            L4Key("@outlook.com", KeyOut.Text("@outlook.com")),
        )

        /** 数字类出厂键位套:一段短语即一把键(`[L4 number]` 等段可整段改写)。 */
        private fun phrases(vararg outs: String): List<L4Key> =
            outs.map { L4Key(it, KeyOut.Text(it)) }

        /**
         * 出厂数字类键位套 = **短语组合**(2026-09-29 用户定案:`+86` 这类)。为什么不是一字一键:
         * 「L1-L3 已有的按键不进 L4」这把尺(见 [producible])按**单次按键的输出**判,而各数字类型的
         * 合法单字符(`- / : . + * #`)在 L2 上全有键——一字一键会被整段筛空,数字态 L4 就空了。
         * 物理键一次只能出一个单字符,**多字符组合天然 L1-L3 按不出**,所以数字态要 L4 有内容
         * 就得放组合键。核心键 `； $ \` 在数字类全非法,由 `FieldProfile.l4CoreKeys = emptyList()` 滤掉。
         *
         * number / signed / decimal / signeddecimal / datetime 五个类型出厂为空:它们的合法字符
         * 全在 L2,**没有任何 L1-L3 按不出的东西可放**(不是被筛空的结果);要放就在对应段
         * (`[L4 datetime]` 等)里自写短语。
         */
        val L4_PROFILE_NUMBER_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_SIGNED_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_DECIMAL_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_SIGNED_DECIMAL_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_PHONE_KEYS: List<L4Key> = phrases("+86", "+1", "+81", "+44")
        val L4_PROFILE_DATETIME_KEYS: List<L4Key> = emptyList()

        /** 上下文键位套的出厂预置(编辑器里没写该段时用它)。 */
        private fun builtinProfile(id: String): List<L4Key> = when (id) {
            L4_PROFILE_URL -> L4_PROFILE_URL_KEYS
            L4_PROFILE_EMAIL -> L4_PROFILE_EMAIL_KEYS
            L4_PROFILE_NUMBER -> L4_PROFILE_NUMBER_KEYS
            L4_PROFILE_SIGNED -> L4_PROFILE_SIGNED_KEYS
            L4_PROFILE_DECIMAL -> L4_PROFILE_DECIMAL_KEYS
            L4_PROFILE_SIGNED_DECIMAL -> L4_PROFILE_SIGNED_DECIMAL_KEYS
            L4_PROFILE_PHONE -> L4_PROFILE_PHONE_KEYS
            L4_PROFILE_DATETIME -> L4_PROFILE_DATETIME_KEYS
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
