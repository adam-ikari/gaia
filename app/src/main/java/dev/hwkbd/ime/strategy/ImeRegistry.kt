package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.L4Key

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
     * **配置什么显示什么,程序不做筛选**。
     */
    fun l4Keys(layout: KeyLayout, kind: FieldKind, languageId: String): List<L4Key> {
        val profile = fieldProfile(kind)
        val combined = layout.l4Combined(profile.l4ProfileId)
        val keys = LinkedHashMap<String, L4Key>()
        val list = profile.l4CoreKeys + combined
        for (k in language(languageId).l4Extras + list) {
            keys.putIfAbsent("${k.label} ${k.out.format()}", k)
        }
        return keys.values.toList()
    }

    companion object {

        /**
         * 数字类表现:芯片文字 = "0-9" + 该类型额外放行的字符(如实标注可输字符集——
         * decimal/signed/phone/date 只显示 0-9 不恰当,用户 2026-09-29 定案)、锁 L2、只放行这些字符。
         */
        private fun digits(extra: String) = FieldProfile(
            altChipLabel = "0-9" + extra,
            digitsOnly = true,
            digitChars = extra,
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
                // 数字类:各自按合法格式放行(见 FieldKind 的调查),其余全吞
                .registerFieldProfile(FieldKind.NUMBER, digits(""))
                .registerFieldProfile(FieldKind.NUMBER_SIGNED, digits("-"))
                .registerFieldProfile(FieldKind.NUMBER_DECIMAL, digits("."))
                .registerFieldProfile(FieldKind.NUMBER_SIGNED_DECIMAL, digits("-."))
                .registerFieldProfile(FieldKind.PHONE, digits("+*#"))
                .registerFieldProfile(FieldKind.DATETIME, digits("-/:."))
        }
    }
}
