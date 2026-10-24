package dev.hwkbd.ime.service.voice

/**
 * 语音识别引擎的公共契约。
 *
 * 三套实现(本地 sherpa-onnx / 腾讯云 / 讯飞)只在「音频往哪送、结果怎么回」上不同,
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

/**
 * 云端这次给不出结果(断网、密钥失效、额度用尽、服务端错误),
 * 可以拿整段录音改走本地模型。[VoiceErrorKind.NO_MATCH] 不算 —— 那是真的没说话。
 */
internal fun VoiceErrorKind.isCloudUnavailable(): Boolean = when (this) {
    VoiceErrorKind.NETWORK,
    VoiceErrorKind.AUTH,
    VoiceErrorKind.BUSY,
    VoiceErrorKind.PROVIDER,
    VoiceErrorKind.GENERIC,
    -> true
    else -> false
}

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

    /** 采集音量,0..1。云端与本地共用,给 HUD 的音量表用。 */
    fun level(level: Float) {}

    /**
     * 云端这次拿不到结果(断网/鉴权失败/额度用尽),但仍握着整段录音:
     * 交给上层用本地模型重试。不是所有错误都该走这里(NO_MATCH 是"真的没说话")。
     */
    fun fallbackAudio(pcm: ByteArray, reason: VoiceError) {}
}

interface VoiceEngine {
    /** 预热(本地模型解压/加载)。云端实现为空。 */
    fun prepare()

    fun start(languageId: String): StartResult
    fun stop()
    fun cancel()
    fun destroy()
}
