package dev.hwkbd.ime.service.voice

import android.content.ContextWrapper
import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Collections

class SherpaLocalEngineTest {
    @Test
    fun `filesystem model uses file entry instead of asset manager`() {
        val root = File("/tmp/sherpa-model")
        val marker = Object()
        var passedManager: AssetManager? = AssetManager::class.java.cast(null)
        var passedConfig: com.k2fsa.sherpa.onnx.OfflineRecognizerConfig? = null

        val actual = buildSherpaRecognizer(root) { manager, config ->
            passedManager = manager
            passedConfig = config
            marker
        }

        assertSame(marker, actual)
        assertNull(passedManager)
        assertEquals(File(root, "model.int8.onnx").absolutePath, passedConfig!!.modelConfig.senseVoice.model)
        assertEquals(File(root, "tokens.txt").absolutePath, passedConfig!!.modelConfig.tokens)
    }

    @Test
    fun `cancel drops late model result`() {
        val events = mutableListOf<String>()
        val callbacks = object : VoiceCallbacks {
            override fun state(state: VoiceState, partial: String?) { events += "state:$state" }
            override fun text(text: String) { events += "text:$text" }
            override fun error(error: VoiceError) { events += "error:${error.kind}" }
        }
        val posted = Collections.synchronizedList(mutableListOf<Runnable>())
        val engine = SherpaLocalEngine(ContextWrapper(null), callbacks, postToMain = { posted.add(it) })

        assertEquals(StartResult.FAILED, engine.start("zh"))
        assertEquals(emptyList<String>(), events)
        engine.cancel()
        events.clear()

        val late = synchronized(posted) { posted.toList() }
        late.forEach { it.run() }
        engine.onModelLoaded(Result.success(null))
        assertTrue("迟到的加载结果不得再触发回调", events.isEmpty())
    }
}
