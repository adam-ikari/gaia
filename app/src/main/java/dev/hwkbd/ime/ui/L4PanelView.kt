package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.R
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut

/**
 * L4 虚拟按键面板:单行水平可滚动的可配置键帽(来自 KeyLayout.l4,键位编辑器 [L4] 段)。
 * 点击键 → 输出文本/功能键。默认隐藏,状态行 ⌨ 按钮开关。
 */
class L4PanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : HorizontalScrollView(context, attrs) {

    var onL4Key: ((KeyOut) -> Unit)? = null

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private var bgColor = ContextCompat.getColor(context, R.color.key_bg)
    private var strokeColor = ContextCompat.getColor(context, R.color.candidate_divider)
    private var fgColor = ContextCompat.getColor(context, R.color.candidate_fg)

    init {
        addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        setHorizontalScrollBarEnabled(false)
    }

    /** 按当前布局与主题重建键帽。 */
    fun refresh(layout: KeyLayout?, theme: ImeTheme) {
        bgColor = theme.pressed
        strokeColor = theme.divider
        fgColor = theme.fg
        row.removeAllViews()
        val density = resources.displayMetrics.density
        layout?.l4?.forEach { key ->
            val tv = TextView(context).apply {
                text = key.label
                gravity = Gravity.CENTER
                minHeight = dip(30)
                minWidth = dip(44)
                maxHeight = dip(32)
                setPadding(dip(8), 0, dip(8), 0)
                textSize = 13f
                setTextColor(fgColor)
                background = makeKeyBg()
                setOnClickListener { onL4Key?.invoke(key.out) }
            }
            row.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT).apply {
                marginEnd = (4 * density).toInt()
            })
        }
        invalidate()
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 6f * resources.displayMetrics.density
        setColor(bgColor)
        setStroke(1, strokeColor)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
