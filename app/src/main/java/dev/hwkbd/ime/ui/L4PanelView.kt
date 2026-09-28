package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import dev.hwkbd.ime.R
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key

/**
 * L4 虚拟按键面板:状态行**中区**的单行键帽。
 *
 * 内容由状态机算好传进来([dev.hwkbd.ime.ime.ImeController.l4Keys] = 当前语言的补差键排最左
 * + 输入类型选的键位套),**配置什么显示什么**——程序不做"L1-L3 敲得出就不显示"之类的筛选,
 * L1-L4 都是固定按键。
 *
 * 点击键 → 输出文本/功能键;显示与否由设置页「状态栏显示 L4 虚拟按键」开关控制
 * (关闭时 INVISIBLE 占位,右区按钮位置不跳)。
 *
 * **不做横向滚动**:状态行上的横滑要留给手势,滚动会和手势抢同一条事件流。
 * 内容不足中区宽度时**键间距保持基础 4dp 不变**,把富余宽度均摊成**键宽**来贴边填满
 * (间距不许被拉大——键会散开、看着像没排齐)。**一行自然宽装不下的尾巴移到状态栏上方的
 * L5 独立虚拟按键行**(齿轮左侧键盘图标切换其显隐,2026-09-29 用户定案):L4 不再被硬压到截字,
 * L5 行内仍超宽才压下限→等比压缩——**键一把不裁**(两行合起来仍然一把不裁)。
 */
class L4PanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    var onL4Key: ((KeyOut) -> Unit)? = null

    /**
     * 溢出回调:(有无溢出, L4 装下的前缀长度=分页下标);service 据此控制齿轮左侧的
     * 键盘图标显隐,并回填上方 L5 行的起始下标。
     */
    var onOverflowChange: ((hasL5: Boolean, cut: Int) -> Unit)? = null

    /** true = L5 行(只显示 [tailFrom] 起的溢出尾巴);false = L4 行(显示装得下的前缀)。 */
    var tailMode = false

    /**
     * L5 行的起始下标:由 L4 行的溢出回调回填——两行可用宽不同,各自算的 cut 对不上,
     * 尾巴必须以 L4 的 cut 为准,否则夹在中间的键两行都不显示。
     */
    var tailFrom = 0

    private var lastHasL5 = false
    private var lastCut = -1

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
     * **L4 页只装自然宽(36dp 下限 + 4dp 间距)装得下的前缀,装不下的尾巴移到 L5 页**
     * (齿轮左侧键盘图标翻页,2026-09-29 定案):
     * ①全部键按 36dp 下限自然量,顺序累加求前缀(第一把无论如何留在 L4);
     * ②L4 页前缀必然 ≤ 中区 → 富余均摊成键宽贴边填满;③L5 页(尾巴)仍超宽才压掉下限、
     * 等比压缩——**键一把不裁、绝不滚动**。溢出规则取代旧的"L4 内硬压"。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (childCount == 0) {
            if (lastHasL5) {
                lastHasL5 = false
                post { onOverflowChange?.invoke(false, 0) }
            }
            return
        }
        val base = (4 * resources.displayMetrics.density).toInt()
        val avail = measuredWidth - paddingLeft - paddingRight
        val hSpec = MeasureSpec.makeMeasureSpec(dip(36), MeasureSpec.EXACTLY)

        // ①全量自然量(GONE 的也量——分页判断要全部键宽)
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.minimumWidth = dip(36)
            child.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), hSpec)
        }
        var used = 0
        var cut = 0
        for (i in 0 until childCount) {
            val cw = getChildAt(i).measuredWidth + if (i == 0) 0 else base
            if (i > 0 && used + cw > avail) break // 第一把无论如何留在 L4(装不下才压缩)
            used += cw
            cut = i + 1
        }
        val hasL5 = cut < childCount
        for (i in 0 until childCount) {
            val visible = if (tailMode) i >= tailFrom else i < cut
            getChildAt(i).visibility = if (visible) View.VISIBLE else View.GONE
        }
        applyBaseMargins(base)
        layoutPage(base, avail)
        if (!tailMode && (hasL5 != lastHasL5 || cut != lastCut)) {
            lastHasL5 = hasL5
            lastCut = cut
            // post:测量期间不动别的视图(布局遍历中 requestLayout 会告警)
            post { onOverflowChange?.invoke(hasL5, cut) }
        }
    }

    /** 间距只有基础值,不再放大;首个**可见**键无左距(L5 页也贴左缘),其余可见键 4dp。 */
    private fun applyBaseMargins(base: Int) {
        var first = true
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            val lp = child.layoutParams as LayoutParams
            if (child.visibility != View.VISIBLE) {
                lp.leftMargin = 0
                lp.rightMargin = 0
                continue
            }
            lp.leftMargin = if (first) 0 else base
            lp.rightMargin = 0
            first = false
        }
    }

    /** 当前页可见键三步适宽:自然装下→富余均摊;压掉 36dp 下限重排;仍不够等比压缩。 */
    private fun layoutPage(base: Int, avail: Int) {
        var content = naturalContent()
        if (content > avail) {
            // 压掉键宽下限(重建时恢复),按文字自然宽重排——只动可见键
            for (i in 0 until childCount) {
                val child = getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
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

    private fun naturalContent(): Int {
        var content = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val lp = child.layoutParams as LayoutParams
            content += child.measuredWidth + lp.leftMargin
        }
        return content
    }

    private fun fillSpare(content: Int, avail: Int) {
        val spare = avail - content
        if (spare <= 0) return
        // L5 尾巴通常只有几把键:均摊全行会拉成一人多宽的怪键——保持自然宽左对齐
        // (键多到接近铺满时余量本来就小,视觉上仍是排齐的)。
        if (tailMode) return
        var vis = 0
        for (i in 0 until childCount) if (getChildAt(i).visibility == View.VISIBLE) vis++
        if (vis == 0) return
        val share = spare / vis
        val rest = spare % vis // 余数给最左的可见键,末键右缘正好贴齐
        var seen = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility != View.VISIBLE) continue
            val w = child.measuredWidth + share + if (seen == 0) rest else 0
            seen++
            child.measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(child.measuredHeight, MeasureSpec.EXACTLY),
            )
        }
    }

    /** 等比压缩**可见键**到中区宽度(文字放不下时由 TextView 自行截字)。 */
    private fun compressToFit(base: Int, avail: Int) {
        val vis = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.VISIBLE) vis += child
        }
        if (vis.isEmpty()) return
        var textWidth = 0
        for (c in vis) textWidth += c.measuredWidth
        val room = avail - base * (vis.size - 1)
        if (textWidth <= 0 || room <= 0) return
        var used = 0
        vis.forEachIndexed { i, child ->
            // 末键吃掉余数,右缘正好贴齐
            val w = if (i == vis.size - 1) room - used else child.measuredWidth * room / textWidth
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
