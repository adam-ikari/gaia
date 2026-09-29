package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.L4Key

/**
 * 一种输入框类型的表现策略,由 [ImeRegistry.registerFieldProfile] 注册、按 [FieldKind] 取用:
 *
 * - [l4ProfileId]:状态行中区用哪个 L4 键位套(键位编辑器 `[L4 xxx]` 段)——网址框给 http:// 等;
 * - [altChipLabel]:状态行 Alt(层 L2)芯片文字——数字类按**实际可输字符集**如实标注
 *   ("0-9" + 该类型放行的额外字符:decimal 显示 0-9.、电话 0-9+*#;用户 2026-09-29 定案:
 *   decimal/signed/phone/date 显示光秃秃的 0-9 不恰当);
 * - [defaultLanguageId]:聚焦该类型输入框时的默认语言(邮箱框 = 英文,用户 2026-09-29 定案);
 *   null = 不动当前语言(同框重启动输入不打断用户手动切的语言);
 * - [l4CoreKeys]:固定核心键里**本框适用的子集**并入 L4——默认全量(`； $ \`);网址框
 *   剔除 `\`(RFC 3986 非法、WHATWG 归一成 `/`,2026-09-29 用户定案:合法的 `; $` 留)、
 *   邮箱框只留 `$`(RFC 5322 atext 合法,`;` `\` 不在 atext);
 * - [digitsOnly]:**数字类直接锁定数字输入**——聚焦即锁 L2(数字层,免按 Alt)、
 *   只放行数字与 [digitChars]、状态芯片只留标了字符集的 Alt;**L4 不再整块隐藏**
 *   (2026-09-29 用户定案):滤掉全非法的核心键([l4CoreKeys] = 空),[l4ProfileId] 键位套
 *   只显示**L1-L3 敲不出**的键——数字态的合法单字符全在 L2(被 [dev.hwkbd.ime.keymap.KeyLayout.producible]
 *   滤掉,不上 L4),L4 放的是短语组合(如 `+86`),白名单另并入 L4 显示的键输出;
 * - [digitChars]:digitsOnly 下额外放行的**单字符**(物理键路径),按各输入类型的合法格式定
 *   (见 [FieldKind]),与 [l4ProfileId] 出厂键位套的合法性口径同源([dev.hwkbd.ime.keymap.KeyLayout.DIGITS_*])。
 */
data class FieldProfile(
    val l4ProfileId: String = KeyLayout.L4_DEFAULT,
    val altChipLabel: String = "alt",
    val l4CoreKeys: List<L4Key> = KeyLayout.L4_CORE,
    val digitsOnly: Boolean = false,
    val digitChars: String = "",
    val defaultLanguageId: String? = null,
)
