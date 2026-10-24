package dev.hwkbd.ime.service.voice

/**
 * 语音识别引擎的公共契约。
 *
 * 三套实现(本地 Vosk / 腾讯云 / 讯飞)只在「音频往哪送、结果怎么回」上不同,
 * 对上层暴露同一组状态与错误,控制器只管长按/松手,不知道背后是谁在识别。
 */
enum class VoiceState {
    /** 本地模型首次解压/加载;此时还没有开始拾音。 */
    PREPARING,

    /** 云端正在握手中。 */
    CONNECTING,

    /** 正在拾音;partial 是可选的部分结果。 */
    LISTENING,

    /** 已停止拾音,等最终结果。 */
    RECOGNIZING,

    IDLE,
}

enum class VoiceErrorKind {
    NO_PERMISSION,
    UNAVAILABLE,
    BUSY,
    NO_MATCH,
    NETWORK,
    LANGUAGE,
    AUTH,
    PROVIDER,
    GENERIC,
}

data class VoiceError(
    val kind: VoiceErrorKind,
    /** 给日志/Toast 的补充信息,可能为空;不要放密钥。 */
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
}

interface VoiceEngine {
    /** 预热(本地模型解压/加载)。云端实现为空。 */
    fun prepare()

    fun start(languageId: String): StartResult
    fun stop()
    fun cancel()
    fun destroy()
}
