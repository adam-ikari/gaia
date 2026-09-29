package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/**
 * 策略注册表:语言 / 输入法 / 输入框表现三张表,加一种就 [register*] 一下,
 * 核心状态机按 id 查策略,不认识任何具体实现。
 *
 * 组合规则(第一性):状态行 L4 中区显示什么 = **输入类型选键位套**(键位编辑器 `[L4 xxx]` 段)
 * **并上当前语言注册的补差键**(各语言自定;拼音没有——分节符走 `v` 键)——两个维度各自注册,互不侵入。
 */
class ImeRegistry {

    private val languages = LinkedHashMap<String, Language>()
    private val methods = LinkedHashMap<String, InputMethod>()
    private val profiles = LinkedHashMap<FieldKind, FieldProfile>()

    /** 密码框强制使用的语言(注册时标了 [registerLanguage] 的 secure)。 */
    var secureLanguageId: String = EnglishLanguage.id
        private set

    /** 首个注册的语言 = 状态行主色实心那颗芯片(出厂是拼音)。 */
    val primaryLanguageId: String
        get() = languages.keys.firstOrNull() ?: ChineseLanguage.id

    /** 注册语言。[secure] = 密码框强制切到它(避免组词混入密码)。 */
    fun registerLanguage(language: Language, secure: Boolean = false): ImeRegistry {
        languages[language.id] = language
        if (secure) secureLanguageId = language.id
        return this
    }

    /** 注册输入法(同 id 覆盖)。 */
    fun registerMethod(method: InputMethod): ImeRegistry {
        methods[method.id] = method
        return this
    }

    /** 注册输入框类型的表现(L4 键位套 + Alt 芯片文字)。 */
    fun registerFieldProfile(kind: FieldKind, profile: FieldProfile): ImeRegistry {
        profiles[kind] = profile
        return this
    }

    /** 按 id 取语言;未知 id 回落首个已注册语言(不崩,状态里最多显示成默认语言)。 */
    fun language(id: String): Language =
        languages[id] ?: languages.values.firstOrNull() ?: ChineseLanguage

    /** 按 [Language.methodId] 取输入法;没注册回落直接上屏(最安全:不组词)。 */
    fun method(id: String): InputMethod = methods[id] ?: DirectInputMethod

    fun languages(): List<Language> = languages.values.toList()

    /** 输入框类型的表现;没注册过就是默认表现(默认键位套 + Alt 芯片)。 */
    fun fieldProfile(kind: FieldKind): FieldProfile = profiles[kind] ?: FieldProfile()

    /** 循环到下一个语言(状态行语言芯片点击 / Shift+空格)。 */
    fun nextLanguage(fromId: String): String {
        val list = languages.values.toList()
        if (list.isEmpty()) return fromId
        val i = list.indexOfFirst { it.id == fromId } // 未知 id 时 -1 → 落到首个
        return list[(i + 1) % list.size].id
    }

    /**
     * 状态行 L4 中区的键序:**语言补差键排最左**(语言注册的键;拼音没有——分节符走 `v` 键),
     * 其后是**固定核心键**(取 [FieldProfile.l4CoreKeys] 圈定的子集:网址框去 `\`、邮箱只留 `$`)
     * 接输入类型选的键位套(默认套自带核心键+自定义键,重复去重)。
     *
     * **最后一道筛:L1-L3 已经能敲出的取值一律不显示**([KeyLayout.producible];2026-09-29
     * 用户定案,推翻 2026-09-28"配置什么显示什么、程序不做筛选"——网址套手动去 `/` 的
     * "L2 键帽不进 L4"那把尺泛化到全部 L4)。L4 与 L5 共用本结果。
     */
    fun l4Keys(
        layout: KeyLayout,
        kind: FieldKind,
        languageId: String,
        fullWidthSymbols: Boolean = true,
    ): List<L4Key> {
        val profile = fieldProfile(kind)
        val combined = layout.l4Combined(profile.l4ProfileId)
        val lang = language(languageId)
        val keys = LinkedHashMap<String, L4Key>()
        val list = profile.l4CoreKeys + combined
        // 语言补差键跟核心键走**同一把合法性尺**:数字类的 [FieldProfile.l4CoreKeys] 是空的
        // (核心键在该框全非法),补差键也该一起退场 —— 数字框里摆一把省略号是荒谬的。
        // 核心键的合法性由各 profile 手圈([FieldKind] 按 RFC 3986/5322 圈网址/邮箱),
        // 补差键是语言自己的,不在那把圈里,只能靠这档一起收。
        val extras = if (profile.digitsOnly) emptyList() else lang.l4Extras(fullWidthSymbols)
        // **固定核心键排最前,语言补差键排最后**(用户 2026-09-29 定案,推翻 2026-09-28
        // 「这类由输入法注册的 L4 虚拟按键应该显示在最左侧」)。核心键是常驻的那一把分号,
        // 该在第一眼的位置;补差键是**冷门符号**,排最后意味着行宽不够时它们先被推进 L5
        // —— 常用键留在 L4,这是排序要的效果。
        //
        // 核心键**整把交给语言处置**([Language.coreKeys]:可换键帽与输出、可退场),且必须在
        // 去重**之前**:核心键会同时出现在 [FieldProfile.l4CoreKeys] 和 [KeyLayout.l4Combined]
        // 里(默认套 = 核心键 + 自定义键),处置晚了同一把会以两个不同身份各留一把。
        val core = lang.coreKeys(profile.l4CoreKeys, fullWidthSymbols)
        // `combined` 里也含核心键(默认套 = 核心键 + 自定义键),必须**过同一遍**处置 ——
        // 只处置 profile 那份的话,原始那把会从 combined 溜进来,屏上并排两把同标签的键
        val rest = lang.coreKeys(combined, fullWidthSymbols)
        for (k in core + rest + extras) {
            keys.putIfAbsent("${k.label} ${k.out.format()}", k)
        }
        // L1-L3 已有的取值不进 L4(2026-09-29 定案,producible 复活为显示前的统一筛)。
        // 算的是**当前语言 + 当前开关下真正敲得出的**那批(见 [producible]),不是出厂表的并集。
        // 还要算上**当前语言专属层表**里的取值,否则拼音态 L2/L3 已经能敲出 `《》`，`
        // L4 上再摆一把同字的键就是重复(屏上看着两把,只有其中一把是活的)。
        // [fullWidthSymbols] 得跟物理键那条路取同一个开关:关了全角时若还按全角表筛,
        // `；` 这类会被误判成"L2 已能敲出"而整个消失,而那时 L2 恰恰敲不出它。
        val producible = producible(layout, lang, fullWidthSymbols)
        return keys.values.filterNot { it.out.format() in producible }
    }

    /**
     * 当前语言 + 当前全角开关下,L1-L3 三层**真正能敲出**的取值。
     *
     * 逐层看:该层被 [Language.layerMap] 接管了就用那张表,否则用键位表出厂值。
     * [KeyLayout.producible] 是**无条件的出厂视图**,不认语言也不认开关,直接拿来筛会误判 ——
     * 出厂 L3 的 R 位是 `$`、拼音全角态那张表把它换成了 `￥`,两张表并起来的话全角态会
     * 判定"`$` L1-L3 敲得出"而把状态行那把 `$` 筛掉,可那恰恰是全角态**唯一**能敲出 `$`
     * 的地方(物理键给的是 `￥`)。
     */
    private fun producible(layout: KeyLayout, lang: Language, fullWidth: Boolean): Set<String> =
        Layer.values().flatMap { l ->
            val over = lang.layerMap(l, fullWidth)
            if (over != null) over.values else layout.mapFor(l).values
        }.map { it.format() }.toSet()

    companion object {

        /**
         * 数字类表现:芯片文字 = "0-9" + 该类型额外放行的字符(如实标注可输字符集——
         * decimal/signed/phone/date 只显示 0-9 不恰当,用户 2026-09-29 定案)、锁 L2、只放行这些字符。
         *
         * L4(2026-09-29 用户定案):**不隐藏**——核心键 `； $ \` 在数字类全非法,按
         * 「合法的留、非法的去」滤掉(`l4CoreKeys = emptyList()`);L4 显示 [FieldProfile.l4ProfileId]
         * 键位套里**L1-L3 敲不出**的键(短语组合,见 [KeyLayout.L4_PROFILE_PHONE_KEYS]):
         * 出厂键位 L2 自带全部合法单字符(`- . / : + * #`),它们由 [chars] 白名单放行物理键
         * 直接敲,**不上 L4**;`[L4 datetime]` 等段可整段改写。
         */
        private fun digits(chars: String, profileId: String) = FieldProfile(
            l4ProfileId = profileId,
            altChipLabel = "0-9" + chars,
            digitsOnly = true,
            digitChars = chars,
            l4CoreKeys = emptyList(),
        )

        /**
         * 出厂注册表:拼音 + 英文(密码态)、直接上屏、网址/邮件/数字类表现。
         * 数字类(含签名/小数/电话/日期)统一 [FieldProfile.digitsOnly]:锁 L2、只放行合法字符。
         */
        fun builtin(): ImeRegistry {
            return ImeRegistry()
                .registerLanguage(ChineseLanguage)
                .registerLanguage(EnglishLanguage, secure = true)
                .registerMethod(DirectInputMethod)
                // 网址框:http:// 等一键上屏 + 核心键子集(去掉非法的 `\`、留合法的 `; $`,
                // 2026-09-29 定案;[L4 url] 段可改键位套)
                .registerFieldProfile(
                    FieldKind.URL,
                    FieldProfile(
                        l4ProfileId = KeyLayout.L4_PROFILE_URL,
                        l4CoreKeys = KeyLayout.L4_CORE_URL,
                    ),
                )
                // 邮件框:@域名键 + 核心键只并 `$`(RFC 5322 atext 合法;`;` `\` 非法不进,
                // 2026-09-29 定案;默认英文态(邮箱地址是 ASCII,用户 2026-09-29 定案)
                .registerFieldProfile(
                    FieldKind.EMAIL,
                    FieldProfile(
                        l4ProfileId = KeyLayout.L4_PROFILE_EMAIL,
                        l4CoreKeys = KeyLayout.L4_CORE_EMAIL,
                        defaultLanguageId = EnglishLanguage.id,
                    ),
                )
                // 数字类:各自按合法格式放行(见 FieldKind 的调查),其余全吞;
                // L4 = 该类型的合法扩展键位套(核心键全非法被滤,2026-09-29 用户定案)
                .registerFieldProfile(
                    FieldKind.NUMBER,
                    digits(KeyLayout.DIGITS_NUMBER, KeyLayout.L4_PROFILE_NUMBER),
                )
                .registerFieldProfile(
                    FieldKind.NUMBER_SIGNED,
                    digits(KeyLayout.DIGITS_SIGNED, KeyLayout.L4_PROFILE_SIGNED),
                )
                .registerFieldProfile(
                    FieldKind.NUMBER_DECIMAL,
                    digits(KeyLayout.DIGITS_DECIMAL, KeyLayout.L4_PROFILE_DECIMAL),
                )
                .registerFieldProfile(
                    FieldKind.NUMBER_SIGNED_DECIMAL,
                    digits(KeyLayout.DIGITS_SIGNED_DECIMAL, KeyLayout.L4_PROFILE_SIGNED_DECIMAL),
                )
                .registerFieldProfile(
                    FieldKind.PHONE,
                    digits(KeyLayout.DIGITS_PHONE, KeyLayout.L4_PROFILE_PHONE),
                )
                .registerFieldProfile(
                    FieldKind.DATETIME,
                    digits(KeyLayout.DIGITS_DATETIME, KeyLayout.L4_PROFILE_DATETIME),
                )
        }
    }
}
