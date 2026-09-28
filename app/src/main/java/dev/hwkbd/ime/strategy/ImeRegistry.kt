package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.L4Key

/**
 * 策略注册表:语言 / 输入法 / 输入框表现三张表,加一种就 [register*] 一下,
 * 核心状态机按 id 查策略,不认识任何具体实现。
 *
 * 组合规则(第一性):状态行 L4 中区显示什么 = **输入类型选键位套**(键位编辑器 `[L4 xxx]` 段)
 * **并上当前语言的补差键**(拼音的分隔符)——两个维度各自注册,互不侵入。
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
     * 状态行 L4 中区的键序:输入类型选的键位套 + 当前语言的补差键,重复(标签+输出)去重。
     * 默认键位套带固定核心键;其它键位套按编辑器字面显示——**配置什么显示什么,程序不做筛选**。
     */
    fun l4Keys(layout: KeyLayout, kind: FieldKind, languageId: String): List<L4Key> {
        val keys = LinkedHashMap<String, L4Key>()
        for (k in layout.l4Combined(fieldProfile(kind).l4ProfileId) + language(languageId).l4Extras) {
            keys.putIfAbsent("${k.label} ${k.out.format()}", k)
        }
        return keys.values.toList()
    }

    companion object {

        /** 出厂注册表:拼音 + 英文(密码态)、直接上屏、网址/数字框表现。 */
        fun builtin(): ImeRegistry = ImeRegistry()
            .registerLanguage(ChineseLanguage)
            .registerLanguage(EnglishLanguage, secure = true)
            .registerMethod(DirectInputMethod)
            // 网址框:http:// 等一键上屏(键位套在键位编辑器 [L4 url] 段可改)
            .registerFieldProfile(FieldKind.URL, FieldProfile(l4ProfileId = KeyLayout.L4_PROFILE_URL))
            // 数字框:Alt 芯片显示 0-9(alt 锁定的数字变种,只改芯片文字)
            .registerFieldProfile(FieldKind.NUMBER, FieldProfile(altChipLabel = "0-9"))
    }
}
