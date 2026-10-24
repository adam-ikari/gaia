package dev.hwkbd.ime.service.voice

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.util.Base64
import java.util.TreeMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞「语音听写(流式版)」。
 *
 * 与腾讯的区别是边说边传:拾音期间每 40ms 发一帧,部分结果直接进 HUD。
 * 代价是要维护 WebSocket 会话与 HMAC 鉴权;免费额度按讯飞当期政策
 * (新用户 500 次/日 + 试用包)。
 */
class XfyunEngine(
    private val settings: VoiceSettings,
    private val callbacks: VoiceCallbacks,
) : VoiceEngine {

    private val main = Handler(Looper.getMainLooper())
    private val segments = TreeMap<Int, String>()

    /** 已发给讯飞的 PCM 副本:云端失败时用它回落本地模型。上限同 [MAX_PCM_BYTES]。 */
    private val pcm = java.io.ByteArrayOutputStream()
    private val autoStopPosted = AtomicBoolean(false)
    private var timeout: Runnable? = null

    private var webSocket: WebSocket? = null
    private var recorder: PcmRecorder? = null
    private var language: String = "zh"
    private var firstFrame = true
    private var totalBytes = 0L
    private var lastPartial: String? = null

    @Volatile
    private var connected = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var finished = false

    @Volatile
    private var stopRequested = false

    /** 会话代号:旧 WebSocket/超时/采集线程的迟到回调不能影响新一次长按。只在主线程改。 */
    @Volatile
    private var generation = 0

    override fun prepare() = Unit

    override fun start(languageId: String): StartResult {
        if (!settings.xfyunReady) return StartResult.UNAVAILABLE
        cancelled = false
        finished = false
        stopRequested = false
        connected = false
        firstFrame = true
        totalBytes = 0
        lastPartial = null
        segments.clear()
        pcm.reset()
        language = languageId
        generation++
        val gen = generation

        val date = XfyunSigner.rfc1123()
        val authorization = XfyunSigner.authorization(settings.xfyunApiKey, settings.xfyunApiSecret, date)
        val url = "https://${XfyunSigner.HOST}/v2/iat".toHttpUrl().newBuilder()
            .addQueryParameter("authorization", authorization)
            .addQueryParameter("date", date)
            .addQueryParameter("host", XfyunSigner.HOST)
            .build()
        callbacks.state(VoiceState.CONNECTING)
        webSocket = client.newWebSocket(
            Request.Builder().url(url).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    main.post { if (gen == generation) onConnected(gen) }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    main.post { if (gen == generation) onServerMessage(text, gen) }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    main.post { if (gen == generation) onClosed(t.message) }
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    main.post {
                        if (gen == generation) onClosed(reason.ifBlank { "WebSocket closed ($code)" })
                    }
                }
            },
        )
        return StartResult.STARTED
    }

    override fun stop() {
        if (finished) return
        if (!connected) {
            // 还没握手就松手了:没有音频可发,直接按"没听清"收尾。
            stopRequested = true
            if (!cancelled) fail(VoiceError(VoiceErrorKind.NO_MATCH))
            return
        }
        stopRecorder()
        if (firstFrame) {
            fail(VoiceError(VoiceErrorKind.NO_MATCH))
            return
        }
        callbacks.state(VoiceState.RECOGNIZING)
        webSocket?.send("{\"data\":{\"status\":2}}")
        scheduleTimeout(generation)
    }

    override fun cancel() {
        cancelled = true
        generation++
        finished = true
        stopRequested = false
        cancelTimeout()
        stopRecorder()
        webSocket?.cancel()
        webSocket = null
        connected = false
        segments.clear()
        pcm.reset()
        callbacks.state(VoiceState.IDLE)
    }

    override fun destroy() = cancel()

    private fun onConnected(gen: Int) {
        if (cancelled || finished || gen != generation) return
        connected = true
        if (stopRequested) {
            fail(VoiceError(VoiceErrorKind.NO_MATCH))
            return
        }
        val rec = PcmRecorder(
            chunkBytes = 1_280,
            onChunk = { chunk ->
                if (finished || cancelled || gen != generation) return@PcmRecorder
                totalBytes += chunk.size
                pcm.write(chunk)
                sendAudio(chunk, if (firstFrame) 0 else 1)
                if (totalBytes >= MAX_PCM_BYTES && autoStopPosted.compareAndSet(false, true)) {
                    main.post { if (gen == generation) stop() }
                }
            },
            onLevel = { callbacks.level(it) },
            onError = { message ->
                main.post {
                    if (gen == generation) fail(VoiceError(VoiceErrorKind.GENERIC, message))
                }
            },
        )
        if (!rec.start()) {
            fail(VoiceError(VoiceErrorKind.GENERIC, "无法开始录音"))
            return
        }
        recorder = rec
        callbacks.state(VoiceState.LISTENING)
    }

    private fun sendAudio(chunk: ByteArray, status: Int) {
        val audio = Base64.getEncoder().encodeToString(chunk)
        val data = JSONObject()
            .put("status", status)
            .put("format", "audio/L16;rate=16000")
            .put("encoding", "raw")
            .put("audio", audio)
        val root = JSONObject().put("data", data)
        if (status == 0) {
            root.put("common", JSONObject().put("app_id", settings.xfyunAppId))
            val business = JSONObject()
                .put("language", if (language == "en") "en_us" else "zh_cn")
                .put("domain", "iat")
                .put("ptt", 1)
                .put("eos", 3_000)
            if (language != "en") business.put("accent", "mandarin")
            root.put("business", business)
            firstFrame = false
        }
        webSocket?.send(root.toString())
    }

    private fun onServerMessage(text: String, gen: Int) {
        if (finished || cancelled || gen != generation) return
        val root = runCatching { JSONObject(text) }.getOrNull() ?: return
        val code = root.optInt("code", 0)
        if (code != 0) {
            val message = root.optString("message")
            val kind = when (code) {
                10105, 10106, 11200 -> VoiceErrorKind.AUTH
                10165 -> VoiceErrorKind.NO_MATCH
                else -> VoiceErrorKind.PROVIDER
            }
            fail(VoiceError(kind, "讯飞 $code $message".trim()))
            return
        }
        val data = root.optJSONObject("data") ?: return
        val result = data.optJSONObject("result")
        if (result != null) {
            val sn = result.optInt("sn", segments.size)
            val sb = StringBuilder()
            val words = result.optJSONArray("ws")
            if (words != null) {
                for (i in 0 until words.length()) {
                    val cw = words.optJSONObject(i)?.optJSONArray("cw") ?: continue
                    for (j in 0 until cw.length()) {
                        sb.append(cw.optJSONObject(j)?.optString("w").orEmpty())
                    }
                }
            }
            if (sb.isNotEmpty()) segments[sn] = sb.toString()
            val partial = joinSegments()
            if (partial.isNotEmpty() && partial != lastPartial) {
                lastPartial = partial
                callbacks.state(VoiceState.RECOGNIZING, partial)
            }
        }
        val status = data.optInt("status", -1)
        if (status == 2 || result?.optBoolean("ls") == true) complete()
    }

    private fun onClosed(reason: String?) {
        if (cancelled || finished) return
        val text = joinSegments()
        if (text.isNotBlank()) {
            complete()
        } else {
            fail(VoiceError(VoiceErrorKind.NETWORK, reason))
        }
    }

    private fun complete() {
        if (finished) return
        finished = true
        cancelTimeout()
        stopRecorder()
        connected = false
        webSocket?.close(1000, "done")
        webSocket = null
        val text = joinSegments()
        segments.clear()
        pcm.reset()
        callbacks.state(VoiceState.IDLE)
        if (text.isBlank()) {
            callbacks.error(VoiceError(VoiceErrorKind.NO_MATCH))
        } else {
            callbacks.text(text)
        }
    }

    private fun fail(error: VoiceError) {
        if (finished) return
        finished = true
        cancelTimeout()
        stopRecorder()
        connected = false
        webSocket?.cancel()
        webSocket = null
        segments.clear()
        val audio = pcm.toByteArray()
        pcm.reset()
        callbacks.state(VoiceState.IDLE)
        if (audio.isNotEmpty() && error.kind.isCloudUnavailable()) {
            callbacks.fallbackAudio(audio, error)
        } else {
            callbacks.error(error)
        }
    }

    private fun joinSegments(): String {
        val words = segments.values.toList()
        // 中文不需要词间空格;英文按空格拼回去。
        return if (language == "en") words.joinToString(" ") else words.joinToString("")
    }

    private fun stopRecorder() {
        recorder?.stop()
        recorder = null
    }

    private fun scheduleTimeout(gen: Int) {
        cancelTimeout()
        val runnable = Runnable {
            if (finished || cancelled || gen != generation) return@Runnable
            Log.w(TAG, "讯飞最终结果超时")
            val text = joinSegments()
            if (text.isNotBlank()) complete() else fail(VoiceError(VoiceErrorKind.NETWORK, "识别结果超时"))
        }
        timeout = runnable
        main.postDelayed(runnable, RESULT_TIMEOUT_MS)
    }

    private fun cancelTimeout() {
        timeout?.let { main.removeCallbacks(it) }
        timeout = null
    }

    companion object {
        private const val TAG = "XfyunAsr"
        private const val RESULT_TIMEOUT_MS = 15_000L
        // 讯飞单次听写上限 60 秒;16k/16bit/单声道 = 32KB/s。
        private const val MAX_PCM_BYTES = 60 * 32_000
        private val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }
}
