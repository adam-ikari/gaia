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

    /** 主线程延迟执行(层芯片的窗口期延迟显示用)。 */
    fun postDelayed(action: Runnable, delayMs: Long)
}
