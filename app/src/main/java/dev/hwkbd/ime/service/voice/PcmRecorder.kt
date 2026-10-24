package dev.hwkbd.ime.service.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 16kHz / 单声道 / PCM16 的录音读取器。
 *
 * 云端与本地共用同一份采集参数,避免"本地 16k、云端 8k"这种只在某一家才复现的偏差。
 * [onChunk] 在采集线程上调用,回调实现必须自己保证线程安全;需要碰 UI 的用 Handler 转主线程。
 */
class PcmRecorder(
    private val chunkBytes: Int,
    private val onChunk: (ByteArray) -> Unit,
    private val onError: (String) -> Unit,
    private val onLevel: (Float) -> Unit = {},
) {

    @Volatile
    private var running = false
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        if (running) return true
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuffer <= 0) {
            onError("AudioRecord 不可用(minBuffer=$minBuffer)")
            return false
        }
        val recorder = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                CHANNEL,
                ENCODING,
                max(minBuffer * 2, chunkBytes * 8),
            )
        }.getOrElse {
            onError("AudioRecord 创建失败:${it.javaClass.simpleName}")
            return false
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            onError("AudioRecord 初始化失败")
            return false
        }
        val started = runCatching { recorder.startRecording() }.isSuccess
        if (!started) {
            recorder.release()
            onError("录音启动失败")
            return false
        }
        record = recorder
        running = true
        thread = Thread({ loop(recorder) }, "voice-pcm").apply {
            isDaemon = true
            start()
        }
        return true
    }

    private fun loop(recorder: AudioRecord) {
        val buffer = ByteArray(chunkBytes)
        while (running) {
            val n = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            if (n > 0) {
                onLevel(levelOf(buffer, n))
                onChunk(buffer.copyOf(n))
            } else if (n < 0) {
                if (running) onError("录音读取失败($n)")
                break
            }
        }
        runCatching { recorder.stop() }
    }

    fun stop() {
        if (!running && record == null) return
        running = false
        // stop() 能让阻塞中的 read() 立刻返回;先停再 join,避免等满一个缓冲区。
        runCatching { record?.stop() }
        thread?.join(500)
        thread = null
        runCatching { record?.release() }
        record = null
    }

    /**
     * 一段 PCM16 的响度,映射到 0..1。
     *
     * 用 dBFS 而不是线性 RMS:人耳是对数的,线性值在正常说话时全挤在 0.05 以下,
     * 音量表几乎不动。-50dB(≈千分之三满幅)当作地板,0dB 当顶。
     */
    private fun levelOf(buffer: ByteArray, n: Int): Float {
        val samples = n / 2
        if (samples == 0) return 0f
        var sum = 0.0
        var i = 0
        while (i + 1 < n) {
            val s = ((buffer[i].toInt() and 0xff) or (buffer[i + 1].toInt() shl 8)).toShort().toInt()
            sum += s.toDouble() * s
            i += 2
        }
        val rms = sqrt(sum / samples)
        if (rms < 1.0) return 0f
        val db = 20.0 * log10(rms / 32_768.0)
        return ((db + 50.0) / 50.0).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}
