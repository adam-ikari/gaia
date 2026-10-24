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

    /**
     * 长按空格到点后开始语音输入。
     *
     * @return false = 这次没真正启动(无麦克风权限 / 无识别服务 / 启动异常),
     * 控制器会补一个空格,免得用户按了键却什么都没有。
     */
    fun startVoiceInput(languageId: String): Boolean

    /** 松开空格:停止收音,等最终识别结果。 */
    fun stopVoiceInput()

    /** 丢弃这次识别(换框 / 收窗口 / 关开关 / 服务销毁)。 */
    fun cancelVoiceInput()
}
