package dev.hwkbd.ime.service.voice

import android.content.res.AssetManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.io.File

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
}
