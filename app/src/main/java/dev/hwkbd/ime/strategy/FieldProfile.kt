package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.keymap.KeyLayout

/**
 * 一种输入框类型的表现策略,由 [ImeRegistry.registerFieldProfile] 注册、按 [FieldKind] 取用:
 *
 * - [l4ProfileId]:状态行中区用哪个 L4 键位套(键位编辑器 `[L4 xxx]` 段)——网址框给 http:// 等;
 * - [altChipLabel]:状态行 Alt(层 L2)芯片文字——数字类显示 0-9(alt 锁定的数字变种);
 * - [l4Core]:固定核心键(`； $ \`)要不要并入 L4——网址框要(L1-L3 没有的按键也要显示)、
 *   邮件框**不要**(邮箱合法字符里用不上这三个);
 * - [digitsOnly]:**数字类直接锁定数字输入**——聚焦即锁 L2(数字层,免按 Alt)、
 *   只放行数字与 [digitChars]、状态芯片只留 0-9 的 Alt、L4 面板整块隐藏;
 * - [digitChars]:digitsOnly 下额外放行的字符,按各输入类型的合法格式定(见 [FieldKind])。
 */
data class FieldProfile(
    val l4ProfileId: String = KeyLayout.L4_DEFAULT,
    val altChipLabel: String = "alt",
    val l4Core: Boolean = true,
    val digitsOnly: Boolean = false,
    val digitChars: String = "",
)
