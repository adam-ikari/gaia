package dev.hwkbd.ime.service.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipInputStream

/**
 * 本地离线识别:Vosk(Kaldi)+ 内置中文小模型。
 *
 * 模型以官方 zip 放在 assets,首次使用解压到 `filesDir/vosk/…`,之后直接复用。
 * 不联网、不需要账号;代价是模型体积与识别质量,以及中文模型不覆盖英文
 * (英文请选云端,或后续再内置英文模型)。
 *
 * ## 线程模型
 *
 * 拾音与识别在采集线程上,Vosk 模型在 `vosk-load` 线程上加载,最终结果在
 * `vosk-final` 线程上收尾;所有会话状态(recognizer / recorder / generation)
 * 只在主线程改。收尾线程不直接碰这些字段,只拿自己那一份 recognizer 与
 * StringBuilder,结束后回主线程认领。
 */
class LocalVoskEngine(
    private val context: Context,
    private val callbacks: VoiceCallbacks,
) : VoiceEngine {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var collecting = false

    private var model: Model? = null
    private var loading = false
    private var pendingStart = false
    private var stopRequested = false
    private var cancelled = false
    private var destroyed = false
    private var finalizing = false
    private var recognizer: Recognizer? = null
    private var recorder: PcmRecorder? = null
    private var session = StringBuilder()

    /** 每次会话 +1;收尾线程回来时凭它判断自己是不是已经被新会话/取消顶掉。 */
    private var generation = 0

    override fun prepare() {
        if (model != null || loading) return
        loading = true
        Thread({ loadModel() }, "vosk-load").apply { isDaemon = true }.start()
    }

    override fun start(languageId: String): StartResult {
        if (languageId != "zh") {
            callbacks.error(VoiceError(VoiceErrorKind.LANGUAGE))
            return StartResult.UNSUPPORTED_LANGUAGE
        }
        cancelled = false
        stopRequested = false
        if (model == null) {
            pendingStart = true
            callbacks.state(VoiceState.PREPARING)
            prepare()
            return StartResult.STARTED
        }
        return if (beginListening()) StartResult.STARTED else StartResult.FAILED
    }

    override fun stop() {
        if (pendingStart && model == null) {
            // 模型还没就绪就松手了:等加载完直接丢这次会话。
            stopRequested = true
            return
        }
        if (!collecting) return
        collecting = false
        val rec = recorder
        recorder = null
        rec?.stop()
        val r = recognizer ?: run {
            callbacks.state(VoiceState.IDLE)
            return
        }
        recognizer = null
        val finishedSession = session
        session = StringBuilder()
        val gen = generation
        finalizing = true
        callbacks.state(VoiceState.RECOGNIZING)
        Thread({ finish(r, finishedSession, gen) }, "vosk-final").apply { isDaemon = true }.start()
    }

    override fun cancel() {
        cancelled = true
        generation++
        pendingStart = false
        stopRequested = false
        collecting = false
        val rec = recorder
        recorder = null
        rec?.stop()
        // 收尾线程持有自己的 recognizer;这里只关还没交出去的那个。
        runCatching { recognizer?.close() }
        recognizer = null
        session = StringBuilder()
        callbacks.state(VoiceState.IDLE)
    }

    override fun destroy() {
        cancel()
        destroyed = true
        // 收尾/加载线程可能还在用 model;交给它们的回调在主线程关闭。
        if (!finalizing) {
            runCatching { model?.close() }
            model = null
        }
    }

    private fun loadModel() {
        val loaded = runCatching { Model(ensureExtracted().absolutePath) }
        main.post {
            loading = false
            val ok = loaded.getOrNull()
            if (destroyed) {
                ok?.let { runCatching { it.close() } }
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
            model = ok
            if (pendingStart) {
                pendingStart = false
                if (stopRequested || cancelled) {
                    // 模型还没加载完就松手了:这次按压根本没开始录,静默收尾即可,
                    // 不能报「没听清」——用户一个字都还没说。
                    stopRequested = false
                    callbacks.state(VoiceState.IDLE)
                } else if (!beginListening()) {
                    callbacks.error(VoiceError(VoiceErrorKind.GENERIC, "无法开始录音"))
                }
            }
        }
    }

    private fun beginListening(): Boolean {
        val m = model ?: return false
        if (cancelled) return true
        val r = runCatching { Recognizer(m, PcmRecorder.SAMPLE_RATE.toFloat()) }
            .getOrElse {
                Log.w(TAG, "Recognizer 创建失败", it)
                callbacks.error(VoiceError(VoiceErrorKind.GENERIC, "识别器初始化失败"))
                return false
            }
        val thisSession = StringBuilder()
        session = thisSession
        generation++
        val gen = generation
        recognizer = r
        collecting = true
        val rec = PcmRecorder(
            chunkBytes = 3_200,
            onChunk = { chunk -> feed(r, chunk, thisSession) },
            onError = { message ->
                main.post {
                    if (gen != generation || !collecting) return@post
                    collecting = false
                    val failed = recorder
                    recorder = null
                    failed?.stop()
                    runCatching { r.close() }
                    recognizer = null
                    callbacks.state(VoiceState.IDLE)
                    callbacks.error(VoiceError(VoiceErrorKind.GENERIC, message))
                }
            },
        )
        if (!rec.start()) {
            collecting = false
            runCatching { r.close() }
            recognizer = null
            callbacks.state(VoiceState.IDLE)
            return false
        }
        recorder = rec
        callbacks.state(VoiceState.LISTENING)
        return true
    }

    private fun feed(r: Recognizer, chunk: ByteArray, session: StringBuilder) {
        if (!collecting) return
        val done = runCatching { r.acceptWaveForm(chunk, chunk.size) }.getOrDefault(false)
        if (done) appendText(session, JSONObject(r.result).optString("text"))
        val partial = runCatching {
            JSONObject(r.partialResult).optString("partial")
        }.getOrDefault("")
        if (partial.isNotBlank()) {
            val shown = clean(partial)
            main.post {
                if (collecting) callbacks.state(VoiceState.LISTENING, shown)
            }
        }
    }

    private fun finish(r: Recognizer, session: StringBuilder, gen: Int) {
        val tail = runCatching { JSONObject(r.finalResult).optString("text") }.getOrDefault("")
        if (tail.isNotBlank()) appendText(session, tail)
        runCatching { r.close() }
        val text = clean(session.toString())
        main.post {
            finalizing = false
            if (destroyed) {
                runCatching { model?.close() }
                model = null
                return@post
            }
            if (cancelled || gen != generation) return@post
            callbacks.state(VoiceState.IDLE)
            if (text.isBlank()) {
                callbacks.error(VoiceError(VoiceErrorKind.NO_MATCH))
            } else {
                callbacks.text(text)
            }
        }
    }

    private fun appendText(session: StringBuilder, raw: String) {
        val text = clean(raw)
        if (text.isEmpty()) return
        if (session.isNotEmpty()) session.append(' ')
        session.append(text)
    }

    /**
     * Vosk 中文模型的输出以空格分词。中文句子不需要这些空格,而本地引擎只加载中文模型;
     * 直接去掉即可(英文单词在中文模型里是零星出现的,不做特殊保留)。
     */
    private fun clean(raw: String): String = raw.replace(" ", "").trim()

    /** 把官方 zip 解到 filesDir;先解到临时目录再改名,避免中断留下半份模型。 */
    private fun ensureExtracted(): File {
        val root = File(context.filesDir, "vosk")
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
                        // 官方 zip 有顶层目录(MODEL_ID/…),剥掉后再落盘。
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
        private const val TAG = "LocalVosk"
        private const val ASSET_ZIP = "vosk/vosk-model-small-cn-0.22.zip"
        private const val MODEL_ID = "vosk-model-small-cn-0.22"
        private const val READY_MARKER = ".ready"
    }
}
