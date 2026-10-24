package dev.hwkbd.ime.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 语音识别门面:按设置挑引擎,并保证所有回调都回到主线程。
 *
 * 控制器只认识「开始/停止/取消」三个动作,不知道背后是 Vosk、腾讯还是讯飞;
 * 设置页改了引擎或密钥,这里销毁旧会话,下一次长按用新的。
 */
class VoiceInput(
    private val context: Context,
    private val settingsProvider: () -> VoiceSettings,
    private val onState: (VoiceState, String?) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (VoiceError) -> Unit,
) {

    private val main = Handler(Looper.getMainLooper())
    private var engine: VoiceEngine? = null
    private var engineKey: String? = null

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
    }

    /**
     * 设置变化:引擎/凭据变了就重建;关掉开关立即取消正在收音的会话。
     * 本地引擎顺手预热,免得第一次长按还要等模型加载。
     */
    fun applySettings(settings: VoiceSettings, enabled: Boolean) {
        if (settings.engineKey != engineKey) {
            engine?.cancel()
            engine?.destroy()
            engine = null
            engineKey = settings.engineKey
        }
        if (!enabled) {
            engine?.cancel()
            return
        }
        if (settings.backend == AsrBackend.LOCAL) engineFor(settings).prepare()
    }

    fun start(languageId: String): StartResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.NO_PERMISSION
        }
        val settings = settingsProvider()
        if (!settings.configured) return StartResult.UNAVAILABLE
        if (settings.backend == AsrBackend.LOCAL && languageId != "zh") {
            return StartResult.UNSUPPORTED_LANGUAGE
        }
        return engineFor(settings).start(languageId)
    }

    fun stop() {
        engine?.stop()
    }

    fun cancel() {
        engine?.cancel()
    }

    fun destroy() {
        alive = false
        engine?.destroy()
        engine = null
        engineKey = null
    }

    private fun engineFor(settings: VoiceSettings): VoiceEngine {
        if (settings.engineKey != engineKey) {
            engine?.cancel()
            engine?.destroy()
            engine = null
            engineKey = settings.engineKey
        }
        return engine ?: when (settings.backend) {
            AsrBackend.LOCAL -> LocalVoskEngine(context, bridge)
            AsrBackend.TENCENT -> TencentEngine(settings, bridge)
            AsrBackend.XFYUN -> XfyunEngine(settings, bridge)
        }.also { engine = it }
    }
}
