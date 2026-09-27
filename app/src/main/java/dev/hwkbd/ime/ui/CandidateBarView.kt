package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.animation.ValueAnimator
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
    var onLongPress: ((Int) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null
    var swipeSelectEnabled = true
    var swipePageEnabled = true

    /** 固定 5 显示槽(首选在中间,槽位即物理键位);空槽为 null。 */
    private var slots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var highlight = -1
    private var page = 0
    private var totalPages = 0

    private val density = resources.displayMetrics.density
    private val arrowWidth = 26f * density
    private val touchSlop = 12f * density
    private val flingDistance = 24f * density

    private val bgPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.candidate_bg) }
    private val pressedPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.candidate_pressed) }
    private val dividerPaint = Paint().apply { color = ContextCompat.getColor(context, R.color.candidate_divider) }
    private val wordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.candidate_fg)
        textSize = 17f * resources.displayMetrics.scaledDensity
        typeface = Typeface.DEFAULT_BOLD
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.arrow_fg)
        textSize = 11f * resources.displayMetrics.scaledDensity
    }

    /** 应用主题:即时刷新全部界面色。 */
    fun setTheme(t: dev.hwkbd.ime.ui.ImeTheme) {
        bgPaint.color = t.barBg
        pressedPaint.color = t.pressed
        dividerPaint.color = t.divider
        wordPaint.color = t.fg
        arrowPaint.color = t.hint
        invalidate()
    }

    private var downX = 0f
    private var downY = 0f
    private var downCell = -1

    // ---- 翻页动画:旧页滑出 + 新页从翻页方向滑入(轮播过渡)
    private var pageAnim: ValueAnimator? = null
    private var pageAnimT = 1f            // 0→1
    private var pageAnimDir = 1f          // 下一页=+1(旧页左出新页右入),上一页=-1
    private var prevSlots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var lastPage = 0

    // ---- 飞字动画:选中的词从槽位向上飞起并淡出
    private var flyAnim: ValueAnimator? = null
    private var flyX = 0f
    private var flyProgress = -1f
    private var flyWord = ""

    /** 飞字动画进行中(两态 UI 需保持候选行可见)。 */
    fun isFlyAnimating(): Boolean = flyProgress in 0f..1f

    fun setState(state: ImeState) {
        val newSlots = state.pageSlots()
        if (state.page != lastPage && slots.isNotEmpty() && newSlots.isNotEmpty()) {
            startPageAnim(if (state.page > lastPage) 1f else -1f)
        }
        lastPage = state.page
        slots = newSlots
        highlight = state.highlight
        page = state.page
        totalPages = state.totalPages
        invalidate()
    }

    private fun startPageAnim(dir: Float) {
        pageAnim?.cancel()
        prevSlots = slots
        pageAnimDir = dir
        pageAnimT = 0f
        pageAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 150
            addUpdateListener { a ->
                pageAnimT = a.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pageAnimT = 1f
                    prevSlots = emptyList()
                    invalidate()
                }
            })
            start()
        }
    }

    /** 飞字动画:词从显示槽 [displayIndex] 的位置向上飞起淡出(选字已上屏后调用)。 */
    fun startFlyAnim(displayIndex: Int, word: String) {
        if (word.isEmpty()) return
        flyX = cellsLeft + (displayIndex + 0.5f) * cellWidth
        flyWord = word
        flyAnim?.cancel()
        flyAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            addUpdateListener { a ->
                flyProgress = a.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    flyProgress = -1f
                    invalidate()
                }
            })
            start()
        }
    }

    private val cellsLeft: Float get() = arrowWidth
    private val cellsRight: Float get() = width - arrowWidth
    private val cellWidth: Float get() = (cellsRight - cellsLeft) / 5f

    private fun cellIndexAt(x: Float): Int {
        if (slots.all { it == null }) return -1
        if (x < cellsLeft || x >= cellsRight) return -1
        val idx = (((x - cellsLeft) / cellWidth).toInt()).coerceIn(0, 4)
        return if (slots.getOrNull(idx) != null) idx else -1
    }

    private var longPressFired = false
    private val longPressRunnable = Runnable {
        if (downCell >= 0) {
            longPressFired = true
            onLongPress?.invoke(downCell)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                downCell = cellIndexAt(downX)
                longPressFired = false
                postDelayed(longPressRunnable, 600L)
                performClick()
            }
            MotionEvent.ACTION_MOVE -> {
                // 移动出按下槽位则取消长按
                if (!longPressFired && cellIndexAt(event.x) != downCell) {
                    removeCallbacks(longPressRunnable)
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                val dx = event.x - downX
                val dy = event.y - downY
                when {
                    longPressFired -> Unit // 长按已在定时器里处理
                    kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop -> onTap(downX)
                    swipeSelectEnabled && dy < -flingDistance && -dy > kotlin.math.abs(dx) -> {
                        val i = cellIndexAt(downX)
                        if (i >= 0) onSelect?.invoke(i)
                    }
                    swipePageEnabled && kotlin.math.abs(dx) > flingDistance && kotlin.math.abs(dx) > kotlin.math.abs(dy) ->
                        onPage?.invoke(if (dx < 0) 1 else -1)
                }
            }
            MotionEvent.ACTION_CANCEL -> removeCallbacks(longPressRunnable)
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

        // 翻页箭头(到边界置暗)
        val dim = arrowPaint.alpha
        arrowPaint.alpha = if (page > 0) 0xFF else 0x40
        canvas.drawText("◀", arrowWidth / 2f - arrowPaint.measureText("◀") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = if (page < totalPages - 1) 0xFF else 0x40
        canvas.drawText("▶", width - arrowWidth / 2f - arrowPaint.measureText("▶") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = dim

        // 翻页动画:旧页向翻页方向滑出淡去,新页反方向滑入(轮播)
        val showPageAnim = pageAnimT < 1f && prevSlots.isNotEmpty()
        val wordAlpha = wordPaint.alpha
        if (showPageAnim) {
            val t = pageAnimT
            // 旧页:向翻页方向滑出
            wordPaint.alpha = ((1f - t) * 255).toInt()
            canvas.save()
            canvas.translate(-pageAnimDir * t * width * 0.55f, 0f)
            drawSlots(canvas, prevSlots, highlightOf = -1, dividers = false)
            canvas.restore()
            // 新页:从翻页反方向滑入
            wordPaint.alpha = (t * 255).toInt()
            canvas.save()
            canvas.translate(pageAnimDir * (1f - t) * width * 0.55f, 0f)
            drawSlots(canvas, slots, highlight, dividers = false)
            canvas.restore()
            wordPaint.alpha = wordAlpha
        } else {
            drawSlots(canvas, slots, highlight, dividers = true)
        }

        // 飞字动画:词放大、上浮、淡出
        if (flyProgress in 0f..1f) {
            val t = flyProgress
            val size = 17f * resources.displayMetrics.scaledDensity * (1f + 0.2f * t)
            wordPaint.textSize = size
            wordPaint.alpha = ((1f - t) * 255).toInt()
            val w = wordPaint.measureText(flyWord)
            val x = (flyX - w / 2f).coerceIn(dip(8), width - w - dip(8))
            val y = height / 2f - t * dip(56)
            canvas.drawText(flyWord, x, y, wordPaint)
            wordPaint.textSize = 17f * resources.displayMetrics.scaledDensity
            wordPaint.alpha = 255
        }
    }

    private fun drawSlots(canvas: Canvas, list: List<dev.hwkbd.ime.engine.Candidate?>, highlightOf: Int, dividers: Boolean) {
        for (i in 0 until 5) {
            val left = cellsLeft + i * cellWidth
            if (i > 0 && dividers) {
                canvas.drawRect(left, dip(8), left + 1, height - dip(8), dividerPaint)
            }
            val c = list.getOrNull(i) ?: continue
            if (i == highlightOf) {
                canvas.drawRect(left, 0f, left + cellWidth, height.toFloat(), pressedPaint)
            }
            val w = wordPaint.measureText(c.word)
            val x = left + (cellWidth - w) / 2f
            val y = height / 2f - (wordPaint.ascent() + wordPaint.descent()) / 2f
            wordPaint.textSize *= if (c.word.length > 6) 0.7f else 1f
            canvas.drawText(c.word, x, y, wordPaint)
            wordPaint.textSize = 17f * resources.displayMetrics.scaledDensity
        }
    }

    private fun dip(v: Int): Float = v * density
}
