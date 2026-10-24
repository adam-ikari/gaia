package dev.hwkbd.ime.service.voice

import android.content.SharedPreferences

enum class AsrBackend {
    LOCAL,
    TENCENT,
    XFYUN;

    companion object {
        fun fromPref(value: String?): AsrBackend =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: LOCAL
    }
}

/**
 * 语音识别的全部可配置项。每次开始识别时现读,设置页改完立刻生效。
 *
 * 云端密钥只存本机 SharedPreferences —— 不能打进 APK,否则反编译就能拿走。
 */
data class VoiceSettings(
    val backend: AsrBackend = AsrBackend.LOCAL,
    val tencentSecretId: String = "",
    val tencentSecretKey: String = "",
    val xfyunAppId: String = "",
    val xfyunApiKey: String = "",
    val xfyunApiSecret: String = "",
) {

    val tencentReady: Boolean
        get() = tencentSecretId.isNotBlank() && tencentSecretKey.isNotBlank()

    val xfyunReady: Boolean
        get() = xfyunAppId.isNotBlank() && xfyunApiKey.isNotBlank() && xfyunApiSecret.isNotBlank()

    val configured: Boolean
        get() = when (backend) {
            AsrBackend.LOCAL -> true
            AsrBackend.TENCENT -> tencentReady
            AsrBackend.XFYUN -> xfyunReady
        }

    /**
     * 引擎实例的复用键。本地只认 backend(改云端密钥不该把已加载的 Paraformer 模型丢掉);
     * 云端把凭据算进去,换了账号/密钥就重建会话。
     */
    val engineKey: String
        get() = when (backend) {
            AsrBackend.LOCAL -> "local"
            AsrBackend.TENCENT -> "tencent|$tencentSecretId|$tencentSecretKey"
            AsrBackend.XFYUN -> "xfyun|$xfyunAppId|$xfyunApiKey|$xfyunApiSecret"
        }

    companion object {
        const val PREF_ENGINE = "voice_engine"
        const val PREF_TENCENT_ID = "tencent_secret_id"
        const val PREF_TENCENT_KEY = "tencent_secret_key"
        const val PREF_XFYUN_APP_ID = "xfyun_app_id"
        const val PREF_XFYUN_API_KEY = "xfyun_api_key"
        const val PREF_XFYUN_API_SECRET = "xfyun_api_secret"

        fun read(p: SharedPreferences): VoiceSettings = VoiceSettings(
            backend = AsrBackend.fromPref(p.getString(PREF_ENGINE, null)),
            tencentSecretId = p.getString(PREF_TENCENT_ID, "").orEmpty().trim(),
            tencentSecretKey = p.getString(PREF_TENCENT_KEY, "").orEmpty().trim(),
            xfyunAppId = p.getString(PREF_XFYUN_APP_ID, "").orEmpty().trim(),
            xfyunApiKey = p.getString(PREF_XFYUN_API_KEY, "").orEmpty().trim(),
            xfyunApiSecret = p.getString(PREF_XFYUN_API_SECRET, "").orEmpty().trim(),
        )
    }
}
