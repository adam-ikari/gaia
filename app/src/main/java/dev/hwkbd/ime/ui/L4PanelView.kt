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
 * L4 虚拟按键面板:状态行**中区**的单行可滚动紧凑键帽(来自 KeyLayout.l4,键位编辑器 [L4] 段)。
 * 点击键 → 输出文本/功能键;显示与否由设置页「状态栏显示 L4 虚拟按键」开关控制
 * (关闭时 INVISIBLE 占位,右区按钮位置不跳)。
 * 键帽压到 24dp 高以塞进 36dp 状态行;内容不足一屏时整体居中,超一屏可横向滚动。
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

    /** 按当前布局与主题重建键帽(紧凑档:塞进 36dp 状态行中区)。 */
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
                minWidth = dip(30)
                setPadding(dip(5), 0, dip(5), 0)
                textSize = 11f
                setTextColor(fgColor)
                background = makeKeyBg()
                setOnClickListener { onL4Key?.invoke(key.out) }
            }
            // 定高 26dp:match_parent 会撑满 36dp 状态行,固定高度才压得住
            row.addView(tv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dip(26)).apply {
                marginEnd = (3 * density).toInt()
            })
        }
        invalidate()
    }

    /**
     * 内容不足一屏时整体居中(超一屏则贴左滚动):把多余宽度均分成左右 padding。
     * 子行宽度按 UNSPECIFIED 量出即内容真实宽度,居中只挪布局位置、不改测量结果,
     * 因此不需要(也不该)在测量里追加第二次 super.onMeasure。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val content = row.measuredWidth
        if (content <= 0) return
        val extra = ((measuredWidth - content) / 2).coerceAtLeast(0)
        if (paddingStart != extra || paddingEnd != extra) setPadding(extra, 0, extra, 0)
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(bgColor)
        setStroke(1, strokeColor)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
