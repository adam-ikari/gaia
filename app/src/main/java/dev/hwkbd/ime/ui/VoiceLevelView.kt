package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.ceil

/**
 * 语音 HUD 里的音量表:一排竖条,亮起的条数跟着采集响度走。
 *
 * 输入是 [setLevel] 的 0..1(dBFS 映射,见 PcmRecorder)。条数比连续进度条更适合
 * 输入法这条 48dp 的窄条:一眼能看出「有没有收到声音」,不需要读刻度。
 * 上升立即跟上、下降缓慢回落,说话停顿时不至于瞬间归零。
 */
class VoiceLevelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private var target = 0f
    private var shown = 0f
    private var barColor = 0xFFFFFFFF.toInt()

    fun setLevel(level: Float) {
        target = level.coerceIn(0f, 1f)
        if (target > shown) shown = target
        postInvalidateOnAnimation()
    }

    fun setBarColor(color: Int) {
        barColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        shown += (target - shown) * 0.35f
        if (target - shown < 0.01f) shown = target

        val gap = width * 0.04f
        val barWidth = (width - gap * (BARS - 1)) / BARS
        val lit = ceil(shown * BARS).toInt()
        val dim = (barColor and 0x00FFFFFF) or 0x33000000
        for (i in 0 until BARS) {
            val fraction = (i + 1f) / BARS
            val barHeight = height * (0.28f + 0.72f * fraction)
            val left = i * (barWidth + gap)
            rect.set(left, height - barHeight, left + barWidth, height.toFloat())
            paint.color = if (i < lit) barColor else dim
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, paint)
        }
        if (shown != target) postInvalidateOnAnimation()
    }

    private companion object {
        const val BARS = 12
    }
}
