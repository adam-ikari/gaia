package dev.hwkbd.ime.service.voice

/**
 * 语音识别的状态与错误语义。
 *
 * 这些类型与引擎无关 —— 控制器只管长按/松手,按状态渲染 HUD,按错误种类 Toast,
 * 不需要知道背后是哪一个模型在解码。
 */
enum class VoiceState {
    /** 本地模型首次解压/加载;此时还没有开始拾音。 */
    PREPARING,

    /** 正在拾音;partial 是可选的部分结果。 */
    LISTENING,

    /** 已停止拾音,等最终结果。 */
    RECOGNIZING,

    IDLE,
}

enum class VoiceErrorKind {
    NO_PERMISSION,

    /** 本地模型解压/加载失败,这次识别根本没法做。 */
    UNAVAILABLE,

    NO_MATCH,

    /** 当前键盘语言不被本地模型支持。 */
    LANGUAGE,

    GENERIC,
}

data class VoiceError(
    val kind: VoiceErrorKind,
    /** 给日志/Toast 的补充信息,可能为空。 */
    val detail: String? = null,
)

enum class StartResult {
    STARTED,
    NO_PERMISSION,
    UNAVAILABLE,
    UNSUPPORTED_LANGUAGE,
    FAILED,
}

interface VoiceCallbacks {
    fun state(state: VoiceState, partial: String? = null)
    fun text(text: String)
    fun error(error: VoiceError)

    /** 采集音量,0..1,给 HUD 的音量表用。 */
    fun level(level: Float) {}
}
