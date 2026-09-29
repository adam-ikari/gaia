package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.ShiftState
import dev.hwkbd.ime.keymap.KeyNames
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer

/**
 * 拼音(中文):字母进组词缓冲,没有大小写。
 *
 * **L2/L3 分两态**(见 [layerMap]),由状态行「拼」右侧那颗「全/半」开关切(同一个偏好
 * PREF_FULLWIDTH,设置页也有):
 * - **半角态** = 键位表出厂值 = 键盘上印着的那个字符,**按眼所见**;
 * - **全角态** = 那个字符的**全角形态**,位子一位不挪。
 *
 * 分节符走 **`v` 键**([mapText]:v → `'`,组词中进缓冲给连读分节 **xi'an ≠ xian**,查询时
 * [PinyinInputMethod] 原样交给引擎、按分节位约束匹配——`xi'an` 只中西安、不中先;
 * 空闲时按 v 就是一次普通上屏),**状态行 L4 不再放分隔符键**——
 * 屏上不占位,v 顺手在字母区。注意:v 记分节符后,ü 词(女/绿,词库 v 记 ü)靠 n/l 前缀候选列出。
 */
object ChineseLanguage : Language {

    /** 拼音音节分隔符。 */
    const val SEPARATOR = "'"

    override val id = "zh"
    override val chipLabel = "拼"
    override val methodId = "pinyin"
    override val showShiftChip = false
    override val shiftTapsEnabled = false

    /** 切入拼音时的收尾:清掉英文态可能挂着的锁定/一次性。 */
    override fun onEnter(state: ImeState) {
        state.shift = ShiftState.OFF
    }

    /** v 键 = 分节符(在字母区,组词中顺手);其余文本原样。 */
    override fun mapText(text: String, state: ImeState): String =
        if (text == "v") SEPARATOR else text

    /**
     * L4 核心键的最终处置(**跟全角开关走**,见 [Language.coreKeys])。
     *
     * - **分号**:中文里的分号是全角的,键帽与输出一起换 —— 原先键帽画全角 `;` 却输出半角 `;`
     *   是骗人。两态都留在 L4:丝印上本来就没有分号,任何一态都敲不出。
     *   半角态给半角 `;`、全角态给全角 `;` —— **状态行上不该出现与开关矛盾的字形**
     *   (用户 2026-09-29「半角的时候不应该有全角符号显示在 L4-L5 上」)。
     * - **币种 `$`**:**两态都退场**。它归物理键 Sym+R(半角/英文敲 `$`、拼音全角敲 `￥`,
     *   见 [L3_CN]),状态行再摆一把就是重复 —— 用户「`￥` 符号 L3 的 R 已经映射了」。
     */
    override fun coreKeys(ins: List<L4Key>, fullWidth: Boolean): List<L4Key> = ins
        .filterNot { it.label == "$" } // 币种归物理键
        .map {
            if (it.label != "；") it
            else if (fullWidth) L4Key("；", KeyOut.Text("；"))
            else L4Key(";", KeyOut.Text(";"))
        }

    /**
     * **全角态的 L2/L3 = 丝印上那个字符的全角形态,一位不挪。**(用户 2026-09-29 定案)
     *
     * 这是硬件键盘:各层的字符就**印在键上**,用户按的是眼睛看到的那个。所以全角态只许
     * "同一个字符换宽",不许换字 —— 印着 `-` 的键不能敲出 `《`,印着 `:` 的键敲 `：`。
     * 2026-09-29 中间试过一版"把中文标点按读着顺摊开",L2 十六把里十二把错位、
     * L3 十五把全错位,用户报障「彻底乱套了」;又试过一版"全角态直接铺 31 个中文标点",
     * 丝印同样对不上,用户报障「L2 的 P 键不是 `：` 吗」。两次都栽在同一件事上:
     * **"哪把键"受丝印约束,"敲出什么"受语言约束,两者混为一谈就既错位又丢键。**
     *
     * 两层都覆盖得到:丝印 L2 的 `( ) - _ / : @ * # + " ' ! . , ?` 与 L3 的
     * `~ < > ^ % [ ] ` ` `` \ & | { } =` 全在 U+21-U+7E,全角区块 U+FF01-U+FF5E 一一对应。
     * 唯二例外:`"`→`”`、`'`→`’`(**不是**全角引号 `＂` `＇`,中文引号是弯的);
     * `©` 不是 ASCII,压根没有全角形态,原样不动 —— 没有的可换,硬套一个反而是骗人。
     *
     * **代价**:`《》【】“‘…——、` 这些 ASCII 压根没有对应字符,两层都变不出来,
     * 只能上状态行 [l4Extras] —— L4 的定义本就是"L1-L3 敲不出的键",一行装不下的
     * 由 L5 接着(横滑翻页、不截断)。
     *
     * [fullWidth] = 那颗开关。**关掉整张表退回 null**(用键位表出厂值 = 丝印原样),
     * 否则它就是按了没反应的死开关。
     */
    override fun layerMap(layer: Layer, fullWidth: Boolean): Map<Int, KeyOut>? {
        if (!fullWidth) return null
        val table = when (layer) {
            Layer.L2 -> L2_CN
            Layer.L3 -> L3_CN
            Layer.L1 -> return null
        }
        val out = mutableMapOf<Int, KeyOut>()
        for ((k, s) in table) KeyNames.NAME_TO_CODE[k]?.let { out[it] = KeyOut.Text(s) }
        return out
    }

    /**
     * 全角态 L2 —— 16 个符号位,**每一位都是丝印上那个字符的全角形态**:
     *
     * 丝印 `( ) - _ / :` / `@ * # + " '` / `! . , ?`
     * → 输出 `（ ） － ＿ ／ ：` / `＠ ＊ ＃ ＋ ” ’` / `！ 。 ， ？`
     *
     * **10 个数字位两层都不动** —— 数字类输入框锁 L2 直接输数字
     * ([FieldProfile.digitsOnly],它的合法字符集正是照着这些槽位定的),挪了整类输入框就废。
     */
    private val L2_CN: List<Pair<String, String>> = listOf(
        "t" to "（", "y" to "）", "u" to "－", "i" to "＿", "o" to "／", "p" to "：",
        "a" to "＠", "g" to "＊", "h" to "＃", "j" to "＋", "k" to "”", "l" to "’",
        "z" to "！", "b" to "。", "n" to "，", "m" to "？",
    )

    /**
     * 全角态 L3 —— 14 个符号位(26 − 11 个功能键位 − 1 个空格),同样是丝印的全角形态:
     *
     * 丝印 `~ < > ^ % [ ] ` ` `` \ & | { } =` → 输出
     * `～ 《 》 ＾ ％ 【 】 ｀ ＼ ＆ ｜ ｛ ｝ ＝`
     *
     * **成对符号换的是"中文里真正用的那一个",不是 Unicode 的全角区块**(用户 2026-09-29 定案
     * 「中文状态 `<>` 对应的应该是 `《》,`[]` 对应 `【】,同理」):
     * - `<` `>` → **`《` `》`**;`[` `]` → **`【` `】`**。中文排版里这两对就是书名号与方头括号;
     *   写成全角 `＜＞［］` 没人用 —— 全角区块只是 Unicode 机械加宽,不是排版惯例。
     * - `{` `}` → `｛` `｝`:**保留机械全角**。中文没有对应的定名括号;要 `〔〕` 也行,那是 CJK
     *   括号序列里更外层的一对,习惯上配 `「」『』` 而不是 `{}`。
     * - 其余(`~ ^ % ` \ & | =`)按机械全角 +0xFEE0,这几个的中文形态就是全角本身
     *   (`~`→`～` 同时也是中文浪纹线)。
     * - R 位**币种**:半角/英文敲 `$`(键位表出厂值),拼音全角敲 `￥`。那里原本是从可可搬来的
     *   `©` —— 既不是 Titan 键帽上的东西、中文写作也几乎不用,2026-09-29 用户定案换掉。
     *   币种是**唯一不随标点全角与否变**的一类(那是标点的事),所以它归物理键、两态分工:
     *   半角给 ASCII 的 `$`、全角给中文的 `￥`。状态行那把核心 `$` 因此整个退场
     *   (见 [l4Drop]),屏上不会出现第二把币种键。
     *
     * **11 个功能键位两层都不动**(`key:esc/up/pgup/pgdn/tab/left/down/right/move_home/
     * move_end/insert`)—— Tab、方向键、光标首尾都在上面。
     *
     * 连带效果:`《》【】` 回到物理键上,状态行 [l4Extras] 就不必再摆它们。
     */
    private val L3_CN: List<Pair<String, String>> = listOf(
        "e" to "～", "r" to "￥", "t" to "《", "y" to "》",
        "u" to "＾", "f" to "％", "g" to "【", "h" to "】",
        "j" to "｀", "z" to "＼", "x" to "＆", "c" to "｜",
        "v" to "｛", "b" to "｝", "n" to "＝",
    )

    /**
     * L4/L5 装 **物理三层腾不出的**那些,**字形跟全角开关走**。
     *
     * 已经被物理键接管、不在这儿摆的:
     * - `《》【】` → Sym+T / Sym+Y / Sym+G / Sym+H(成对符号各占一格,见 [L3_CN]);
     * - 引号 → Alt+K / Alt+L。用户 2026-09-29 定案「`“` 应该切换半角再去按 L2 的 K、L 按键
     *   输入」—— 引号有两把物理键就够,不必在状态行再摆两把。
     * - 币种 → Sym+R(见 [coreKeys])。
     *
     * - **全角态**:省略号 `…`、破折号 `——`、顿号 `、`(逗号 Alt+N 已经有了),加冷门但中文里
     *   真有的 CJK 括号/引号那一族 `「」『』〔〕` 与注释符 `※`(用户「冷门的正好可以放 L4/L5」)。
     * - **半角态**:同一批符号的**半角写法** —— 省略号敲三个点 `...`、破折号敲两个连字符 `--`。
     *   其余在半角没有对应写法(顿号、CJK 括号都只在中文里存在),半角态就不摆 ——
     *   **状态行上不该出现与开关矛盾的字形**(用户 2026-09-29「半角的时候不应该有全角符号
     *   显示在 L4-L5 上」)。
     *
     * 状态行一行装不下的尾巴由 L5 接着(横滑翻页、不截断),所以全角态可以放心多放。
     * 还要更多就用键位编辑器的 `[L4]` 自定义段自己加(见 [dev.hwkbd.ime.keymap.KeyLayoutStore])。
     */
    override fun l4Extras(fullWidth: Boolean): List<L4Key> = if (fullWidth) {
        listOf(
            L4Key("…", KeyOut.Text("…")),
            L4Key("——", KeyOut.Text("——")),
            L4Key("、", KeyOut.Text("、")),
            L4Key("「", KeyOut.Text("「")), L4Key("」", KeyOut.Text("」")),
            L4Key("『", KeyOut.Text("『")), L4Key("』", KeyOut.Text("』")),
            L4Key("〔", KeyOut.Text("〔")), L4Key("〕", KeyOut.Text("〕")),
            L4Key("※", KeyOut.Text("※")),
        )
    } else {
        listOf(
            L4Key("...", KeyOut.Text("...")),
            L4Key("--", KeyOut.Text("--")),
        )
    }

    override fun composes(text: String, state: ImeState): Boolean = when {
        text.length == 1 && text[0] in 'a'..'z' -> true
        // 分隔符只在组词中追加(空闲时按 `'` 就是一次普通上屏,不新开组词)
        text == SEPARATOR && state.hasComposition -> true
        else -> false
    }

    /**
     * 半角态(= 键位表出厂值)下按「中文全角」开关把 L2 的 ASCII 标点转全角。
     *
     * 全角态走 [layerMap] 那张表,这里转的是**用户自己在 `keymap.txt` 里写进 L2 的半角键**
     * —— 没有这一层,改了键位表就等于在全角态漏出半角标点。规则与 [L2_CN] 一致:
     * `.`→`。`、`"`→`”`、`'`→`’`,其余 +0xFEE0;数字保持半角以便输号码。
     */
    override fun commitText(text: String, layer: Layer, upper: Boolean, fullWidth: Boolean): String {
        if (!(fullWidth && layer == Layer.L2)) return text
        return text.map { c ->
            when {
                c == '.' -> '。'
                c == '"' -> '”'
                c == '\'' -> '’'
                c.code in 0x21..0x2F || c.code in 0x3A..0x40 ||
                    c.code in 0x5B..0x60 || c.code in 0x7B..0x7E -> (c.code + 0xFEE0).toChar()
                else -> c
            }
        }.joinToString("")
    }
}
