package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** 音量条的纯数学:NaN 归一、升降都收敛(收敛判据缺绝对值会永远自绘)。 */
class VoiceLevelEasingTest {

    @Test
    fun `NaN 归零 越界值夹到区间`() {
        assertEquals(0f, normalizeVoiceLevel(Float.NaN), 0f)
        assertEquals(0f, normalizeVoiceLevel(-1f), 0f)
        assertEquals(1f, normalizeVoiceLevel(2f), 0f)
        assertEquals(0.5f, normalizeVoiceLevel(0.5f), 0f)
    }

    @Test
    fun `上升第一帧不跳到目标`() {
        assertEquals(0.35f, approachVoiceLevel(0f, 1f), 1e-6f)
    }

    @Test
    fun `升降都吸附到目标 不留下恒真的自绘条件`() {
        var shown = 0f
        repeat(1000) { shown = approachVoiceLevel(shown, 1f) }
        assertEquals(1f, shown, 0f)
        repeat(1000) { shown = approachVoiceLevel(shown, 0f) }
        assertEquals(0f, shown, 0f)
    }
}
