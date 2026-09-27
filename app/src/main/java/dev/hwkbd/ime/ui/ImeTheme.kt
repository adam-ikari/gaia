package dev.hwkbd.ime.ui

/**
 * 输入法配色主题:输入条背景 / 候选文字 / 主色(模式芯片、匹配拼音)/
 * 辅助色(箭头)/ 分隔线 / 高亮背景 / 层芯片颜色。
 */
data class ImeTheme(
    val id: String,
    val label: String,
    val barBg: Int,
    val fg: Int,
    val accent: Int,
    val hint: Int,
    val divider: Int,
    val pressed: Int,
    val chipL2: Int,
    val chipL3: Int,
) {
    companion object {
        fun byId(id: String?): ImeTheme = ALL.firstOrNull { it.id == id } ?: DEFAULT

        /** 主题色板基于 R.color 现值生成,保证默认主题与既有视觉一致。 */
        val DEFAULT: ImeTheme = ImeTheme(
            "obsidian", "曜石黑",
            barBg = 0xFF16181D.toInt(),
            fg = 0xFFEDF0F5.toInt(),
            accent = 0xFFC44D3D.toInt(),
            hint = 0xFF7C838F.toInt(),
            divider = 0xFF2A2E36.toInt(),
            pressed = 0xFF2C313A.toInt(),
            chipL2 = 0xFF5B8DBE.toInt(),
            chipL3 = 0xFF7FA05A.toInt(),
        )

        val ALL = listOf(
            DEFAULT,
            ImeTheme("gold", "墨金", 0xFF0E0E10.toInt(), 0xFFF5EFE0.toInt(), 0xFFD4AF37.toInt(), 0xFF8A8578.toInt(), 0xFF26241E.toInt(), 0xFF2A2820.toInt(), 0xFFC9A227.toInt(), 0xFF9C8B3D.toInt()),
            ImeTheme("coral", "珊瑚红", 0xFF1A1214.toInt(), 0xFFFFE8E8.toInt(), 0xFFFF6B6B.toInt(), 0xFF9C7A7A.toInt(), 0xFF33242A.toInt(), 0xFF3A2A30.toInt(), 0xFFE08A9B.toInt(), 0xFFC97B8E.toInt()),
            ImeTheme("azure", "黛蓝", 0xFF101622.toInt(), 0xFFE2EAF5.toInt(), 0xFF6FA8DC.toInt(), 0xFF7488A3.toInt(), 0xFF1F2A3D.toInt(), 0xFF243248.toInt(), 0xFF5B9BD5.toInt(), 0xFF6FC3A0.toInt()),
            ImeTheme("lavender", "薰衣草", 0xFF191322.toInt(), 0xFFEFE8F5.toInt(), 0xFFB39DDB.toInt(), 0xFF8F819E.toInt(), 0xFF2A2238.toInt(), 0xFF322A42.toInt(), 0xFF9D8CD8.toInt(), 0xFF7FB09A.toInt()),
            ImeTheme("moss", "苔绿", 0xFF101812.toInt(), 0xFFE4F0E4.toInt(), 0xFF8FBC6F.toInt(), 0xFF7A8F7A.toInt(), 0xFF1E2A20.toInt(), 0xFF243428.toInt(), 0xFF6FA8DC.toInt(), 0xFFB5C96F.toInt()),
        )

        fun labels(): Array<String> = ALL.map { it.label }.toTypedArray()
        fun ids(): Array<String> = ALL.map { it.id }.toTypedArray()
    }
}
