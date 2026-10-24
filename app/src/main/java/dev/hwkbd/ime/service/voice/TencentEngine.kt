package dev.hwkbd.ime.service.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 腾讯云「一句话识别」。
 *
 * 适合「按住说话、松手出结果」:拾音期间只在本地攒 PCM,松手后补 WAV 头、
 * 整段 POST 到 `asr.tencentcloudapi.com`。没有流式部分结果,所以 HUD 在松手后
 * 显示「识别中…」,直到最终文本返回。
 *
 * 免费额度 5000 次/月(以腾讯云当期政策为准),需要在设置页填自己的
 * SecretId / SecretKey —— 密钥只存本机,不能打进 APK。
 */
class TencentEngine(
    private val settings: VoiceSettings,
    private val callbacks: VoiceCallbacks,
) : VoiceEngine {

    private val main = Handler(Looper.getMainLooper())
    private val pcm = ByteArrayOutputStream()
    private val autoStopPosted = AtomicBoolean(false)

    @Volatile
    private var recording = false

    @Volatile
    private var cancelled = false

    private var recorder: PcmRecorder? = null

    /** 本次会话的键盘语言,start 时记下,松手后的识别线程要用。 */
    private var language: String = "zh"

    /** 会话代号:晚到的旧结果不能写进新一次长按。只在主线程改。 */
    @Volatile
    private var generation = 0

    @Volatile
    private var connection: HttpURLConnection? = null

    override fun prepare() = Unit

    override fun start(languageId: String): StartResult {
        if (!settings.tencentReady) return StartResult.UNAVAILABLE
        language = languageId
        cancelled = false
        generation++
        val gen = generation
        autoStopPosted.set(false)
        pcm.reset()
        recording = true
        val rec = PcmRecorder(
            chunkBytes = 3_200,
            onChunk = { chunk ->
                if (!recording || gen != generation) return@PcmRecorder
                if (pcm.size() + chunk.size > MAX_PCM_BYTES) {
                    if (autoStopPosted.compareAndSet(false, true)) {
                        main.post { if (gen == generation) stop() }
                    }
                    return@PcmRecorder
                }
                pcm.write(chunk)
            },
            onLevel = { callbacks.level(it) },
            onError = { message ->
                main.post {
                    if (gen != generation || !recording) return@post
                    recording = false
                    val failed = recorder
                    recorder = null
                    failed?.stop()
                    callbacks.state(VoiceState.IDLE)
                    callbacks.error(VoiceError(VoiceErrorKind.GENERIC, message))
                }
            },
        )
        if (!rec.start()) {
            recording = false
            callbacks.state(VoiceState.IDLE)
            return StartResult.FAILED
        }
        recorder = rec
        callbacks.state(VoiceState.LISTENING)
        return StartResult.STARTED
    }

    override fun stop() {
        if (!recording) return
        recording = false
        recorder?.stop()
        recorder = null
        callbacks.state(VoiceState.RECOGNIZING)
        val audio = pcm.toByteArray()
        if (audio.isEmpty()) {
            callbacks.state(VoiceState.IDLE)
            callbacks.error(VoiceError(VoiceErrorKind.NO_MATCH))
            return
        }
        val gen = generation
        Thread({ recognize(audio, gen) }, "tencent-asr").apply { isDaemon = true }.start()
    }

    override fun cancel() {
        cancelled = true
        generation++
        recording = false
        recorder?.stop()
        recorder = null
        runCatching { connection?.disconnect() }
        connection = null
        autoStopPosted.set(false)
        pcm.reset()
        callbacks.state(VoiceState.IDLE)
    }

    override fun destroy() = cancel()

    private fun recognize(pcmData: ByteArray, gen: Int) {
        val payload = JSONObject()
            .put("ProjectId", 0)
            .put("SubServiceType", 2)
            .put("EngSerViceType", if (language == "en") "16k_en" else "16k_zh")
            .put("SourceType", 1)
            .put("VoiceFormat", "wav")
            .put("Data", Base64.getEncoder().encodeToString(wav(pcmData)))
            .put("DataLen", pcmData.size + WAV_HEADER_SIZE)
            .put("FilterDirty", 0)
            .put("FilterModal", 0)
            .put("ConvertNumMode", 1)
            .put("WordInfo", 0)
            .toString()

        val timestamp = System.currentTimeMillis() / 1000
        val authorization = Tc3Signer.authorization(
            secretId = settings.tencentSecretId,
            secretKey = settings.tencentSecretKey,
            host = HOST,
            payload = payload,
            timestampSeconds = timestamp,
        )

        val conn = runCatching {
            (URL("https://$HOST").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 20_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("X-TC-Action", "SentenceRecognition")
                setRequestProperty("X-TC-Version", "2019-06-14")
                setRequestProperty("X-TC-Timestamp", timestamp.toString())
                setRequestProperty("Authorization", authorization)
            }
        }.getOrElse {
            fail(VoiceError(VoiceErrorKind.GENERIC, it.message), gen, pcmData)
            return
        }
        connection = conn
        try {
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                .orEmpty()
            if (cancelled || gen != generation) return
            if (body.isBlank()) {
                fail(VoiceError(VoiceErrorKind.NETWORK, "HTTP $code 无响应体"), gen, pcmData)
                return
            }
            val response = JSONObject(body).optJSONObject("Response")
            if (response == null) {
                fail(VoiceError(VoiceErrorKind.PROVIDER, "响应缺少 Response"), gen, pcmData)
                return
            }
            val error = response.optJSONObject("Error")
            if (error != null) {
                val errCode = error.optString("Code")
                val msg = error.optString("Message")
                fail(mapError(errCode, msg), gen, pcmData)
                return
            }
            val text = response.optString("Result").trim()
            if (cancelled || gen != generation) return
            callbacks.state(VoiceState.IDLE)
            if (text.isEmpty()) {
                callbacks.error(VoiceError(VoiceErrorKind.NO_MATCH))
            } else {
                callbacks.text(text)
            }
        } catch (e: Exception) {
            if (!cancelled && gen == generation) {
                Log.w(TAG, "腾讯云识别失败", e)
                fail(VoiceError(VoiceErrorKind.NETWORK, e.javaClass.simpleName), gen, pcmData)
            }
        } finally {
            runCatching { conn.disconnect() }
            if (connection === conn) connection = null
        }
    }

    private fun fail(error: VoiceError, gen: Int, audio: ByteArray? = null) {
        if (cancelled || gen != generation) return
        callbacks.state(VoiceState.IDLE)
        if (audio != null && audio.isNotEmpty() && error.kind.isCloudUnavailable()) {
            callbacks.fallbackAudio(audio, error)
        } else {
            callbacks.error(error)
        }
    }

    private fun mapError(code: String, message: String): VoiceError {
        val kind = when {
            code.startsWith("AuthFailure") -> VoiceErrorKind.AUTH
            code.contains("LimitExceeded") || code.contains("RequestLimit") -> VoiceErrorKind.BUSY
            else -> VoiceErrorKind.PROVIDER
        }
        return VoiceError(kind, "$code $message".trim())
    }

    private fun wav(pcmData: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(WAV_HEADER_SIZE + pcmData.size)
        fun le32(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
            out.write((value shr 16) and 0xff)
            out.write((value shr 24) and 0xff)
        }
        fun le16(value: Int) {
            out.write(value and 0xff)
            out.write((value shr 8) and 0xff)
        }
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        le32(36 + pcmData.size)
        out.write("WAVE".toByteArray(Charsets.US_ASCII))
        out.write("fmt ".toByteArray(Charsets.US_ASCII))
        le32(16)
        le16(1) // PCM
        le16(1) // 单声道
        le32(PcmRecorder.SAMPLE_RATE)
        le32(PcmRecorder.SAMPLE_RATE * 2)
        le16(2)
        le16(16)
        out.write("data".toByteArray(Charsets.US_ASCII))
        le32(pcmData.size)
        out.write(pcmData)
        return out.toByteArray()
    }

    companion object {
        private const val TAG = "TencentAsr"
        private const val HOST = "asr.tencentcloudapi.com"
        private const val WAV_HEADER_SIZE = 44
        // 一句话识别上限 60 秒;16k/16bit/单声道 = 32KB/s。
        private const val MAX_PCM_BYTES = 60 * 32_000
    }
}