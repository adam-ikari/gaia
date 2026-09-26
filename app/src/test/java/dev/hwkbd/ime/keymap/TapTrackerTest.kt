package dev.hwkbd.ime.keymap

import org.junit.Assert.assertEquals
import org.junit.Test

class TapTrackerTest {

    @Test
    fun `window 内连续敲击计数`() {
        var t = 0L
        val tr = TapTracker(now = { t }, windowMs = 400)
        assertEquals(1, tr.tap())
        t = 100
        assertEquals(2, tr.tap())
        t = 250
        assertEquals(3, tr.tap())
        assertEquals(3, tr.peek())
    }

    @Test
    fun `窗口过期后从1重新开始`() {
        var t = 0L
        val tr = TapTracker(now = { t }, windowMs = 400)
        assertEquals(1, tr.tap())
        t = 401
        assertEquals(1, tr.tap())
    }

    @Test
    fun `恰好压线算连续`() {
        var t = 0L
        val tr = TapTracker(now = { t }, windowMs = 400)
        tr.tap()
        t = 400
        assertEquals(2, tr.tap())
    }
}
