package dev.hwkbd.ime.keymap

import android.view.KeyEvent

/** 三个键盘层:L1 字母 / L2 符号数字(Alt) / L3 编程(Sym)。 */
enum class Layer { L1, L2, L3 }

/** 解析结果:全部行合法时 layout 非空,否则 errors 里带 1 起始行号。 */
data class KeyLayoutParse(val layout: KeyLayout?, val errors: List<Int>)

/**
 * L4/L5 键的**显示优先级**(2026-09-30 用户定案「L4L5 显示的按键是有优先级的」)。
 * **数字小 = 优先**,排在前面、留在 L4;行宽不够被推进 L5 的是数字大的。
 *
 * 整套 L4/L5 就是一条优先级列表:状态行 L4 取**装得下的最高优先级前缀**
 * ([dev.hwkbd.ime.ui.L4PanelView.onMeasure] 按"前缀最长标签宽"判装得下),L5 接剩下的尾巴
 * (横滑翻页)。所以"优先级"不是显示上的花活 —— 它**就是**"这把键留在 L4 还是被推进 L5"的判据。
 *
 * ## 两个家族,别混成一句"放 L1-L3 敲不出的键"(2026-09-30 用户两次纠正)
 *
 * 早先这里把整条规则写成「L4 放 L1-L3 敲不出的键」。那把**两件性质不同的事**混成了一句,
 * 后果是两种误判:以为"敲得出就不该上 L4"(于是连"一次上屏一段常用串"这种纯粹的省事也
 * 一并否掉),以及以为"L4 只该放单字符"(于是把词组短语塞进固定键档、名不正言不顺)。
 *
 * - **固定符号键(只有 [FIXED] 一档)**:单个符号,**物理 L1-L3 真敲不出**,状态行是唯一入口 ——
 *   出厂核心 `；` `$`(见 [KeyLayout.L4_CORE])。数量就那么几个,任何输入框都得有(按
 *   [FieldProfile.l4CoreKeys] 的合法值过滤,数字类全非法 ⇒ 整档退场)。
 * - **词组短语([PHRASE_HOT] [PHRASE] [PHRASE_COLD] 三档)**:一次按键上屏一段常用串。
 *   **图的是省事,不是字形** —— 物理键敲 `+86` 要三下、敲 `...` 要三下,状态行一下。
 *   `…` `——` `「」『』※` 都属于这一族(用户 2026-09-30:「`…` `——`,以及冷门的 `「」『』※`
 *   不是 L4 的固定按键 这是词组短语」)。三档只表示**常用度**,不表示性质。
 *
 * 跨家族唯一一条取舍规则是**去重**:一次物理键就能敲出、并不省操作的取值不上状态行(见
 * [dev.hwkbd.ime.strategy.ImeRegistry.l4Keys])。多字符短语天然一次敲不出,所以它从不拦词组短语。
 *
 * 档位由高到低:
 * 1. [FIXED] **固定符号键**:出厂核心 `；` `$`。`[L4 url]`/`[L4 email]` 等段删掉也会被合并回来。
 * 2. [PHRASE_HOT] **通用高频短语**(`…` `——`,半角态 `...` `--`):任何输入框都用得上,排在
 *    模式短语之前 —— 整批排在词组之后时,拼音邮箱模式下 `...`/`--` 被挤到 L5 末尾
 *    (用户 2026-09-30 报障)。
 * 3. [PHRASE] **模式短语 / 通用短语**(键位套里的 `http://` `.com` `+86` `00:00:00`):
 *    套内按常用度排序;也是键位文件 `[L4]` 段自定义键的默认档。
 * 4. [PHRASE_COLD] **冷门短语**(`「」『』※`):同一性质,但很少用,排最末、行紧时先溢出去
 *    (网址模式下要把位置让给 `http://`)。
 * 5. [USER] **用户自己编辑录入的短语**:功能还没实现(见 [USER_PHRASES_TODO]),档位先占住,
 *    接上时排在 [PHRASE] 之后即可,不影响已有四档。
 *
 * ## 状态行的长尾到此为止(2026-09-30 用户定案)
 *
 * **比 [PHRASE_COLD] 更冷的一律不上状态行**:CJK 括号的第三四层(`〔〕` 已撤)、制表符与序号
 * (`① ② ③`)、emoji 等,连同 [USER] 那档自定义短语,都**留给将来的屏幕软键盘**
 * (见 [dev.hwkbd.ime.ui.SoftKeyboardView])。理由是物理键盘的状态行**只有一行**:
 * 每多一把冷门键,行宽就少留给高频键与模式短语的位置,而 L4/L5 的切分是**前缀切**
 * (装不下的整段被推进 L5),挤掉的往往正是用户刚报障要留在 L4 的那些。
 * 换句话说:长尾不是"排在后面",而是**不在这一行**——要它们时用软键盘,那才是网格。
 */
object L4Priority {
    /**
     * **固定符号键**:单个符号,物理 L1-L3 敲不出,状态行是唯一入口(出厂核心 `；` `$`)。
     * 这一档"敲不出"是**定义本身**,全表只此一档。
     */
    const val FIXED = 0

    /**
     * **通用高频短语**:一次按键上屏一段常用串,任何输入框都用得上(`…` `——` / `...` `--`)。
     * 排在模式短语之前 —— 排在之后时拼音邮箱模式下它们会被挤到 L5 末尾(用户 2026-09-30 报障)。
     */
    const val PHRASE_HOT = 5

    /**
     * **模式短语 / 通用短语**:键位套里的 `http://` `.com` `+86` `00:00:00`……
     * 同样是"一次上屏一段常用串",只是**与输入框类型相关**;套内按常用度排序。
     * 也是键位文件 `[L4]` 段自定义键的默认档。
     */
    const val PHRASE = 10

    /**
     * **冷门短语**(`「」『』※`):同一性质,只是很少用 —— 排最末、行紧时先被推进 L5
     * (网址模式下要把位置让给 `http://`)。比这更冷的见类注释"长尾到此为止"。
     */
    const val PHRASE_COLD = 15

    /**
     * **用户自己编辑录入的短语**:功能还没实现(见 [USER_PHRASES_TODO]),档位先占住位置。
     */
    const val USER = 30

    /**
     * 用户自定义短语**还没实现**:键位编辑器目前只写得出"这一把键是什么",没有"按输入框分类 +
     * 长期累积用户常敲短语"这套数据源([L4Profile] 的自定义段只是静态键)。
     * 档位 [USER] 先占住位置,接上时不必再动排序。
     */
    const val USER_PHRASES_TODO = "用户自定义短语未实现:数据源(按输入框分类的用户短语库)还没有"
}

/**
 * L4 虚拟按键:屏幕面板上的一个可配置键(标签 + 输出 + 优先级)。
 *
 * [priority] 见 [L4Priority]:**默认 [L4Priority.PHRASE]**(这一把是"某个输入模式的词组短语",
 * 也是键位文件 `[L4]` 段能写出来的全部内容)。出厂固定符号键给 [L4Priority.FIXED](物理敲不出的
 * 单个符号,见 [KeyLayout.L4_CORE]),语言补差键里的通用高频短语给 [L4Priority.PHRASE_HOT]、
 * 冷门短语给 [L4Priority.PHRASE_COLD](见 [dev.hwkbd.ime.strategy.Language.l4Extras])。
 *
 * **优先级不进键位文件格式**(`label=output` 照旧,见 [KeyLayout.formatText]):文件排不出
 * "哪一把更该留 L4",那是输入法自己的判断;要改档位改语言策略那一侧。
 */
data class L4Key(val label: String, val out: KeyOut, val priority: Int = L4Priority.PHRASE)

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
     * - **最终显示前再过一道去重筛**([producible]:一次物理键就能敲出、并不省操作的取值不上
     *   状态行,2026-09-29 定案)——筛在注册表 [dev.hwkbd.ime.strategy.ImeRegistry.l4Keys] 做,
     *   本函数不筛。多字符词组短语天然一次敲不出,所以这把筛从不拦它们。
     */
    fun l4Combined(profile: String = L4_DEFAULT): List<L4Key> {
        val list = if (profile == L4_DEFAULT) {
            L4_CORE + l4
        } else {
            l4Profiles[profile] ?: builtinProfile(profile)
        }
        val keys = LinkedHashMap<String, L4Key>()
        for (k in list) {
            // 同一把键出现多次(核心键也在 `[L4]` 段里)只留一把,**优先级高的那把赢**
            val id = "${k.label} ${k.out.format()}"
            val held = keys[id]
            if (held == null || k.priority < held.priority) keys[id] = k
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
         * 各数字类型的合法单字符——白名单 [dev.hwkbd.ime.strategy.FieldProfile.digitChars] 与 Alt 那一项
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
         * L4 **固定符号键**:默认键位套永远显示它们(键位编辑器里删掉也会被 [l4Combined] 合并回来)。
         * `;`/`$` 是 L1/L2/L3 三层都敲不出的**单个字符**,状态行是它们唯一的入口 ——
         * 这一档"敲不出"就是定义本身(与 [PHRASE] 那档的"图省事"是两件事,别混)。
         * **`\` 已从固定键里删掉**(2026-09-29 用户定案):L3 编程层 `Sym+z` 就能敲出它,
         * 一次按键就能上屏,状态行再摆一把只是重复;L2 严格按 Titan 丝印,丝印上也没有它。
         * Tab/Esc/Home/End/PU/PD/Ins/方向键 L3 全有,不重复占用中区。
         *
         * **不放组合按键**——`-> => != ==` 这类多字符串键已按用户要求移除(2026-09-28):
         * 组合串要去就去 L3 或键位编辑器 [L4] 段自定义,状态行中区只留单字符标点。
         */
        val L4_CORE: List<L4Key> = listOf(
            // 键帽是**出厂**的(键位表与语言无关),画全角 `；`。实际显示/输出哪一把由
            // [dev.hwkbd.ime.strategy.Language.l4Replace] 按语言换:拼音态换全角 `；`、
            // 英文态换半角 `;` —— 换的是整把键(标签+输出),所以两边键帽都不骗人。
            // 优先级 [L4Priority.FIXED]:物理 L1-L3 敲不出的**单个符号**,状态行是唯一入口,
            // 任何输入框模式下都排在最前、留在 L4(词组短语挤不掉它们)
            L4Key("；", KeyOut.Text(";"), L4Priority.FIXED),
            L4Key("$", KeyOut.Text("\$"), L4Priority.FIXED),
        )

        /**
         * 网址框适用的核心键子集(注册表 [dev.hwkbd.ime.strategy.FieldProfile.l4CoreKeys] 选用):
         * `;` `$` 是合法 sub-delim(`?a=1;b=2`)且 L1/L2/L3 三层敲不出,**全留**
         * (2026-09-29 用户定案:合法的留、非法的去;`\` 2026-09-29 已从固定键删除,不再需要剔除)。
         */
        val L4_CORE_URL: List<L4Key> = L4_CORE

        /**
         * 邮箱框适用的核心键子集(注册表 [dev.hwkbd.ime.strategy.FieldProfile.l4CoreKeys] 选用):
         * 只留 `$`——RFC 5322 的 atext 集合(`!#$%&'*+-/=?^_`{|}~` + 字母数字)含它,
         * `user$tag@example.com` 是合法地址;`;` 不在 atext(只有引号形式才合法,供应商基本不收),
         * domain 段更是只允许字母/数字/`-`/`.`——**不合法的不进邮箱键帽**
         * (2026-09-29 用户定案,与网址框同一把尺:合法的留、非法的去)。
         */
        val L4_CORE_EMAIL: List<L4Key> = L4_CORE.filter { it.label == "\$" }

        /**
         * 出厂网址键位套:输入框是网址时的一键短语,**按常用度排序**(同 [L4_PROFILE_EMAIL_KEYS] 的思路):
         * 前 5 把左右留在 L4 中区,装不下的尾巴落到 L5 行(齿轮左侧键盘图标翻页)。
         *
         * 2026-09-29 用户定案「给网址模式添加更多的扩展词组确保溢出到L5」:原来只有 3 把
         * (`http://` `https://` `.com`),连同核心键 `； $` 一共 5 把,在中区 314dp 里**恰好铺满**
         * (实测键帽 5×144px),于是**不溢出、键盘图标不出现**——L5 那套机制在网址框里等于没有出口。
         * 扩充后必溢出。2026-09-30 用户「补充常用的」再补一批内网与常见后缀。
         *
         * **不放 `/`**——那是 L2 的键帽(Alt+o),L4 不重复(2026-09-28 用户定案);
         * `?` `&` `=` 同理都在 L2/L3 上,会被 [producible] 那把筛带走。
         * 多字符短语天然 L1-L3 按不出,所以一定能过筛;后缀选的是真正常用的,
         * `.xyz`/`.top` 那类垃圾后缀不收;`[L4 url]` 段可整段改写。
         */
        val L4_PROFILE_URL_KEYS: List<L4Key> = listOf(
            L4Key("http://", KeyOut.Text("http://")),
            L4Key("https://", KeyOut.Text("https://")),
            L4Key("www.", KeyOut.Text("www.")), // 最常敲的裸域前缀
            L4Key("localhost", KeyOut.Text("localhost")), // 本机调试/内网直连
            L4Key("127.0.0.1", KeyOut.Text("127.0.0.1")), // 配 localhost 最常跟的一段
            L4Key("192.168.", KeyOut.Text("192.168.")), // 内网段前缀,后面跟 0~255
            L4Key(".com", KeyOut.Text(".com")),
            L4Key(".cn", KeyOut.Text(".cn")),
            L4Key(".net", KeyOut.Text(".net")),
            L4Key(".org", KeyOut.Text(".org")),
            L4Key(".io", KeyOut.Text(".io")),
            L4Key(".dev", KeyOut.Text(".dev")),
            L4Key(".app", KeyOut.Text(".app")),
            L4Key(".co", KeyOut.Text(".co")),
            L4Key("index.html", KeyOut.Text("index.html")), // 本机起静态站最常敲的整串
            L4Key(".html", KeyOut.Text(".html")),
            L4Key(".php", KeyOut.Text(".php")),
            L4Key(".json", KeyOut.Text(".json")),
            L4Key(".xml", KeyOut.Text(".xml")),
            L4Key(".js", KeyOut.Text(".js")),
            L4Key(".css", KeyOut.Text(".css")),
            L4Key(".htm", KeyOut.Text(".htm")),
        )

        /**
         * 出厂邮件键位套:输入框是邮箱时的一键域名——**按显示优先级排序**(2026-09-29 用户定案):
         * 短域名后缀(`.com` `.net` `.org` `.cn` `.edu`)优先级高排**前面**,L4 优先显示它们;
         * 完整域名(`@gmail.com` 等)优先级低排**后面**,L4 自然宽装不下的尾巴才落到 L5
         * (齿轮左侧键盘图标翻页)——"后缀占 L4、域名溢出 L5"由**键序 + 既有溢出规则**自然达成,
         * 不做硬分栏。**键位套本身不含核心键**(并入哪些由 `FieldProfile.l4CoreKeys` 定:邮箱只并
         * 合法的 `$`),`[L4 email]` 段可整段改写。
         *
         * 2026-09-30 用户「补充常用的」:完整域名按国内常用度补齐(163/qq/126 排在国外那几家前面)。
         */
        val L4_PROFILE_EMAIL_KEYS: List<L4Key> = listOf(
            L4Key(".com", KeyOut.Text(".com")),
            L4Key(".net", KeyOut.Text(".net")),
            L4Key(".org", KeyOut.Text(".org")),
            L4Key(".cn", KeyOut.Text(".cn")),
            L4Key(".edu", KeyOut.Text(".edu")),
            L4Key("@163.com", KeyOut.Text("@163.com")),
            L4Key("@qq.com", KeyOut.Text("@qq.com")),
            L4Key("@126.com", KeyOut.Text("@126.com")),
            L4Key("@gmail.com", KeyOut.Text("@gmail.com")),
            L4Key("@hotmail.com", KeyOut.Text("@hotmail.com")),
            L4Key("@outlook.com", KeyOut.Text("@outlook.com")),
            L4Key("@yahoo.com", KeyOut.Text("@yahoo.com")),
            L4Key("@icloud.com", KeyOut.Text("@icloud.com")),
            L4Key("@foxmail.com", KeyOut.Text("@foxmail.com")),
            L4Key("@live.com", KeyOut.Text("@live.com")),
        )

        /** 数字类出厂键位套:一段短语即一把键(`[L4 number]` 等段可整段改写)。 */
        private fun phrases(vararg outs: String): List<L4Key> =
            outs.map { L4Key(it, KeyOut.Text(it)) }

        /**
         * number / signed / decimal / signeddecimal 四个类型出厂仍为空:它们的合法字符全在 L2,
         * **没有任何 L1-L3 按不出的东西可放**(不是被筛空的结果)。用户 2026-09-30
         * 「补充常用的」这一轮没给它们加内容——数字框里能敲的就是数字与那几个符号,
         * 硬凑"短语"只会摆出输入框根本不收的东西;要放就在对应段(`[L4 decimal]` 等)里自写。
         *
         * 数字类要 L4 有内容就得放**多字符组合**(物理键一次只出一个单字符,组合天然按不出),
         * 核心键 `； $` 在数字类全非法、由 `FieldProfile.l4CoreKeys = emptyList()` 滤掉。
         */
        val L4_PROFILE_NUMBER_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_SIGNED_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_DECIMAL_KEYS: List<L4Key> = emptyList()
        val L4_PROFILE_SIGNED_DECIMAL_KEYS: List<L4Key> = emptyList()

        /**
         * 出厂电话键位套:国际区号,**按从国内拨出的常用度排**(2026-09-30 用户「有些输入模式的
         * L4L5 中词组确实少 需要补充常用的」,原来只有 4 把)。港澳台与新马泰排在欧美前面。
         * 前缀留在 L4、溢出到 L5 是既有机制(见 [L4PanelView] 的前缀切)。
         *
         * **只放区号不放分机符号**:`、`(分机号分隔)归 Sym+Z(全角态的 `\` 位),
         * `转`/`ext` 之类各家表单写法不统一,写死在键帽上容易连着后面的分机一起上屏。
         */
        val L4_PROFILE_PHONE_KEYS: List<L4Key> = phrases(
            "+86", "+852", "+886", "+1", "+81", "+44", "+65", "+60",
            "+82", "+66", "+63", "+84", "+62", "+7", "+49", "+33", "+61", "+971",
        )

        /**
         * 出厂日期时间键位套:**时刻是数字类里唯一"单字符都按得出、整串仍然常用"的那一类** ——
         * `:` 和数字在 L2 上都有键,可 `00:00:00` 这种整串按不出(一把物理键只出一个字符),
         * 所以它天生属于 L4。`T00:00:00` 给 HTML `datetime-local` 那类要 ISO 本地格式的框。
         *
         * 年月日的数字同样按得出,所以**不给 `2026-01-01` 这种**:年份每月都变,写死在键帽上
         * 很快就过期,而敲四个数字并不麻烦。
         */
        val L4_PROFILE_DATETIME_KEYS: List<L4Key> = phrases(
            "00:00", "00:00:00", "T00:00:00", "23:59:59",
        )

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
                "h" to "#", "j" to "+", "k" to "\"", "l" to "'",
                "z" to "!", "x" to "7", "c" to "8", "v" to "9", "b" to ".",
                "n" to ",", "m" to "?",
            )) l2[KeyNames.NAME_TO_CODE[k]!!] = KeyOut.Text(s)

            // L3 编程层(Sym):**本输入法自定义,没有丝印可对**——Titan 的 Sym 键官方说法是
            // "弹出特殊符号列表,requires IME support",这一层的内容由输入法自己定。
            // 早期版本照搬可可的扩展字符布局,连 `©` 也带了过来;`©` 既不是 Titan 键帽上的东西、
            // 中文写作也几乎不用,2026-09-29 用户定案换成 `$`(用户原话:半角和英文态输 `$`)。
            // **中文全角态这一格由 [dev.hwkbd.ime.strategy.ChineseLanguage] 改成 `￥`**
            // ——同一个"钱"的位置,两态各给各的形态。
            // 成对括号相邻是有意排的(< > 相邻、[ ] 相邻、{ } 相邻),写代码时顺手。
            val l3 = mutableMapOf<Int, KeyOut>()
            for ((k, s) in listOf(
                "q" to "key:esc", "w" to "key:up", "e" to "~", "r" to "$",
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
