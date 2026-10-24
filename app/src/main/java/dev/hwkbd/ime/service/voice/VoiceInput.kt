package dev.hwkbd.ime.service.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat

/**
 * 语音识别门面:保证所有回调都回到主线程。
 *
 * 控制器只认识「开始/停止/取消」三个动作;识别本身由内置的 sherpa-onnx + Paraformer
 * 完成,不联网、不需要任何配置。
 */
class VoiceInput(
    private val context: Context,
    private val onState: (VoiceState, String?) -> Unit,
    private val onText: (String) -> Unit,
    private val onError: (VoiceError) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {

    private val main = Handler(Looper.getMainLooper())

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
    }

    private val engine = SherpaLocalEngine(context, bridge)

    /** 开关关掉时立即取消正在收音的会话;开着时预热模型,别让用户长按时等解压。 */
    fun applySettings(enabled: Boolean) {
        if (enabled) engine.prepare() else engine.cancel()
    }

    fun start(languageId: String): StartResult {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return StartResult.NO_PERMISSION
        }
        return engine.start(languageId)
    }

    fun stop() = engine.stop()

    fun cancel() = engine.cancel()

    fun destroy() {
        alive = false
        engine.destroy()
    }
}
