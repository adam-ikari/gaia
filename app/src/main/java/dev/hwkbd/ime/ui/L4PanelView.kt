package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.R
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut

/**
 * L4 虚拟按键面板:状态行**中区**的单行键帽,内容 = 两段拼接——
 * 1. 固定核心键([KeyLayout.L4_CORE]:L1/L2/L3 敲不出的单字符标点,删不掉;**不放组合按键**);
 * 2. 键位编辑器 [L4] 段的自定义键。
 *
 * 两段都过同一道筛:**L1/L2/L3 已经能敲出来的取值一律不显示**([KeyLayout.producible]),
 * 中区只留给三层敲不出的键(没有"最近符号"之类的动态键)。
 *
 * 点击键 → 输出文本/功能键;显示与否由设置页「状态栏显示 L4 虚拟按键」开关控制
 * (关闭时 INVISIBLE 占位,右区按钮位置不跳)。
 *
 * **不做横向滚动**:状态行上的横滑要留给手势,滚动会和手势抢同一条事件流。
 * 内容不足中区宽度时**键间距保持基础 4dp 不变**,把富余宽度均摊成**键宽**来贴边填满
 * (间距不许被拉大——键会散开、看着像没排齐);超出则保持基础间距贴左裁切
 * (超屏属键位配置问题,请精简编辑器里的自定义键)。
 */
class L4PanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onL4Key: ((KeyOut) -> Unit)? = null

    private var bgColor = ContextCompat.getColor(context, R.color.key_bg)
    private var strokeColor = ContextCompat.getColor(context, R.color.candidate_divider)
    private var fgColor = ContextCompat.getColor(context, R.color.candidate_fg)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    /**
     * 按当前布局与主题重建键帽(填满 48dp 状态行中区)。
     *
     * **只放 L1-L3 敲不出的键**:固定核心键 + 键位编辑器自定义键——
     * 凡是 L1/L2/L3 已经能敲出来的取值([KeyLayout.producible]),一律不占中区。
     */
    fun refresh(layout: KeyLayout?, theme: ImeTheme) {
        bgColor = theme.pressed
        strokeColor = theme.divider
        fgColor = theme.fg
        removeAllViews()
        val density = resources.displayMetrics.density
        val keys = layout ?: KeyLayout.default()
        val producible = keys.producible()
        keys.l4Combined()
            .filterNot { it.out.format() in producible }
            .forEach { addKey(it.label, it.out, density) }
        invalidate()
    }

    private fun addKey(label: String, out: KeyOut, density: Float) {
        val tv = TextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            minWidth = dip(36)
            setPadding(dip(6), 0, dip(6), 0)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(fgColor)
            background = makeKeyBg()
            // 按下要有震动反馈:走 performHapticFeedback,不需要 VIBRATE 权限,随系统设置走
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                onL4Key?.invoke(out)
            }
        }
        // 定高 36dp:match_parent 会撑满 48dp 状态行,固定高度才压得住(上下各留 6dp)
        addView(tv, LayoutParams(LayoutParams.WRAP_CONTENT, dip(36)).apply {
            marginEnd = (4 * density).toInt()
        })
    }

    /**
     * **填满**中区,但**间距不动**:基础间距固定 4dp,富余宽度均摊成每个键的宽度
     * (首键贴左、末键贴右,右缘正好对齐);内容超出时保持基础间距 → 贴左裁切(绝不滚动)。
     *
     * 间距曾被均摊拉大过——键会被甩得稀稀拉拉,用户明确不要;要填满只能加宽键帽。
     * 宽度直接量到子 View 上(measuredWidth + leftMargin 一起算),onLayout 按 leftMargin 排。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (childCount == 0) return
        val base = (4 * resources.displayMetrics.density).toInt()
        val widths = IntArray(childCount)
        var content = 0
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            lp.leftMargin = if (i == 0) 0 else base // 间距只有基础值,不再放大
            lp.rightMargin = 0
            widths[i] = getChildAt(i).measuredWidth
            content += widths[i] + lp.leftMargin
        }
        val spare = (measuredWidth - paddingLeft - paddingRight) - content
        if (spare <= 0) return // 超出:基础间距 + 贴左裁切
        val share = spare / childCount
        val rest = spare % childCount // 余数给最左的键,末键右缘正好贴齐
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val w = widths[i] + share + if (i == 0) rest else 0
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(bgColor)
        setStroke(1, strokeColor)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
