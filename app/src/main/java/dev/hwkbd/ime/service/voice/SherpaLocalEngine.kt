package dev.hwkbd.ime.service.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 本地离线识别:sherpa-onnx + Paraformer-zh-small。
 *
 * 非流式(整段录完再解码),正好匹配「按住说、松手出字」的交互:
 * 拾音期间不占用解码器,松手后一次性出结果。
 *
 * 模型以 zip 放在 assets,首次使用解压到 `filesDir/sherpa/…`,之后直接复用。
 * 不联网、不需要账号、没有任何可配置项。
 */
class SherpaLocalEngine(
    private val context: Context,
    private val callbacks: VoiceCallbacks,
) {

    private val main = Handler(Looper.getMainLooper())
    private val pcm = ByteArrayOutputStream()

    @Volatile
    private var collecting = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var destroyed = false

    /** 解码线程持有 recognizer 期间,销毁要等它收尾(见 [destroy])。 */
    @Volatile
    private var decoding = false

    private var recognizer: OfflineRecognizer? = null
    private var loading = false
    private var pendingStart = false
    private var stopRequested = false
    private var recorder: PcmRecorder? = null
    private var generation = 0

    fun prepare() {
        if (recognizer != null || loading || destroyed) return
        loading = true
        Thread({ loadModel() }, "sherpa-load").apply { isDaemon = true }.start()
    }

    fun start(languageId: String): StartResult {
        if (languageId != "zh") {
            callbacks.error(VoiceError(VoiceErrorKind.LANGUAGE))
            return StartResult.UNSUPPORTED_LANGUAGE
        }
        cancelled = false
        stopRequested = false
        if (recognizer == null) {
            pendingStart = true
            callbacks.state(VoiceState.PREPARING)
            prepare()
            return StartResult.STARTED
        }
        return if (beginListening()) StartResult.STARTED else StartResult.FAILED
    }

    fun stop() {
        if (pendingStart && recognizer == null) {
            stopRequested = true
            return
        }
        if (!collecting) return
        collecting = false
        recorder?.stop()
        recorder = null
        val audio = pcm.toByteArray()
        pcm.reset()
        if (audio.isEmpty()) {
            callbacks.state(VoiceState.IDLE)
            callbacks.error(VoiceError(VoiceErrorKind.NO_MATCH))
            return
        }
        callbacks.state(VoiceState.RECOGNIZING)
        val gen = generation
        startDecode(audio, gen)
    }

    fun cancel() {
        cancelled = true
        generation++
        pendingStart = false
        stopRequested = false
        collecting = false
        recorder?.stop()
        recorder = null
        pcm.reset()
        callbacks.state(VoiceState.IDLE)
    }

    fun destroy() {
        cancel()
        destroyed = true
        if (!decoding) releaseRecognizer()
    }

    private fun loadModel() {
        val loaded = runCatching { buildRecognizer(ensureExtracted()) }
        main.post {
            loading = false
            val ok = loaded.getOrNull()
            if (destroyed) {
                ok?.let { runCatching { it.release() } }
                return@post
            }
            if (ok == null) {
                Log.w(TAG, "本地语音模型加载失败", loaded.exceptionOrNull())
                if (pendingStart) {
                    pendingStart = false
                    callbacks.state(VoiceState.IDLE)
                    callbacks.error(VoiceError(VoiceErrorKind.UNAVAILABLE, "本地模型加载失败"))
                }
                return@post
            }
            recognizer = ok
            if (pendingStart) {
                pendingStart = false
                if (stopRequested || cancelled) {
                    // 模型还没加载完就松手了:这次按压根本没开始录,静默收尾即可。
                    stopRequested = false
                    callbacks.state(VoiceState.IDLE)
                } else if (!beginListening()) {
                    callbacks.error(VoiceError(VoiceErrorKind.GENERIC, "无法开始录音"))
                }
            }
        }
    }

    private fun buildRecognizer(root: File): OfflineRecognizer {
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = PcmRecorder.SAMPLE_RATE, featureDim = 80),
            modelConfig = OfflineModelConfig(
                paraformer = OfflineParaformerModelConfig(
                    model = File(root, MODEL_FILE).absolutePath,
                ),
                tokens = File(root, TOKENS_FILE).absolutePath,
                numThreads = 2,
                provider = "cpu",
            ),
        )
        return OfflineRecognizer(context.assets, config)
    }

    private fun beginListening(): Boolean {
        if (recognizer == null) return false
        if (cancelled) return true
        generation++
        val gen = generation
        pcm.reset()
        collecting = true
        val rec = PcmRecorder(
            chunkBytes = 3_200,
            onChunk = { chunk ->
                if (collecting && gen == generation) pcm.write(chunk)
            },
            onLevel = { callbacks.level(it) },
            onError = { message ->
                main.post {
                    if (gen != generation || !collecting) return@post
                    collecting = false
                    val failed = recorder
                    recorder = null
                    failed?.stop()
                    callbacks.state(VoiceState.IDLE)
                    callbacks.error(VoiceError(VoiceErrorKind.GENERIC, message))
                }
            },
        )
        if (!rec.start()) {
            collecting = false
            callbacks.state(VoiceState.IDLE)
            return false
        }
        recorder = rec
        callbacks.state(VoiceState.LISTENING)
        return true
    }

    private fun startDecode(audio: ByteArray, gen: Int) {
        decoding = true
        Thread({ finish(audio, gen) }, "sherpa-final").apply { isDaemon = true }.start()
    }

    private fun finish(audio: ByteArray, gen: Int) {
        val result = runCatching { decodeAudio(audio) }
        main.post {
            decoding = false
            if (destroyed) {
                releaseRecognizer()
                return@post
            }
            if (cancelled || gen != generation) return@post
            callbacks.state(VoiceState.IDLE)
            val text = result.getOrNull()
            when {
                result.isFailure -> {
                    Log.w(TAG, "本地解码失败", result.exceptionOrNull())
                    callbacks.error(VoiceError(VoiceErrorKind.GENERIC, "本地解码失败"))
                }
                text.isNullOrBlank() -> callbacks.error(VoiceError(VoiceErrorKind.NO_MATCH))
                else -> callbacks.text(text)
            }
        }
    }

    private fun decodeAudio(audio: ByteArray): String {
        val rec = recognizer ?: throw IllegalStateException("本地模型未加载")
        val samples = FloatArray(audio.size / 2)
        var i = 0
        var j = 0
        while (i + 1 < audio.size) {
            val s = ((audio[i].toInt() and 0xff) or (audio[i + 1].toInt() shl 8)).toShort()
            samples[j++] = s / 32_768f
            i += 2
        }
        // OfflineRecognizer 不是线程安全的:上一次解码可能还没收尾。
        return synchronized(rec) {
            val stream = rec.createStream()
            try {
                stream.acceptWaveform(samples, PcmRecorder.SAMPLE_RATE)
                rec.decode(stream)
                rec.getResult(stream).text.trim().replace(" ", "")
            } finally {
                stream.release()
            }
        }
    }

    private fun releaseRecognizer() {
        runCatching { recognizer?.release() }
        recognizer = null
    }

    /** 把模型 zip 解到 filesDir;先解到临时目录再改名,避免中断留下半份模型。 */
    private fun ensureExtracted(): File {
        val root = File(context.filesDir, "sherpa")
        val target = File(root, MODEL_ID)
        if (File(target, READY_MARKER).isFile()) return target

        val temp = File(root, "$MODEL_ID.tmp")
        temp.deleteRecursively()
        temp.mkdirs()
        try {
            context.assets.open(ASSET_ZIP).use { raw ->
                ZipInputStream(BufferedInputStream(raw)).use { zip ->
                    while (true) {
                        val entry = zip.nextEntry ?: break
                        // zip 里有顶层目录(MODEL_ID/…),剥掉后再落盘。
                        val relative = entry.name.substringAfter('/', missingDelimiterValue = "")
                        if (relative.isNotEmpty()) {
                            val out = File(temp, relative)
                            val canonical = out.canonicalFile
                            if (!canonical.path.startsWith(temp.canonicalPath + File.separator)) {
                                throw IOException("zip entry 越界: ${entry.name}")
                            }
                            if (entry.isDirectory) {
                                canonical.mkdirs()
                            } else {
                                canonical.parentFile?.mkdirs()
                                BufferedOutputStream(FileOutputStream(canonical), 64 * 1024).use { zip.copyTo(it) }
                            }
                        }
                        zip.closeEntry()
                    }
                }
            }
            File(temp, READY_MARKER).writeText(MODEL_ID)
            target.deleteRecursively()
            if (!temp.renameTo(target)) throw IOException("模型目录改名失败")
            return target
        } catch (e: Exception) {
            temp.deleteRecursively()
            throw e
        }
    }

    companion object {
        private const val TAG = "SherpaLocal"
        private const val ASSET_ZIP = "sherpa/paraformer-zh-small.zip"
        private const val MODEL_ID = "paraformer-zh-small"
        private const val MODEL_FILE = "model.int8.onnx"
        private const val TOKENS_FILE = "tokens.txt"
        private const val READY_MARKER = ".ready"
    }
}
