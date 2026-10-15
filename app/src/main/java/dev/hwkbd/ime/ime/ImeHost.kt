package dev.hwkbd.ime.ime

/**
 * ImeController 与 Android 层的唯一通道,保证状态机可 JVM 单测。
 */
interface ImeHost {
    /** 上屏文本(有组词区时替换组词区,否则直接提交)。 */
    fun commitText(text: String)

    /** 向目标应用转发原始按键(删除、方向键、Ctrl 组合、回车等)。 */
    fun sendKey(code: Int, ctrl: Boolean = false, shift: Boolean = false)

    /** 状态有变,刷新 UI 与组词区。 */
    fun render()

    /**
     * 登记一次「长按待判定」:host 在 [ImeController.HOLD_MS] 后若键仍未松开,
     * 回调 [ImeController.onLongPressFire](2026-10-15 用户定的长按上屏 L2 符号 /
     * 拼音中长按插分隔符)。实现侧一般只对**最近一次**调度有效(新 down 覆盖旧的)。
     */
    fun scheduleLongPress(code: Int)

    /** 取消待判定的长按(松手 / 换键 / 复位时)。 */
    fun cancelLongPress()
}
