package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.R
import dev.hwkbd.ime.ime.ImeState

/**
 * 候选栏:点选候选直接上屏;在候选上向上滑动 = 选字上屏;左右滑动 = 翻页;两侧箭头可点。
 * 手势开关由 service 按偏好注入。
 */
class CandidateBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var onSelect: ((Int) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null
    var swipeSelectEnabled = true
    var swipePageEnabled = true

    private var items: List<dev.hwkbd.ime.engine.Candidate> = emptyList()
    private var highlight = -1
    private var page = 0
    private var totalPages = 0

    private val density = resources.displayMetrics.density
    private val arrowWidth = 40f * density
    private val touchSlop = 12f * density
    private val flingDistance = 24f * density

    private val bgPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.candidate_bg) }
    private val pressedPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.candidate_pressed) }
    private val dividerPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.candidate_divider) }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.candidate_fg)
        textSize = 21f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.arrow_fg)
        textSize = 15f * resources.displayMetrics.scaledDensity
    }

    private var downX = 0f
    private var downY = 0f
    private var downCell = -1

    fun setState(state: ImeState) {
        items = state.pageItems()
        highlight = state.highlight
        page = state.page
        totalPages = state.totalPages
        invalidate()
    }

    private val cellsLeft: Float get() = arrowWidth
    private val cellsRight: Float get() = width - arrowWidth
    private val cellWidth: Float get() = if (items.isEmpty()) 0f else (cellsRight - cellsLeft) / items.size

    private fun cellIndexAt(x: Float): Int {
        if (items.isEmpty()) return -1
        if (x < cellsLeft || x >= cellsRight) return -1
        return (((x - cellsLeft) / cellWidth).toInt()).coerceIn(0, items.size - 1)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downCell = cellIndexAt(downX)
                performClick()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when {
                    kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop -> onTap(downX)
                    swipeSelectEnabled && dy < -flingDistance && -dy > kotlin.math.abs(dx) -> {
                        val i = cellIndexAt(downX)
                        if (i >= 0) onSelect?.invoke(i)
                    }
                    swipePageEnabled && kotlin.math.abs(dx) > flingDistance && kotlin.math.abs(dx) > kotlin.math.abs(dy) ->
                        onPage?.invoke(if (dx < 0) 1 else -1)
                }
            }
        }
        return true
    }

    private fun onTap(x: Float) {
        when {
            x < arrowWidth -> onPage?.invoke(-1)
            x >= width - arrowWidth -> onPage?.invoke(1)
            else -> cellIndexAt(x).takeIf { it >= 0 }?.let { onSelect?.invoke(it) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        if (items.isEmpty()) return

        // 翻页箭头(到边界置暗)
        val dim = arrowPaint.alpha
        arrowPaint.alpha = if (page > 0) 0xFF else 0x40
        canvas.drawText("◀", arrowWidth / 2f - arrowPaint.measureText("◀") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = if (page < totalPages - 1) 0xFF else 0x40
        canvas.drawText("▶", width - arrowWidth / 2f - arrowPaint.measureText("▶") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = dim

        for (i in items.indices) {
            val left = cellsLeft + i * cellWidth
            if (i == highlight) {
                canvas.drawRect(left, 0f, left + cellWidth, height.toFloat(), pressedPaint)
            }
            val w = wordPaint.measureText(items[i].word)
            val x = left + (cellWidth - w) / 2f
            val y = height / 2f - (wordPaint.ascent() + wordPaint.descent()) / 2f
            wordPaint.textSize *= if (items[i].word.length > 6) 0.7f else 1f
            canvas.drawText(items[i].word, x, y, wordPaint)
            wordPaint.textSize = 21f * resources.displayMetrics.scaledDensity
            if (i > 0) {
                canvas.drawRect(left, dip(8), left + 1, height - dip(8), dividerPaint)
            }
        }
    }

    private fun dip(v: Int): Float = v * density
}
