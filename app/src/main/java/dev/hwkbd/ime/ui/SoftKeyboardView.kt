package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.R
import dev.hwkbd.ime.ime.ImeMode
import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.ShiftState
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.Layer

/**
 * 屏幕软键盘:4 行 QWERTY 按当前层渲染(L2/L3 显示上档标签);
 * 键面横向按比例分 [zoneWeights] 区,从键盘面往上滑 = 选中该区候选;左右滑 = 翻页。
 * 点击键位等价于物理按键,走同一状态机。
 */
class SoftKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    /** 键被点击(keycode 形式,与物理键同一入口)。 */
    var onKey: ((Int) -> Unit)? = null
    var onAltTap: (() -> Unit)? = null
    var onSymTap: (() -> Unit)? = null
    var onShiftTap: (() -> Unit)? = null
    var onSelectZone: ((Int) -> Unit)? = null
    var onPage: ((Int) -> Unit)? = null

    var swipeSelectEnabled = true
    var swipePageEnabled = true

    var layout: KeyLayout? = null

    private var layer = Layer.L1
    private var lock: Layer? = null
    private var mode = ImeMode.CN
    private var shift = ShiftState.OFF

    private val density = resources.displayMetrics.density
    private val rowHeight = 46f * density
    private val touchSlop = 12f * density
    private val flingDistance = 24f * density

    private val keyBg = Paint().apply { color = ContextCompat.getColor(context, R.color.key_bg) }
    private val keySpecialBg = Paint().apply { color = ContextCompat.getColor(context, R.color.key_special_bg) }
    private val keyPressedBg = Paint().apply { color = ContextCompat.getColor(context, R.color.key_pressed) }
    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_fg)
        textSize = 17f * resources.displayMetrics.scaledDensity
    }
    private val hintPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.candidate_hint)
        textSize = 9f * resources.displayMetrics.scaledDensity
    }
    private val fnPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_fn_fg)
        textSize = 13f * resources.displayMetrics.scaledDensity
    }

    private var downX = 0f
    private var downY = 0f

    private enum class Special { SHIFT, ALT, SYM, SPACE, DEL, ENTER }
    private data class Slot(val label: String, val sub: String?, val code: Int, val special: Special?)

    private val letterRows = listOf("qwertyuiop", "asdfghjkl", "zxcvbnm")

    fun setState(state: ImeState) {
        layer = state.effectiveLayer
        lock = state.lock
        mode = state.mode
        shift = state.shift
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (rowHeight * 4).toInt())
    }

    private fun rowSlots(row: Int): List<Slot> {
        if (row < 3) {
            val letters = letterRows[row]
            return letters.map { c ->
                val code = KeyEvent.KEYCODE_A + (c - 'a')
                val sub = layerOutputLabel(code)
                Slot(c.toString(), sub, code, null)
            }
        }
        val altLabel = if (lock == Layer.L2) "符·锁" else "符"
        val symLabel = if (lock == Layer.L3) "sym·锁" else "sym"
        return listOf(
            Slot("⇧", null, KeyEvent.KEYCODE_SHIFT_LEFT, Special.SHIFT),
            Slot(altLabel, "Alt", KeyEvent.KEYCODE_ALT_LEFT, Special.ALT),
            Slot(symLabel, "Sym", KeyEvent.KEYCODE_PICTSYMBOLS, Special.SYM),
            Slot(if (layer == Layer.L3) (layout?.mapFor(Layer.L3)?.get(KeyEvent.KEYCODE_SPACE) as? KeyOut.Text)?.text ?: "␣" else "␣", "space", KeyEvent.KEYCODE_SPACE, Special.SPACE),
            Slot("⌫", null, KeyEvent.KEYCODE_DEL, Special.DEL),
            Slot("⏎", null, KeyEvent.KEYCODE_ENTER, Special.ENTER),
        )
    }

    /** 当前层该键的输出标签(仅 L2/L3 显示上档,L1 不显示)。 */
    private fun layerOutputLabel(code: Int): String? {
        if (layer == Layer.L1) return null
        val l = layout ?: return null
        val out = l.mapFor(layer)[code] as? KeyOut.Text ?: return null
        return if (out.text.length <= 3) out.text else out.text.take(3)
    }

    private fun slotAt(x: Float, y: Float): Slot? {
        val row = (y / rowHeight).toInt()
        if (row !in 0..3) return null
        val slots = rowSlots(row)
        val w = width / slots.size.toFloat()
        val col = (x / w).toInt().coerceIn(0, slots.size - 1)
        return slots[col]
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                performClick()
            }
            MotionEvent.ACTION_UP -> {
                val dx = event.x - downX
                val dy = event.y - downY
                when {
                    kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop ->
                        slotAt(downX, downY)?.let(::press)
                    swipeSelectEnabled && dy < -flingDistance && -dy > kotlin.math.abs(dx) ->
                        zoneIndexAt(downX)?.let { onSelectZone?.invoke(it) }
                    swipePageEnabled && kotlin.math.abs(dx) > flingDistance && kotlin.math.abs(dx) > kotlin.math.abs(dy) ->
                        onPage?.invoke(if (dx < 0) 1 else -1)
                }
            }
        }
        return true
    }

    private fun press(slot: Slot) {
        when (slot.special) {
            Special.SHIFT -> onShiftTap?.invoke()
            Special.ALT -> onAltTap?.invoke()
            Special.SYM -> onSymTap?.invoke()
            else -> onKey?.invoke(slot.code)
        }
    }

    /** 键面横向平分 5 区,返回 x 所在区号。 */
    private fun zoneIndexAt(x: Float): Int? {
        if (width <= 0) return null
        return ((x / width * FLY_ZONES).toInt()).coerceIn(0, FLY_ZONES - 1)
    }

    companion object {
        private const val FLY_ZONES = 5
    }

    override fun onDraw(canvas: Canvas) {
        val pad = 1.5f * density
        for (row in 0..3) {
            val slots = rowSlots(row)
            val w = width / slots.size.toFloat()
            for (i in slots.indices) {
                val left = i * w + pad
                val top = row * rowHeight + pad
                val right = (i + 1) * w - pad
                val bottom = (row + 1) * rowHeight - pad
                val isSpecial = slots[i].special != null
                val active = (slots[i].special == Special.ALT && lock == Layer.L2) ||
                    (slots[i].special == Special.SYM && lock == Layer.L3) ||
                    (slots[i].special == Special.SHIFT && shift != ShiftState.OFF)
                canvas.drawRoundRect(left, top, right, bottom, 6f * density, 6f * density, when {
                    active -> keyPressedBg
                    isSpecial -> keySpecialBg
                    else -> keyBg
                })
                val cx = (left + right) / 2f
                val labelY = (top + bottom) / 2f + dip(if (slots[i].sub != null) 3 else 5)
                val paint = if (isSpecial) fnPaint else keyPaint
                val tw = paint.measureText(slots[i].label)
                canvas.drawText(slots[i].label, cx - tw / 2f, labelY, paint)
                slots[i].sub?.let {
                    val sw = hintPaint.measureText(it)
                    canvas.drawText(it, cx - sw / 2f, top + dip(13), hintPaint)
                }
            }
        }
    }

    private fun dip(v: Int): Float = v * density
}
