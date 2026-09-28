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
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key

/**
 * L4 虚拟按键面板:状态行**中区**的单行键帽。
 *
 * 内容由状态机算好传进来([dev.hwkbd.ime.ime.ImeController.l4Keys] = 输入类型选的键位套
 * + 当前语言的补差键),**配置什么显示什么**——程序不做"L1-L3 敲得出就不显示"之类的筛选,
 * L1-L4 都是固定按键。
 *
 * 点击键 → 输出文本/功能键;显示与否由设置页「状态栏显示 L4 虚拟按键」开关控制
 * (关闭时 INVISIBLE 占位,右区按钮位置不跳)。
 *
 * **不做横向滚动**:状态行上的横滑要留给手势,滚动会和手势抢同一条事件流。
 * 内容不足中区宽度时**键间距保持基础 4dp 不变**,把富余宽度均摊成**键宽**来贴边填满
 * (间距不许被拉大——键会散开、看着像没排齐);超出时先压掉 36dp 键宽下限按文字自然宽重排,
 * 仍不够再等比压缩——**键一把不裁**(裁掉的那把是配置里写死的键)。
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
     * 按给定键序重建键帽(填满 48dp 状态行中区)。键序来自状态机——
     * 输入类型变(网址框)或语言变(中↔英)时由 service 重新调用。
     */
    fun refresh(keys: List<L4Key>, theme: ImeTheme) {
        bgColor = theme.pressed
        strokeColor = theme.divider
        fgColor = theme.fg
        removeAllViews()
        val density = resources.displayMetrics.density
        keys.forEach { addKey(it.label, it.out, density) }
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
     * (首键贴左、末键贴右,右缘正好对齐)。间距曾被均摊拉大过——键会被甩得稀稀拉拉,
     * 用户明确不要;要填满只能加宽键帽。
     *
     * 超宽三步:①按 36dp 键宽下限自然量;②放不下就压掉下限、按文字自然宽重排;
     * ③仍不够按文字宽等比压到中区(可能截字,但**键一把不裁、绝不滚动**)。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (childCount == 0) return
        val base = (4 * resources.displayMetrics.density).toInt()
        val avail = measuredWidth - paddingLeft - paddingRight
        applyBaseMargins(base)

        var content = naturalContent()
        if (content > avail) {
            // ②压掉键宽下限(只在本次刷新的键帽上生效,重建时恢复),按文字自然宽重排
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                child.minimumWidth = 0
                child.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
                )
            }
            content = naturalContent()
            if (content > avail) {
                compressToFit(base, avail)
                return
            }
        }
        fillSpare(content, avail)
    }

    /** 间距只有基础值,不再放大;内容宽 = 各键实测宽 + 间距。 */
    private fun applyBaseMargins(base: Int) {
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            lp.leftMargin = if (i == 0) 0 else base
            lp.rightMargin = 0
        }
    }

    private fun naturalContent(): Int {
        var content = 0
        for (i in 0 until childCount) {
            val lp = getChildAt(i).layoutParams as LayoutParams
            content += getChildAt(i).measuredWidth + lp.leftMargin
        }
        return content
    }

    private fun fillSpare(content: Int, avail: Int) {
        val spare = avail - content
        if (spare <= 0) return
        val share = spare / childCount
        val rest = spare % childCount // 余数给最左的键,末键右缘正好贴齐
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val w = child.measuredWidth + share + if (i == 0) rest else 0
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    /** 等比压缩到中区宽度(键全部可见;文字放不下时由 TextView 自行截字)。 */
    private fun compressToFit(base: Int, avail: Int) {
        var textWidth = 0
        for (i in 0 until childCount) textWidth += getChildAt(i).measuredWidth
        val room = avail - base * (childCount - 1)
        if (textWidth <= 0 || room <= 0) return
        var used = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            // 末键吃掉余数,右缘正好贴齐
            val w = if (i == childCount - 1) room - used else child.measuredWidth * room / textWidth
            used += w
            child.measure(
                MeasureSpec.makeMeasureSpec(w.coerceAtLeast(1), MeasureSpec.EXACTLY),
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
