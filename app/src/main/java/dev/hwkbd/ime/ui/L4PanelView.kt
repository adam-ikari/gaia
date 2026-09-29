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
 * 内容由状态机算好传进来([dev.hwkbd.ime.ime.ImeController.l4Keys] = 语言补差键排最左
 * + 字段核心键 + 输入类型选的键位套,已由注册表过完筛)**L1-L3 已有的按键不进 L4**
 * (按本机真实键位判,见 [dev.hwkbd.ime.keymap.KeyLayout.producible];数字态放的是
 * L1-L3 敲不出的短语组合,见 [dev.hwkbd.ime.keymap.KeyLayout.L4_PROFILE_PHONE_KEYS])。
 *
 * 点击键 → 输出文本/功能键;L4 常显(2026-09-29 用户定案:设置页开关已删、数字态也不隐藏)。
 *
 * **不做横向滚动**:状态行上的横滑要留给手势,滚动会和手势抢同一条事件流。
 *
 * **等宽槽版式(2026-09-29 定案):同时满足「居中」和「两端对齐」**。键间距恒 4dp,
 * 所有可见键取同一槽宽 `W = min(填充值, cap)`(`cap = max(本页最长标签自然宽, MAX_KEY)`,
 * 见 [layoutPage]):
 * - `W = 填充值` → 组宽恰好 = 中区宽 → 首键贴左、末键贴右 = **两端对齐**;
 * - `W = cap`(键少/行宽) → 组变窄 → 首键留对称外边距、**整组居中**;
 * - 等宽 + 等距 ⇒ 整行关于自身中点镜像对称 ⇒ **中线恒过中心键心(奇数把)或中缝(偶数把)**,
 *   两种状态下"中线对齐"都成立——这正是用户对"居中"的定义,靠等宽自然获得,无需吸附计算。
 *
 * **装不下的尾巴移到状态栏上方的 L5 独立虚拟按键行**([tailMode] = true,齿轮左侧键盘图标切换其显隐,
 * 2026-09-29 用户定案):分页按"前缀最长标签宽"判装得下(见 [onMeasure]),L4 页不裁字;
 * 超宽(分页兜底/L5 尾巴)由槽宽公式直接压掉——**键一把不裁的兜底是单行截字,不换行**。
 * **L5 左侧对齐、键宽用自然宽**(2026-09-29 用户定案,与 L4 的居中/两端对齐不同,见 [layoutPage])。
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

    /**
     * true = L5 行(显示 [tailFrom] 起的溢出尾巴 + 接在后面的用户短语);false = L4 行(显示装得下的前缀)。
     * L5 行键帽**贴行底**:两行键间距原为 12dp(两行 48dp 各取 6dp 居中边距之和),
     * 用户嫌大(2026-09-29),贴底后收到 6dp,贴近行内 4dp 键距;状态行本身不动。
     * L5 行高 42dp(见 ime_root):键仍 36dp,贴底后上边 6dp——原 48dp 行上边空 12dp,用户嫌太宽。
     */
    var tailMode = false
        set(value) {
            field = value
            gravity = Gravity.START or if (value) Gravity.BOTTOM else Gravity.CENTER_VERTICAL
        }

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
        // 用 START + 显式居中边距(见 layoutPage),不能用 gravity CENTER:
        // LinearLayout 的 mTotalLength 在 super.onMeasure 里按子 View 的 wrap 宽算死,
        // 之后把子 View 改成 EXACTLY 槽宽它不会重算 → CENTER 会拿过时总长算偏移(实测整组
        // 右移 164px、末键被面板右缘裁掉)。START 布局只读子 View 实测宽与 lp 边距,免疫。
        gravity = Gravity.START or Gravity.CENTER_VERTICAL
    }

    /**
     * 按给定键序重建键帽(等宽槽排布见 [layoutPage])。键序来自状态机——
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
            // 槽宽压到标签自然宽以下时单行截字(等宽槽/压缩的兜底),不许换行撑破 36dp
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
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
     * **等宽槽分页**:前缀 k 的槽宽 = 前缀里最长标签的自然宽(等宽 ⇒ 一行的槽宽由最长标签决定),
     * 故装得下 ⟺ `k × 前缀最长自然宽 + (k-1) × 4dp ≤ avail`。第一把无论如何留在 L4(装不下才截字)。
     * 装不下的尾巴移到 L5 行(2026-09-29 定案),L4 页保证不裁字;槽宽排布见 [layoutPage]。
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (childCount == 0) {
            // L4 一把键都没有(如纯数字框):切点必须回 0,否则 L5 行的用户短语会被上一次的
            // 旧 cut 误裁(实测:日期框 cut=1 → 切到纯数字框,L5 只剩第二把短语)
            if (lastHasL5 || lastCut != 0) {
                lastHasL5 = false
                lastCut = 0
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
        var maxNat = 0
        var cut = 0
        for (i in 0 until childCount) {
            val cw = getChildAt(i).measuredWidth
            if (cw > maxNat) maxNat = cw
            // 前缀 (i+1) 把:等宽槽按前缀最长宽算;单调增,装不下即可停
            if (i > 0 && (i + 1) * maxNat + i * base > avail) break
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

    /** 间距只有基础值,不再放大;首个**可见**键先不带左边距(免得多出一格 4dp),其余可见键 4dp;首键的居中边距由 [layoutPage] 计算回填。 */
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

    /**
     * **等宽槽排布**:槽宽 `W = min(填充值, cap)`,其中
     * `填充值 = (avail - (n-1)×4dp) / n`、`cap = max(本页最长标签自然宽, MAX_KEY)`:
     * - `W = 填充值` → 组宽 = avail:**两端对齐**(余数按镜像成对补,末键右缘正好贴齐);
     * - `W = cap`(键少/行宽,如单键) → 组变窄,首键留对称外边距**整组居中**
     *   (不能用 gravity CENTER——LinearLayout 的 mTotalLength 按子 View wrap 宽算死不重算,
     *   会拿过时总长定位;START + 显式边距只读实测宽,见 init 注释);
     * - 等宽 + 恒 4dp 间距 ⇒ 关于自身中点镜像对称 ⇒ **中线过中心键心(奇数把)/中缝(偶数把)**,
     *   居中与两端对齐由同一条公式给出,互为特例、连续无跳变。
     * - 填充值算出的 W 低于最长标签自然宽 = 分页兜底(第一把超宽等),照裁、单行截字。
     *
     * **L5 行例外(2026-09-29 用户定案:左侧对齐、键宽用自然宽)**:不重测成等宽、不拉满行宽、
     * 不居中,首键贴行左缘向右排——尾行是"接着 L4 往下看"的续页,居中会让人以为是一组独立键,
     * 靠左才和上面那行读成同一条序列。
     */
    private fun layoutPage(base: Int, avail: Int) {
        val vis = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.VISIBLE) vis += child
        }
        if (vis.isEmpty()) return
        var maxNat = 0
        for (c in vis) if (c.measuredWidth > maxNat) maxNat = c.measuredWidth
        val n = vis.size
        val room = avail - (n - 1) * base
        // 极端窄(长标签键位套,如邮箱 @域名):压缩到最小槽宽 + 单行截字。
        // 此前直接 return 维持自然宽 → 对称外溢压住右区按钮(用户报障);压缩后溢出有界。
        val tooNarrow = room < n
        val fill = room / n
        val rest = room % n
        val cap = maxNat.coerceAtLeast(dip(MAX_KEY))
        val hSpec = MeasureSpec.makeMeasureSpec(dip(36), MeasureSpec.EXACTLY)
        if (tailMode) {
            // L5 左侧对齐、**键宽用自然宽**:不重测成等宽、不拉满行宽、不居中
            // (首键 leftMargin 由 [applyBaseMargins] 置 0 = 贴行左缘)
            return
        }
        val w = if (tooNarrow) {
            for (c in vis) (c as? TextView)?.ellipsize = android.text.TextUtils.TruncateAt.END
            dip(24)
        } else fill.coerceAtMost(cap)
        for (c in vis) c.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), hSpec)
        if (fill <= cap || tooNarrow) distributeRest(vis, if (tooNarrow) 0 else rest)
        // 居中:START 布局从首键左 margin 起步 → 给首键加 (avail-组宽)/2 的对称外边距
        // (1px 取整差自然落在右侧)。组铺满时余量为 0 = 贴边两端对齐。
        var groupW = 0
        for (c in vis) groupW += c.measuredWidth
        groupW += (vis.size - 1) * base
        (vis.first().layoutParams as LayoutParams).leftMargin = ((avail - groupW) / 2).coerceAtLeast(0)
    }

    /**
     * 取整余数(≤ n-1 px)按**镜像成对**回补,保证"两端对齐"精确贴齐的同时不破坏中线对称;
     * 最后 1px:奇数把给中心键(对称保持),偶数把只能落末键(<1px 视觉差)。
     */
    private fun distributeRest(vis: List<View>, rest: Int) {
        var r = rest
        var i = 0
        val hSpec = MeasureSpec.makeMeasureSpec(dip(36), MeasureSpec.EXACTLY)
        fun grow(c: View) {
            c.measure(
                MeasureSpec.makeMeasureSpec(c.measuredWidth + 1, MeasureSpec.EXACTLY),
                hSpec,
            )
        }
        while (r >= 2 && i < vis.size / 2) {
            grow(vis[i])
            grow(vis[vis.size - 1 - i])
            r -= 2
            i++
        }
        if (r == 1) grow(if (vis.size % 2 == 1) vis[vis.size / 2] else vis.last())
    }

    private companion object {
        /**
         * 槽宽封顶(dp,≈2×36dp 下限):键少/行宽时键宽不超过它,富余转成**居中余量**,
         * 防止单键/少键被拉成一人多宽的怪键。不裁字优先于封顶(cap 取两者较大)。
         */
        const val MAX_KEY = 72
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(bgColor)
        setStroke(1, strokeColor)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
