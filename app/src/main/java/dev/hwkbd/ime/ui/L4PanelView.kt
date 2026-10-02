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
     * **本次手势允许走横滑吗**:起手那一下问一次,整条手势记住结论。
     *
     * service 注入 [dev.hwkbd.ime.ime.KeyEventGate](按键后 200ms 静默窗)。**L5 横滑也在其列**
     * (2026-10-02 用户定「所有的键盘滑动都无效」)。**键帽点按不走这里** —— 它走子 View 的
     * `setOnClickListener`,与手势分支无关,所以「敲一个键、紧接着点 L4 上的键」不受影响。
     *
     * 问在 DOWN 而不是每次 MOVE:半路才接管的话画面已经跟着手指走了,松手却不翻页,
     * 那读起来是延迟,比一开始就不响应更糟。
     */
    var swipeGate: (() -> Boolean)? = null

    /** 本次手势的横滑许可(DOWN 时定,见 [swipeGate])。默认 true = 没接门就是全放行。 */
    private var swipeArmed = true

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
     * 键位的**身份**:用于判断"还是那把键,只是换了内容"。
     *
     * 2026-09-30 用户定案:全角/半角切换时,同一个键位的全角与半角**算同一把键**,
     * 不该"一把消失、另一把补位" —— 那会让屏幕上的键凭空换位,读起来像布局错了。
     * 身份取**输出**而不是标签:全角 `；` 与半角 `;` 标签不同但都是"分号键位",
     * 标签还会因为语言、档位变化而变,拿它当身份会把同一把键认成两把。
     */
    private data class KeyIdentity(val out: String)

    /**
     * 按给定键序重建键帽(等宽槽排布见 [layoutPage])。键序来自状态机——
     * 输入类型变(网址框)或语言变(中↔英)时由 service 重新调用。
     *
     * **键序变化走 enter 动画**(2026-09-30 用户「l4l5的按键需要一个进入动画」):
     * 从右侧移入,非线性缓动([Fling.DECELERATE]),且**位移严格限制在本区域内** ——
     * 起始位移取 min(行宽, 键组宽),动画期间 clip 在自身边界,绝不影响区域以外
     * (此前键是从右侧凭空出现的,读起来像"多了一把")。
     *
     * **消失的键与补位**(同一条要求):新旧键序按身份对齐,身份还在的键**原地保留**
     * (全角↔半角就是走这条路),只有真正消失的键才淡出、新增的键才从右侧移入,
     * 其余键平移到新位置 —— 而不是整组推倒重来。
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
        // 记下旧键序(身份 + 旧槽位),用于"消失/补位"动画
        val density = resources.displayMetrics.density
        val oldIds = (0 until childCount).mapNotNull { (getChildAt(it).tag as? KeyIdentity)?.out }
        val hadKeys = childCount > 0
        val newIds = keys.map { it.out.format() }

        // **退场键的快照要在 removeAllViews 之前取** —— 之后就没有 left/right/top/bottom 了。
        // 只快照真正消失的那些:身份还在的键由进入动画以"原位移 0"处理(见 runEnterAnimation)。
        val ghosts: List<L4PanelView.Ghost> =
            if (!hadKeys || enterDisabled) emptyList()
            else (0 until childCount)
                .map { getChildAt(it) }
                .filter { (it.tag as? KeyIdentity)?.out?.let { id -> id !in newIds } == true }
                .map { toGhost(it) }

        // 跟手状态清零:键序要重建,旧键帽上的平移不该带到新键上
        swipeSlide = 0f
        swipeTravel = 0f
        swipeTracking = false
        swipeSpeed.reset()
        swipeSpring.cancel()
        // 键序要重建:两支弹簧的旧回调绑的是**旧的子视图列表**,继续跑会往新键上写旧位移
        enterProgress.cancel()
        exitAlpha.cancel()

        removeAllViews()
        keys.forEach { addKey(it.label, it.out, density) }

        if (!hadKeys || enterDisabled) {
            exiting.clear()
            enterRunning = false
            for (i in 0 until childCount) {
                getChildAt(i).translationX = 0f
                getChildAt(i).alpha = 1f
            }
            invalidate()
            return
        }
        runEnterAnimation(oldIds, newIds)
        // 退场与进场**同时**跑,同一份 [SpringSpec.CAP_MOVE]、同一时刻起跑 ⇒ 同一个物理过程,
        // 不会变成"先退后进"两段,也不会各自从零初速起步而中途顿一下
        runExitAnimation(ghosts)
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
        // 键的身份:**输出**字符串。全角↔半角是同一把键(用户 2026-09-30 定),
        // 拿输出当身份才认得出"还是它",标签会随语言/档位变,不能当身份用。
        tv.tag = KeyIdentity(out.format())
    }

    // 三处只差阈值取值与轴向;这里是横向、阈值 = 行宽的 1/3(同候选行翻页)。
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false

    /**
     * 跟手位移(**px**,不是进度):0 = 停在原页,[swipeLimitPx] = 完全换成目标页。
     * 2026-09-30 改:此前是 0..1 进度再乘 `swipeTravel`,而那本是**阈值**(行宽 × 提交比例),
     * 于是"走完阈值"画面也只走了阈值那么远,永远到不了新页。现在与候选行翻页统一。
     */
    private var swipeSlide = 0f

    /** 本次拖动沿 [swipeDir] 的手指位移(px,已取正):提交判定用,不参与绘制。 */
    private var swipeTravel = 0f

    /** 指尖速度跟踪(px/s):松手时决定惯性滑多远、补完动画多长。 */
    private val swipeSpeed = SpeedTracker()

    /** 本次拖动定下的方向:+1 下一页(手指向左)/ -1 上一页。 */
    private var swipeDir = 1f


    /** 跟手时整排键的横向位移(px)由它驱动,松手后由动画接管。 */
    private fun swipeOffset(): Float = -swipeDir * swipeSlide

    // ---- 键序变化动画(2026-09-30 用户「l4l5的按键需要一个进入动画」)

    private var enterRunning = false

    /** 无障碍/测试可关掉动画(设置里没有这个开关,只有单测与截图脚本用)。 */
    @Volatile
    var enterDisabled = false

    /**
     * 进场位移的**归一化进度**弹簧:1 = 起始位移,0 = 落位。
     *
     * 用归一化值而不是像素,是让不同长度的键组共用一支弹簧 —— 手感一致,
     * 且位移天然被 [startShift] 各自的行程夹住,不会越出本视图区域。
     */
    private var enterProgress = newEnterChannel()

    /** 每个新槽位的起始位移(px,0 = 原地不动)。每次 [runEnterAnimation] 重算。 */
    private var startShift = FloatArray(0)

    /** 退场淡出的弹簧:1 = 完全不透明,0 = 消失。文字与键帽共用它,见 [onDraw]。 */
    private var exitAlpha = SpringChannel(1f, SpringSpec.CAP_MOVE, onFrame = { _, _ -> })

    private fun newEnterChannel() = SpringChannel(
        1f,
        SpringSpec.CAP_MOVE,
        onFrame = { _, _ -> applyEnterSpring() },
        onFinish = {
            enterRunning = false
            for (i in 0 until childCount) {
                getChildAt(i).translationX = 0f
                getChildAt(i).alpha = 1f
            }
            invalidate()
        },
    )

    // ---- 键的退场动画(2026-10-02 用户「l4l5 要有按键的消失动画」)

    /**
     * 正在退场的键的**快照**:位置与外观。
     *
     * 为什么不留着真视图跑动画:[layoutPage] 按**子视图个数**排等宽槽,多挂一个
     * 「正在退场」的键就会把整排挤歪 —— 而挤歪发生在键序正在重排、最该看清的地方。
     * 真实视图一律只属于新键序;退场键由 [onDraw] 补画。
     *
     * **没有"退场方向"**:2026-10-02 用户「l4l5 按键消失的效果不对 应该原地消失」。
     * 此前退场键会朝行外平移(`origin ± 侧向 × enterTravel`),于是消失的那几把
     * 一边淡出一边横穿还留着的那几把 —— 看着像布局在乱挤,而不是"这把键没了"。
     * 现在退场只有淡出,位置一帧不动([runExitAnimation])。
     */
    private class Ghost(
        val text: String,
        val bg: android.graphics.drawable.Drawable?,
        /** 退场位置(只淡出,所以每帧都等于这个值)。 */
        val origin: android.graphics.RectF,
        /** 当前这一帧要画的位置。 */
        val rect: android.graphics.RectF,
    )

    private val exiting = ArrayList<Ghost>()

    init {
        // 自己画退场键 ⇒ 不能让 LinearLayout 跳过 onDraw
        setWillNotDraw(false)
    }

    /** 把一个即将被丢掉的旧键帽快照成 [Ghost](本视图坐标系)。必须在 `removeAllViews` **之前**调。 */
    private fun toGhost(v: View): Ghost {
        val tv = v as? android.widget.TextView
        return Ghost(
            text = tv?.text?.toString() ?: "",
            bg = v.background?.mutate(),
            origin = android.graphics.RectF(
                v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat(),
            ),
            rect = android.graphics.RectF(v.left.toFloat(), v.top.toFloat(), v.right.toFloat(), v.bottom.toFloat()),
        )
    }

    /**
     * 退场动画:**原地淡出**,不动位置(2026-10-02 用户「应该原地消失」)。
     *
     * 此前是"往外挪 + 淡出",于是消失的那几把一边淡出一边横穿还留着的那几把,
     * 读起来像布局在乱挤。原地淡出才读得出"这把键没了",而补位那几把各自走
     * [runEnterAnimation] 的位移 —— 两件事分开,各自看得懂。
     *
     * **透明度走官方 [androidx.dynamicanimation.animation.SpringAnimation]**,
     * 参数与进场位移**同一支** [SpringSpec.CAP_MOVE]。这一点是关键:
     * 旧代码是两个独立 `ValueAnimator`,各自从零初速起步、靠固定时长对齐 ——
     * 键帽正往右滑而淡出却从静止开始,读起来就是「消失动画不自然」
     * (2026-10-02 用户报障)。现在两段是同一条弹簧的两个通道,收敛过程一致。
     */
    private fun runExitAnimation(ghosts: List<Ghost>) {
        exiting.clear()
        if (ghosts.isEmpty()) return
        exiting += ghosts
        // 每次重起一支:键序又变了一次,淡出必须从完全不透明开始,
        // 否则上一段的残余透明度会被继承(退场一开始就半透明,像"闪掉")
        exitAlpha = SpringChannel(1f, SpringSpec.CAP_MOVE, onFrame = { _, _ ->
            for (g in exiting) {
                // 位置**一帧不动**:每帧都回到 origin,只写透明度
                g.rect.set(g.origin)
                g.bg?.alpha = (255 * exitAlpha.currentValue).toInt().coerceIn(0, 255)
            }
            invalidate()
        }, onFinish = {
            exiting.clear()
            invalidate()
        })
        // 从完全不透明朝 0 淡出
        exitAlpha.settle(1f, 0f)
    }

    /**
     * 补画正在退场的键。真实子视图只属于新键序,退场键在这里画 —— 见 [Ghost] 为什么不做成真视图。
     *
     * **文字必须跟着键帽一起淡出**(2026-10-02 用户「消失动画不自然」):此前这里写的是
     * `tv.paint.color = fgColor`,一个**不带 alpha 的常量**。于是键帽背景在淡出、
     * 字却一直满不透明地留在原地,最后 `exiting.clear()` 那一帧字突然消失 ——
     * 读起来就是"键帽没了但字还飘着,然后啪一下没了"。淡出必须作用到画笔上。
     */
    override fun onDraw(canvas: android.graphics.Canvas) {
        super.onDraw(canvas)
        if (exiting.isEmpty()) return
        val tv = android.widget.TextView(context) // 只为量一次文字宽度,不入视图树
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
        // 透明度取自退场弹簧的当前值,逐帧重算(键帽 bg 的 alpha 在 [runExitAnimation] 里写)
        val textAlpha = (255 * exitAlpha.currentValue).toInt().coerceIn(0, 255)
        for (g in exiting) {
            g.bg?.setBounds(
                g.rect.left.toInt(), g.rect.top.toInt(),
                g.rect.right.toInt(), g.rect.bottom.toInt(),
            )
            g.bg?.draw(canvas)
            tv.text = g.text
            // 只替换 alpha 位,颜色本身逐帧不变
            tv.paint.color = (fgColor and 0x00FFFFFF) or (textAlpha shl 24)
            val tw = tv.paint.measureText(g.text)
            val cx = g.rect.centerX() - tw / 2f
            val metrics = tv.paint.fontMetrics
            val cy = g.rect.centerY() - (metrics.ascent + metrics.descent) / 2f
            canvas.drawText(g.text, cx, cy, tv.paint)
        }
    }

    /**
     * 进入动画:**从右侧移入**,位移严格限制在本区域内。**走官方 [SpringAnimation]**。
     *
     * 三条约束都是用户明确要求的:
     * 1. **从右侧进来** —— 新键 startX 偏移,而不是凭空出现;
     * 2. **非线性** —— 临界阻尼弹簧([SpringSpec.CAP_MOVE],ζ=1.6 过阻尼、不过冲);
     * 3. **不超过边距** —— 起始位移 = `min(本视图宽度, 键组自然宽)`,再 `coerceAtMost`,
     *    所以即使键组比视口宽,键也**完全在自己的区域内**滑,不越界影响外层。
     *
     * ## 为什么换掉 `ValueAnimator`(2026-10-02 用户「使用物理引擎重构手势和动画」)
     *
     * 固定时长 + 曲线有三个坏处,换引擎后才消得掉:
     * - **打断必跳变**:`ValueAnimator.setCurrentPlayTime` / 重开动画器时速度不连续,
     *   用户在动画播到一半切语言/切输入框,键帽会顿一下 —— 这与翻页那边报的是同一个毛病。
     * - **时长与行程无关**:三把键与八把键都是 [ENTER_MS],位移差 3 倍而时长一样,
     *   读起来像"突然窜出去"。
     * - **与退场不同步**:退场淡出与进场位移是两个独立动画器,各自从零初速起步。
     *
     * 换 [SpringAnimation] 后:位移与透明度**共用同一条弹簧**(同一 [SpringSpec]、同一启动时刻),
     * 所以"滑进来"与"淡入"是同一个物理过程;打断时官方引擎保留速度,位置与速度都连续。
     *
     * **退场键不参与本动画**:它已被 [removeAllViews] 移除,由 [runExitAnimation] 的淡出负责。
     *
     * **消失的键与补位**:按身份([KeyIdentity],取输出)对齐三组 ——
     * - 身份还在的键:**原位移为 0**,只做内容替换(全角↔半角就是这一档,不该"消失一把再补一把");
     * - 位置变了的老键:从旧位置平移到新位置;
     * - 真正新增的键:从右侧外移进来。
     */
    private fun runEnterAnimation(oldIds: List<String>, newIds: List<String>) {
        val n = childCount
        if (n == 0) return

        // 每个新槽位的起始位移:身份还在 ⇒ 0(原地不动,只换内容);否则从右侧整段移进来
        startShift = FloatArray(n) { i ->
            val id = newIds.getOrNull(i) ?: return@FloatArray 0f
            if (id in oldIds) 0f else enterTravel()
        }
        // 有任何一把要移动才播动画:全角↔半角这种"同一把换内容"不播,免得闪一下
        val moving = startShift.any { it > 0f }
        if (!moving) {
            invalidate()
            return
        }

        enterRunning = true
        // 每次都是**新的一支弹簧**从 1 起步:键序变了就是一段新的动画,
        // 旧的进度值不该继承(否则第二次刷新会从半路开始,看着像闪了一下)。
        enterProgress = newEnterChannel()
        // 从 1(整段起始位移)朝 0(落位)起跑;无惯性初速
        enterProgress.settle(1f, 0f)
        applyEnterSpring()
    }

    /**
     * 把进场弹簧的当前进度写到子视图上。
     *
     * 位移与透明度**读同一个进度**:位移 = 整段 × p,alpha = 0.35 → 1。
     * 两者由同一支弹簧驱动,所以"滑进来"与"淡入"是同一个物理过程。
     */
    private fun applyEnterSpring() {
        val p = enterProgress.currentValue
        val alpha = 0.35f + 0.65f * (1f - p)
        for (i in 0 until childCount) {
            getChildAt(i).translationX = startShift[i] * p
            getChildAt(i).alpha = alpha
        }
        invalidate()
    }

    /**
     * 进入动画的起始位移(px):**不超过本视图区域**。
     *
     * 取 `min(视口宽, 键组自然宽 + 一点余量)` —— 用键组宽而不是视口宽是关键:
     * 键组窄时位移就短(整段滑进来正好铺满键组),不会从屏幕外很远的地方飞进来;
     * 键组比视口宽时用视口宽,位移被视口封住,键**不会滑出区域外**。
     */
    private fun enterTravel(): Float {
        val viewport = (measuredWidth - paddingLeft - paddingRight).toFloat()
        if (viewport <= 0f) return 0f
        // 键组自然宽:子视图实测宽 + 键间距
        var natural = 0f
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            natural += v.measuredWidth
            if (i > 0) natural += keyGapPx()
        }
        return kotlin.math.min(viewport, natural).coerceAtLeast(dip(16).toFloat())
    }

    private fun keyGapPx(): Float = dip(4).toFloat()

    /** 供上层在动画期间拒绝点击(动画中点到半路会误触)。 */
    fun isEnterAnimating(): Boolean = enterRunning

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
                // 滑动许可只在这里问一次(见 swipeGate);键帽点按不看它
                swipeArmed = swipeGate?.invoke() != true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (
                    swipeArmed &&
                    kotlin.math.abs(dx) > swipeDeadPx &&
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
                // 与 onInterceptTouchEvent 各问一次:两条路径的 DOWN 不一定都走到
                // (被父截走时只有 onIntercept 见过 DOWN),两处都定才不会出现半条手势没许可
                swipeArmed = swipeGate?.invoke() != true
                swipeSpeed.reset()
                swipeSpeed.add(ev.eventTime, ev.x)
                // 接管正在播的动画:手指从当前位移接着走,不突跳(和候选行翻页同一套接管逻辑)。
                // **取消的是弹簧,不是那个已恒为 null 的 `ValueAnimator`** ——
                // 否则弹簧继续跑,下一帧又把 swipeSlide 写回去,手指按着画面还在自己动。
                swipeSpring.cancel()
                // 自己没背景可点,仍返回 true 以便后续 MOVE 继续送来(被 onIntercept 截过来时)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (!swipeTracking && swipeArmed &&
                    kotlin.math.abs(dx) > swipeDeadPx &&
                    kotlin.math.abs(dx) > kotlin.math.abs(dy)
                ) {
                    // 定方向那一下决定整次手势往哪翻(同候选行翻页)
                    swipeDir = if (dx < 0f) 1f else -1f
                    swipeTracking = true
                }
                if (swipeTracking) {
                    // 跟手:反向滑动会把位移拉回 0 ⇒ 那就是在收回,松手后动画退回原页。
                    // 与候选行翻页同一模型:**画面位移 = 手指位移**(slide),进度只用来判提交。
                    // 此前这里是"进度 × 行宽"两处混用,放大过 —— 见 CandidateBarView.pageSlide。
                    swipeTravel = -dx * swipeDir
                    swipeSpeed.add(ev.eventTime, -dx)
                    setSwipeSlide(DragGesture.slide(swipeTravel, swipeThresholdPx(), swipeLimitPx(), swipeDeadPx))
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeTracking) {
                    val dx = ev.x - swipeDownX
                    swipeTravel = -dx * swipeDir
                    swipeSpeed.add(ev.eventTime, ev.x)
                    setSwipeSlide(DragGesture.slide(swipeTravel, swipeThresholdPx(), swipeLimitPx(), swipeDeadPx))
                    // 与候选行翻页同一判定:手指位移过线,或速度够快"甩"过去
                    val v = swipeSpeed.velocity() * swipeDir
                    val limit = swipeLimitPx()
                    val byFinger = DragGesture.progress(swipeTravel, swipeThresholdPx(), swipeDeadPx) >= 1f
                    val byFling = v > 0f && Landing.project(swipeSlide, limit, v) >= swipeThresholdPx()
                    if (byFinger || byFling) {
                        // 过线:提交翻页,动画把剩下的位移补完
                        onSwipePage?.invoke(if (swipeDir > 0f) 1 else -1)
                        settleSwipe(Landing.project(swipeSlide, limit, v).coerceAtLeast(swipeSlide))
                    } else {
                        // 没过线:反向动画退回原页
                        settleSwipe(Landing.project(swipeSlide, 0f, v).coerceAtMost(swipeSlide))
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
    // ------------------------------------------------------------------ 横滑翻页阈值

    /**
     * L5 横滑翻页的触发线 = **行宽的比例**。
     *
     * 出厂值 [L5_COMMIT_FRAC];实际取值由 service 从设置页的档位注入
     * (2026-10-02 用户「左右滑动阈值需要可调节性」)。**与候选行共用同一个开关** ——
     * 两处都是「在候选/键位那一行上左右划」,灵敏度一起变才有意义,分开调反而不知道
     * 该调哪个。
     */
    var swipeCommitFrac: Float = L5_COMMIT_FRAC

    private fun swipeThresholdPx(): Float =
        (measuredWidth - paddingLeft - paddingRight) * swipeCommitFrac

    /**
     * 横滑的**固定死区**(px):[DragGesture.DEAD_DP] 换算,**不随 [swipeCommitFrac] 变**。
     *
     * 2026-10-02 用户「死区大小不随着触发阈值变化」「左右滑动和上下滑动的死区都是固定大小」。
     * 与候选行的 [CandidateBarView.pageDeadPx] 同一个常量,两处手感才对得上。
     */
    private val swipeDeadPx: Float get() = DragGesture.DEAD_DP * resources.displayMetrics.density

    /**
     * 本次横滑的位移上限(px):一整页的宽度 —— 也就是"滑到底"要走全的距离。
     * 与候选行翻页的 `width` 同义,拖过它画面就到底(不会连翻第二页)。
     */
    private fun swipeLimitPx(): Float = (measuredWidth - paddingLeft - paddingRight).toFloat()

    /**
     * 设置跟手位移(**px**,不是进度)。与候选行翻页一致:画面位移 = 手指位移。
     * @param slide 沿 [swipeDir] 的位移(px)
     */
    private fun setSwipeSlide(slide: Float) {
        if (slide == swipeSlide) return
        swipeSlide = slide
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
     * 松手后补完/回退:**target 是像素**(整页宽 = 补完,0 = 退回原页)。
     *
     * **走官方弹簧**(2026-10-02 用户「使用物理引擎重构手势和动画」「l4l5 的动画也要基于物理引擎」)。
     * 落点由 [Landing] 定、过程由 `SpringAnimation` 积分,分工见 [Landing]。
     *
     * 与候选栏那支 [CandidateBarView] 的翻页弹簧是**同一套**,所以 L5 横滑与候选行翻页
     * 的手感不会分叉 —— 此前两处各自 `ValueAnimator` + 各自算时长,同一个速度在两处
     * 走出的距离不一样,用户能感觉到。
     */
    private fun settleSwipe(target: Float) {
        val from = swipeSlide
        if (from == target) {
            swipeSpring.cancel()
            finishSwipe()
            return
        }
        swipeSettleTarget = target
        swipeSpring.settle(from, target)
    }

    /** [settleSwipe] 的落点;走完时用它收尾。见 [CandidateBarView.pageSettleTarget] 同理。 */
    private var swipeSettleTarget = 0f

    private val swipeSpring = SpringChannel(
        from = 0f,
        spec = SpringSpec.DEFAULT,
        onFrame = { value, _ ->
            swipeSlide = value
            applySwipeOffset()
        },
        onFinish = { cancelled ->
            if (cancelled) return@SpringChannel
            swipeSlide = swipeSettleTarget
            finishSwipe()
        },
    )

    private fun finishSwipe() {
        swipeSlide = 0f
        swipeTravel = 0f
        swipeSpeed.reset()
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
         * L5 横滑翻页的触发线 = **行宽的 0.22**,与候选行翻页同一个比例
         * (2026-09-29「三处手势手感对齐」)。死区是固定常量 [DragGesture.DEAD_DP](8dp),
         * 不随本比例变,见 [dev.hwkbd.ime.ui.PAGE_COMMIT_FRAC] 的说明。
         *
         * **只是出厂默认**(2026-10-02 用户「左右滑动阈值需要可调节性」):
         * 实际取值经 [L4PanelView.swipeCommitFrac] 由设置页注入。默认档为「灵敏」
         * (2026-10-02 用户「灵敏这档的横向滑动手感算正常」)。
         */
        const val L5_COMMIT_FRAC = 0.22f
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * resources.displayMetrics.density
        setColor(bgColor)
        setStroke(1, strokeColor)
    }

    private fun dip(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
