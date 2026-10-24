package dev.hwkbd.ime.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 语音识别门面:按设置挑引擎,并保证所有回调都回到主线程。
 *
 * 控制器只认识「开始/停止/取消」三个动作,不知道背后是本地 sherpa-onnx、腾讯还是讯飞;
 * 设置页改了引擎或密钥,这里销毁旧会话,下一次长按用新的。
 *
 * ## 云端失败自动回落本地
 *
 * 云端不可调用(断网、密钥失效、额度用尽)时,不把这次按压白白丢掉:
 * 引擎握着整段录音交给 [VoiceCallbacks.fallbackAudio],这里用内置 Paraformer 再识别一遍。
 * 回落过一次之后,接下来 [CLOUD_RETRY_MS] 内直接走本地,免得每次都要等云端超时;
 * 到期后再试云端(额度可能恢复、密钥可能已经改对)。
 *
 * 云端没填密钥时同理:本地模型是随包内置的,不需要任何配置。
 */
class VoiceInput(
    private val context: Context,
    private val settingsProvider: () -> VoiceSettings,
    private val onState: (VoiceState, String?) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (VoiceError) -> Unit,
    private val onLevel: (Float) -> Unit = {},
    private val onFallback: () -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())
    private var engine: VoiceEngine? = null
    private var engineKey: String? = null

    /** 本地引擎单独持有:云端引擎换代/销毁时,已解压的模型不该跟着丢。 */
    private var local: SherpaLocalEngine? = null

    /** 本次按压的语言;云端失败后靠它判断本地模型能不能接。 */
    private var sessionLanguage = "zh"

    /** 云端冷却截止(ElapsedRealtime)。期间直接走本地。 */
    private var cloudDownUntil = 0L

    /** 回落提示只报一次,别每次按压都弹。 */
    private var fallbackNotifiedUntil = 0L

    @Volatile
    private var alive = true

    private val bridge = object : VoiceCallbacks {
        override fun state(state: VoiceState, partial: String?) {
            main.post { if (alive) onState(state, partial) }
        }

        override fun text(text: String) {
            main.post { if (alive) onText(text) }
        }

        override fun error(error: VoiceError) {
            main.post { if (alive) onError(error) }
        }

        override fun level(level: Float) {
            main.post { if (alive) onLevel(level) }
        }

        override fun fallbackAudio(pcm: ByteArray, reason: VoiceError) {
            main.post { if (alive) handleCloudFallback(pcm, reason) }
        }
    }

    /**
     * 设置变化:引擎/凭据变了就重建;关掉开关立即取消正在收音的会话。
     * 本地模型**始终**预热 —— 它不只是本地引擎,也是云端失败时的兜底。
     */
    fun applySettings(settings: VoiceSettings, enabled: Boolean) {
        if (settings.engineKey != engineKey) {
            engine?.cancel()
            engine?.destroy()
            if (engine === local) local = null
            engine = null
            engineKey = settings.engineKey
        }
        if (!enabled) {
            engine?.cancel()
            return
        }
        localEngine().prepare()
    }

    fun start(languageId: String): StartResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.NO_PERMISSION
        }
        val settings = settingsProvider()
        sessionLanguage = languageId

        if (!settings.configured) {
            if (settings.backend == AsrBackend.LOCAL) return StartResult.UNAVAILABLE
            // 选了云端却没填密钥:本地模型不需要配置,直接用它。
            if (languageId != "zh") return StartResult.UNSUPPORTED_LANGUAGE
            noteFallback()
            return localEngine().start(languageId)
        }
        if (settings.backend != AsrBackend.LOCAL && languageId == "zh" &&
            SystemClock.elapsedRealtime() < cloudDownUntil
        ) {
            return localEngine().start(languageId)
        }
        if (settings.backend == AsrBackend.LOCAL && languageId != "zh") {
            return StartResult.UNSUPPORTED_LANGUAGE
        }
        return engineFor(settings).start(languageId)
    }

    fun stop() {
        engine?.stop()
        if (local !== engine) local?.stop()
    }

    fun cancel() {
        engine?.cancel()
        if (local !== engine) local?.cancel()
    }

    fun destroy() {
        alive = false
        engine?.destroy()
        if (local !== engine) local?.destroy()
        engine = null
        local = null
        engineKey = null
    }

    private fun handleCloudFallback(pcm: ByteArray, reason: VoiceError) {
        if (sessionLanguage != "zh" || pcm.isEmpty()) {
            onError(reason)
            return
        }
        cloudDownUntil = SystemClock.elapsedRealtime() + CLOUD_RETRY_MS
        noteFallback()
        localEngine().recognize(pcm, sessionLanguage)
    }

    private fun noteFallback() {
        val now = SystemClock.elapsedRealtime()
        if (now < fallbackNotifiedUntil) return
        fallbackNotifiedUntil = now + CLOUD_RETRY_MS
        onFallback()
    }

    private fun localEngine(): SherpaLocalEngine =
        local ?: SherpaLocalEngine(context, bridge).also { local = it }

    private fun engineFor(settings: VoiceSettings): VoiceEngine {
        if (settings.engineKey != engineKey) {
            engine?.cancel()
            engine?.destroy()
            if (engine === local) local = null
            engine = null
            engineKey = settings.engineKey
        }
        return engine ?: when (settings.backend) {
            AsrBackend.LOCAL -> localEngine()
            AsrBackend.TENCENT -> TencentEngine(settings, bridge)
            AsrBackend.XFYUN -> XfyunEngine(settings, bridge)
        }.also { engine = it }
    }

    private companion object {
        /** 云端失败后先走本地多久,再重新尝试云端。 */
        const val CLOUD_RETRY_MS = 5 * 60_000L
    }
}
