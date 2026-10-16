package dev.hwkbd.ime.ime

/**
 * 一条剪贴板记录。
 *
 * @property text 剪贴板原文(上屏用的就是它)。
 * @property sensitive 系统标记为敏感(Android 13+ `ClipDescription.EXTRA_IS_SENSITIVE`,
 *   如密码管理器复制的内容)。面板对它**遮罩显示**(`••••`),点击仍可上屏 —— 上屏是
 *   用户主动动作,遮罩防的是"面板上明文摆着密码"。旧系统无此标记,一律 false。
 */
data class ClipItem(val text: String, val sensitive: Boolean)

/**
 * 剪贴板历史(2026-10-16 用户「剪贴板功能:历史面板,复用 L6 浮层」)。
 *
 * ## 形态
 *
 * 内存列表,**最新在前**,默认 [DEFAULT_CAPACITY] 条;**不持久化**(进程生命周期,
 * 用户没要跨重启保留)。每一条对应剪贴板面板里的一行。
 *
 * ## 相邻去重
 *
 * 连续复制同一内容只留一条。系统的 primaryClip 变化回报可能重复触发同一份内容
 * (权限提示、多剪贴板等),不去重的话历史会被同一条刷满,把更早的内容挤掉。
 * 只在**与当前最新一条相同**时去重 —— 隔着别的内容再复制同一条,是两次真实动作,各记一条。
 */
class ClipHistory(private val capacity: Int = DEFAULT_CAPACITY) {
    private val items = ArrayDeque<ClipItem>()

    /** 最新在前的快照(面板按这个顺序每行一条)。 */
    val entries: List<ClipItem> get() = items.toList()

    /**
     * 记一条剪贴板内容。空串忽略、与当前最新一条重复则忽略;超过 [capacity] 丢最旧。
     *
     * @param text 剪贴板原文
     * @param sensitive 是否被系统标记为敏感(见 [ClipItem.sensitive])
     */
    fun record(text: String, sensitive: Boolean = false) {
        if (text.isEmpty()) return
        // 相邻重复:保留既有那条(连同它的 sensitive 标记),不重复记
        if (items.firstOrNull()?.text == text) return
        items.addFirst(ClipItem(text, sensitive))
        while (items.size > capacity) items.removeLast()
    }

    fun clear() = items.clear()

    companion object {
        /** 面板每条占一行,保留最近 10 条(2026-10-16 用户定)。 */
        const val DEFAULT_CAPACITY = 10
    }
}
