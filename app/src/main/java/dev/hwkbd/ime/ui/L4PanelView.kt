package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
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
 * **L5 与 L4 共用同一套等宽槽版式、同样居中**(2026-09-29 用户定案,推翻当天的"左对齐 + 自然宽"):
 * L5 左右内边距相等 ⇒ 行中线 = 屏幕中点 = L4 中区中线;等宽槽 ⇒ 中线过中心键心(奇数把)
 * 或中缝(偶数把)。行内与 L4 唯一的差别是**竖向**:L5 键帽贴行底(见 [tailMode])。
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
     * L5 行键帽**上下居中**(不再贴行底):背景改成完全透明 + 高斯模糊之后,42dp 贴行底那版
     * L5 键帽下缘紧贴状态行、两行没有分界(用户 2026-09-29 报障「L5 按键和状态栏的分割不太自然」);
     * 现在行高 48dp(见 ime_root)+ 居中 ⇒ 键帽上下各 6dp,**到状态行键帽净距 12dp**。
     * 键仍是 36dp,与状态行同款。
     */
    var tailMode = false
        set(value) {
            field = value
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }

    /**
     * L5 行的起始下标:由 L4 行的溢出回调回填——两行可用宽不同,各自算的 cut 对不上,
     * 尾巴必须以 L4 的 cut 为准,否则夹在中间的键两行都不显示。
     */
    var tailFrom = 0

    /**
     * L5 当前显示第几页(0 起)。**放不下的翻到下一页**,不是截字(2026-09-29 用户定案
     * 「不截断 放不下的翻页」):网址键位套 13 把时尾巴有 10 把,一行放不下,键盘图标点一下翻一页。
     * 越界由 service 夹在 [0, [pageCount)-1]。
     */
    var page = 0
        set(value) {
            if (field == value) return
            field = value
            requestLayout()
        }

    /** L5 总页数,由本页测量结果回填(service 用它夹 [page] 并决定键盘图标要不要显示页码)。 */
    var pageCount = 1
        private set

    /** L5 翻页回调(当前页, 总页数);service 据此把 [page] 夹进范围。 */
    var onPageChange: ((page: Int, total: Int) -> Unit)? = null

    /**
     * L5 行**横滑翻页**回调(方向:+1 下一页 / -1 上一页),2026-09-29 用户定案选方案 2
     * 「在 L5 行上横滑翻页,和候选行手感统一」。**只在 [tailMode] 下生效**——
     * L4 中区不接横滑:那条横滑要留给飞字手势(见类注释),抢了会打架。
     */
    var onSwipePage: ((Int) -> Unit)? = null

    private var lastHasL5 = false
    private var lastCut = -1
    private var lastPage = -1
    private var lastPageSize = 0

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
        // 键帽底色**分两路**(2026-09-29 用户定案「L5 的按钮也要和背景色适配,仅仅是深一点或浅一点」
        // →「可以和背景再接近一些」):
        // - L4 在**不透明**状态行上 ⇒ 沿用主题的 [ImeTheme.pressed] 这个实心对比色(换了会融进黑底,键就消失了);
        // - L5 在**薄底色**上 ⇒ 键帽取同一个色、alpha 只比行底色重一点点([L5_CAP_ALPHA] vs [L5_TINT_ALPHA]),
        //   于是键帽读作"这层玻璃上深一点的**一点**",几乎与背景同色。
        //   填充差这么小之后,键的边界改由**半透明白描边**撑起来(见下),不然深色 App 上会糊成一片。
        //   两层黑纱叠在 App 内容上 ⇒ 字仍可读(文字压在键帽上)。
        bgColor = if (tailMode) {
            (theme.barBg and 0x00FFFFFF) or (L5_CAP_ALPHA shl 24)
        } else {
            theme.pressed
        }
        strokeColor = if (tailMode) L5_EDGE else theme.divider
        fgColor = theme.fg
        removeAllViews()
        // 键帽换了一批,旧的平移对象随之消失:顺手清掉跟手状态,别让新键带着上一轮的位移出现
        swipeU = 0f
        swipeTracking = false
        swipeAnim?.cancel()
        swipeAnim = null
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
            // **每颗键的最小宽度**([keyMinPx]):分页按它算"一把键至少占多宽",
            // 排版也按它兜底 —— 键永远不会被压成一条看不清的窄条。
            // 值与 [KeyLayout.MIN_KEY_DP] 同源,改一处两处一起变。
            minWidth = keyMinPx
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

    // ---- L5 行横滑翻页(只在 [tailMode] 下生效)
    //
    // 手感与候选行翻页、上滑飞字**共用 [DragGesture]**(2026-09-29「手感对齐」):
    // 死区 → 跟手(拖动时整排键跟着手走)→ 松手过阈值提交、没过则反向动画退回。
    // 三处只差阈值取值与轴向;这里是横向、阈值 = 行宽的 1/3(同候选行翻页)。

    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false

    /** 跟手进度 0..1:0 = 停在原页,1 = 完全换成目标页。拖动中实时更新,松手由动画补完/回退。 */
    private var swipeU = 0f

    /** 本次拖动定下的方向:+1 下一页(手指向左)/ -1 上一页。 */
    private var swipeDir = 1f

    private var swipeAnim: android.animation.ValueAnimator? = null

    /** 跟手时整排键的横向位移(px)由它驱动,松手后由动画接管。 */
    private fun swipeOffset(): Float = -swipeDir * swipeU * swipeTravel()

    /** 走完一整页的位移基准 = 行宽 × 提交比例(同候选行翻页,两处一致)。 */
    private fun swipeTravel(): Float = (measuredWidth - paddingLeft - paddingRight) * L5_COMMIT_FRAC

    /**
     * L5 行接横滑翻页,手感和候选行统一(死区 → 跟手 → 松手过阈值才翻)。
     *
     * **只在 [tailMode] 下接管**:L4 中区不接,那条横滑留给飞字手势(见类注释)。
     * **不接管 DOWN**:先让子 View(键帽)正常处理,只有横移超过死区才把后续事件截过来
     * ——这样点按键帽(输出文本)完全不受影响,竖滑(误起手)也会原样还给飞字。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (!tailMode) return super.onInterceptTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeTracking = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (kotlin.math.abs(dx) > DragGesture.dead(swipeThresholdPx()) &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    swipeTracking = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeTracking = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (!tailMode) return super.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeTracking = false
                // 接管正在播的动画:手指从当前位移接着走,不突跳(和候选行翻页同一套接管逻辑)
                swipeAnim?.cancel()
                swipeAnim = null
                // 自己没背景可点,仍返回 true 以便后续 MOVE 继续送来(被 onIntercept 截过来时)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (!swipeTracking &&
                    kotlin.math.abs(dx) > DragGesture.dead(swipeThresholdPx()) &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    // 定方向那一下决定整次手势往哪翻(同候选行翻页)
                    swipeDir = if (dx < 0f) 1f else -1f
                    swipeTracking = true
                }
                if (swipeTracking) {
                    // 跟手:反向滑动会把进度拉回 0 ⇒ 那就是在收回,松手后动画退回原页
                    val travel = -dx * swipeDir
                    setSwipeU(DragGesture.progress(travel, swipeThresholdPx()))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeTracking) {
                    val dx = ev.x - swipeDownX
                    val travel = -dx * swipeDir
                    setSwipeU(DragGesture.progress(travel, swipeThresholdPx()))
                    if (swipeU >= 1f) {
                        // 过线:提交翻页,动画把剩下的位移补完
                        onSwipePage?.invoke(if (swipeDir > 0f) 1 else -1)
                        settleSwipe(1f)
                    } else {
                        // 没过线:反向动画退回原页
                        settleSwipe(0f)
                    }
                }
                swipeTracking = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swipeTracking) settleSwipe(0f)
                swipeTracking = false
            }
        }
        return super.onTouchEvent(ev)
    }

    /** 本次横滑的触发阈值(px):行宽的 [L5_COMMIT_FRAC],与候选行翻页同一比例。 */
    private fun swipeThresholdPx(): Float =
        (measuredWidth - paddingLeft - paddingRight) * L5_COMMIT_FRAC

    private fun setSwipeU(u: Float) {
        if (u == swipeU) return
        swipeU = u
        applySwipeOffset()
    }

    /**
     * 把跟手位移应用到子视图:整排键一起平移。用 [View.setTranslationX] 而不是改 margin,
     * 这样**不碰排版**——`layoutPageNatural` 算出的居中边距仍然有效,且不触发重新测量。
     * 键滑出面板边缘是正常观感,所以不裁剪。
     */
    private fun applySwipeOffset() {
        val off = swipeOffset()
        for (i in 0 until childCount) getChildAt(i).translationX = off
    }

    /**
     * 松手后补完/回退:时长按剩余距离缩放([DragGesture.settleDuration]),所以动画速度不随
     * 松手早晚跳变——这正是"跟手"的关键。播完回到 0 并清掉 translationX。
     */
    private fun settleSwipe(target: Float) {
        swipeAnim?.cancel()
        swipeAnim = null
        val from = swipeU
        if (from == target) {
            finishSwipe()
            return
        }
        swipeAnim = android.animation.ValueAnimator.ofFloat(from, target).apply {
            duration = DragGesture.settleDuration(kotlin.math.abs(target - from))
            addUpdateListener { a ->
                swipeU = a.animatedValue as Float
                applySwipeOffset()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    swipeAnim = null
                    finishSwipe()
                }
            })
            start()
        }
    }

    private fun finishSwipe() {
        swipeU = 0f
        for (i in 0 until childCount) getChildAt(i).translationX = 0f
        invalidate()
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
            // 页码也要复位,否则换到少键的框时 page 还指着旧的第 N 页 → L5 空着
            if (lastHasL5 || lastCut != 0 || lastPage != -1) {
                lastHasL5 = false
                lastCut = 0
                lastPage = -1
                lastPageSize = 0
                page = 0
                pageCount = 1
                post { onOverflowChange?.invoke(false, 0) }
            }
            return
        }
        val base = (4 * resources.displayMetrics.density).toInt()
        val avail = measuredWidth - paddingLeft - paddingRight
        val hSpec = MeasureSpec.makeMeasureSpec(dip(36), MeasureSpec.EXACTLY)

        // ①全量自然量(GONE 的也量——分页判断要全部键宽)。
        // minimumWidth 压到 [keyMinPx]:自然宽**不低于**键的最小宽度,所以 `maxNat` 天然含了这个下限,
        // 分页算式不必再单独加一项。
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            child.minimumWidth = keyMinPx
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

        if (tailMode) {
            // L5 行**自己也分页**(2026-09-29 用户定案「不截断 放不下的翻页」):
            // 尾巴可能比一行还长(网址键位套 13 把时尾巴就有 10 把),以前 L5 一股脑全画,
            // 槽宽被压到标签自然宽以下 → 键帽被截成 `local...`/`127.0...`。
            // 现在 L5 从 [tailFrom] 起**按同样的自然宽判据**再切一次页,装不下的继续往后排,
            // 由键盘图标翻页(见 [page]/[pageCount]),**任何一页都不截字**。
            val pageStart = tailFrom + page * lastPageSize
            var maxNatT = 0
            var n = 0
            for (i in pageStart until childCount) {
                val cw = getChildAt(i).measuredWidth
                if (cw > maxNatT) maxNatT = cw
                if (i > pageStart && (i - pageStart + 1) * maxNatT + (i - pageStart) * base > avail) break
                n = i - pageStart + 1
            }
            if (n <= 0) n = 1 // 空页兜底:第一把无论如何留在本页
            for (i in 0 until childCount) {
                getChildAt(i).visibility =
                    if (i >= pageStart && i < pageStart + n) View.VISIBLE else View.GONE
            }
            applyBaseMargins(base)
            // 本页的键都按**自然宽**排,不做填充压缩:不截字是这一版的硬要求
            layoutPageNatural(base)
            if (n != lastPageSize || page != lastPage) {
                lastPageSize = n
                lastPage = page
                val total = totalTailPages()
                pageCount = total
                post { onPageChange?.invoke(page, total) }
            }
            return
        }

        for (i in 0 until childCount) {
            getChildAt(i).visibility = if (i < cut) View.VISIBLE else View.GONE
        }
        applyBaseMargins(base)
        layoutPage(base, avail)
        if (hasL5 != lastHasL5 || cut != lastCut) {
            lastHasL5 = hasL5
            lastCut = cut
            // post:测量期间不动别的视图(布局遍历中 requestLayout 会告警)
            post { onOverflowChange?.invoke(hasL5, cut) }
        }
    }

    /** 尾巴总页数 = ⌈尾巴键数 / 每页键数⌉;至少 1 页(没有尾巴时也要有一个"第 0 页"可显示)。 */
    private fun totalTailPages(): Int {
        val tail = childCount - tailFrom
        if (tail <= 0) return 1
        val per = if (lastPageSize > 0) lastPageSize else 1
        return ((tail + per - 1) / per).coerceAtLeast(1)
    }

    /**
     * L5 页的排布:可见键一律用**自然宽**,组宽不足就留对称外边距**居中**,不拉不满也不压缩。
     * 与 L4 的 [layoutPage] 的区别只有这一条——L4 那套"铺满=两端对齐、封顶=居中"是为了
     * 让 L4 页铺满中区好看,而 L5 是尾巴,它的首要诉求是**一个字都不截**(2026-09-29 用户定案)。
     * 键多到自然宽排不下时,[onMeasure] 已经把它们切到下一页去了,这里不会排不下。
     */
    private fun layoutPageNatural(base: Int) {
        val vis = ArrayList<View>()
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.VISIBLE) vis += child
        }
        if (vis.isEmpty()) return
        var groupW = 0
        for (c in vis) groupW += c.measuredWidth
        groupW += (vis.size - 1) * base
        (vis.first().layoutParams as LayoutParams).leftMargin =
            ((measuredWidth - paddingLeft - paddingRight - groupW) / 2).coerceAtLeast(0)
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
     * **等宽槽排布**(L4 中区与 L5 溢出行**共用**这一套版式):槽宽 `W = min(填充值, cap)`,其中
     * `填充值 = (avail - (n-1)×4dp) / n`、`cap = max(本页最长标签自然宽, MAX_KEY)`:
     * - `W = 填充值` → 组宽 = avail:**两端对齐**(余数按镜像成对补,末键右缘正好贴齐);
     * - `W = cap`(键少/行宽,如单键) → 组变窄,首键留对称外边距**整组居中**
     *   (不能用 gravity CENTER——LinearLayout 的 mTotalLength 按子 View wrap 宽算死不重算,
     *   会拿过时总长定位;START + 显式边距只读实测宽,见 init 注释);
     * - 等宽 + 恒 4dp 间距 ⇒ 关于自身中点镜像对称 ⇒ **中线过中心键心(奇数把)/中缝(偶数把)**,
     *   居中与两端对齐由同一条公式给出,互为特例、连续无跳变。
     * - 填充值算出的 W 低于最长标签自然宽 = 分页兜底(第一把超宽等),照裁、单行截字。
     *
     * **L5 行用同一套**(2026-09-29 用户定案,推翻当天的"左对齐 + 自然宽"):
     * L5 的左右内边距相等 ⇒ 行中线 = 屏幕中点 = L4 中区的中线;等宽槽 ⇒
     * **要么相邻两键的边缘接在中点(偶数把),要么正中一把键的中心对在中点(奇数把)**,
     * 两行读成同一个版式。行内唯一与 L4 不同的仍是**竖向**:L5 键帽贴行底(见 [tailMode])。
     * 排不下时同样按填充值压缩 + 单行截字,所以 L5 的尾巴也不会跑出右缘。
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
        // cap 不低于键的最小宽度:键少时也不会缩到比 [keyMinPx] 还窄
        val cap = maxNat.coerceAtLeast(dip(MAX_KEY)).coerceAtLeast(keyMinPx)
        val hSpec = MeasureSpec.makeMeasureSpec(dip(36), MeasureSpec.EXACTLY)
        val w = if (tooNarrow) {
            for (c in vis) (c as? TextView)?.ellipsize = android.text.TextUtils.TruncateAt.END
            keyMinPx // 极端窄也不得低于最小宽度(此前是写死 24dp,比 36dp 下限还窄)
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

    /**
     * **每颗 L4/L5 键的最小宽度(px)**(2026-09-29 用户定案「L4 L5 每个按钮设置最小宽度」)。
     * 三个地方都读它,改一处全生效:
     * - 分页:[onMeasure] 量自然宽时先钉 `minimumWidth`,于是"一把键至少占多宽"自动进了算式;
     * - 排版:[layoutPage] 的 `cap` 与"极端窄"兜底都不低于它,键不会被压成看不清的窄条;
     * - 建键:[addKey] 给 TextView 设 `minWidth`,短标签(`.co`)也有稳定的点击目标。
     *
     * 36dp 与键帽的 36dp 高一致(宽高比 1:1),也是这一行能给出的最小可点目标
     * (Android 建议 ≥48dp,但行高只有 48dp,再宽就挤了)。**L4 与 L5 共用同一个值**,
     * 两行视觉上要读成同一套键。
     */
    private val keyMinPx: Int get() = dip(MIN_KEY_DP)

    internal companion object {
        /**
         * L5 行的**薄底色** alpha(约 30%):底下 App 的颜色透上来 ⇒ L5 看着和应用背景一致
         * (2026-09-29 用户定案「L5 从应用取色」)。严格意义的"取色"做不到——输入法与 App
         * 跨进程,拿不到背后像素,只能"透过来"。
         */
        const val L5_TINT_ALPHA = 0x4D

        /**
         * L5 **键帽**底色 alpha(约 45%):与 [L5_TINT_ALPHA] 同色、只重 15 个点,所以键帽
         * "仅仅是深一点"(用户定案「可以和背景再接近一些」)。**必须大于** [L5_TINT_ALPHA],
         * 否则键帽与行底色完全同深、整排键糊成一片。
         * 两层黑纱叠在 App 内容上 ⇒ 文字压在键帽上仍可读。
         */
        const val L5_CAP_ALPHA = 0x73

        /**
         * L5 键帽的**边缘描边**(半透明白):键帽与行底色只差 15 个点,靠填充已经分不出键的边界,
         * 尤其在深色 App 上;这道玻璃感的白边把每颗键的轮廓撑起来。透明的白描边在浅色
         * App 上几乎看不见,那没关系——那里靠填充差就够;深色 App 上它才是关键。
         */
        val L5_EDGE: Int = 0x4DFFFFFF.toInt()

        /** 键的最小宽度(dp),见 [keyMinPx]。 */
        const val MIN_KEY_DP = 36

        /**
         * 槽宽封顶(dp,≈2×36dp 下限):键少/行宽时键宽不超过它,富余转成**居中余量**,
         * 防止单键/少键被拉成一人多宽的怪键。不裁字优先于封顶(cap 取两者较大)。
         */
        const val MAX_KEY = 72

        /**
         * L5 横滑翻页的触发线 = **行宽的 1/3**,与候选行翻页同一个比例
         * (2026-09-29「三处手势手感对齐」)。死区取阈值的 1/4,和其他两处一样,见 [DragGesture]。
         */
        const val L5_COMMIT_FRAC = 0.33f
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(bgColor)
        setStroke(1, strokeColor)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
