package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.keymap.KeyLayout

/**
 * 一种输入框类型的表现策略,由 [ImeRegistry.registerFieldProfile] 注册、按 [FieldKind] 取用:
 *
 * - [l4ProfileId]:状态行中区用哪个 L4 键位套(键位编辑器 `[L4 xxx]` 段)——网址框给 http:// 等;
 * - [altChipLabel]:状态行 Alt(层 L2)芯片文字——数字框显示 0-9(alt 锁定的数字变种,
 *   只改这颗芯片的显示,不改层映射、不碰软键盘)。
 */
data class FieldProfile(
    val l4ProfileId: String = KeyLayout.L4_DEFAULT,
    val altChipLabel: String = "ALT",
)
