package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
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

    /**
     * **本次手势允许走滑动吗**:起手那一下问一次,整条手势记住结论。
     *
     * service 注入 [dev.hwkbd.ime.ime.KeyEventGate](按键后 200ms 静默窗)。
     * **候选行也在其列**(2026-10-02 用户定「所有的键盘滑动都无效」)—— 键盘表面上敲完键
     * 紧接着划一下,和在候选行上划,是同一种"手还没离开键盘"的惯性。
     *
     * **只挡滑动,点按与长按不看它**:"敲一个键、紧接着点候选词上屏"是最常见的手速,
     * 一起挡掉比不挡更糟。所以 [swipeArmed] 只出现在翻页接管、上滑起拖、抬手快划这几处。
     *
     * 问在 DOWN 而不是每次 MOVE:要挡的是**整条手势**,半路接管的话画面已经跟着手指走了,
     * 松手却不翻页 —— 那读起来是延迟,比一开始就不响应更糟。
     */
    var swipeGate: (() -> Boolean)? = null

    /** 本次手势的滑动许可(DOWN 时定,见 [swipeGate])。默认 true = 没接门就是全放行。 */
    private var swipeArmed = true

    /**
     * 飞字触发上滑距离(px):由 service 按设置注入,与全屏飞字手势(FlyDetector)
     * 用同一个阈值——在候选行上起手的上滑不再走独立的 24dp 判据。
     */
    var flyThresholdPx: Float = 160f

    /**
     * 上滑死区 = 阈值 1/4:位移更小视为抖动忽略(词不起升、其余候选不渐隐);过死区后进度从 0 重算。
     * 与翻页共用 [DragGesture] 的死区约定(2026-09-29「三处手势手感对齐」)。
     */
    private val flyDeadPx: Float get() = DragGesture.DEAD_DP * density

    /** 固定 5 显示槽(首选在中间,槽位即物理键位);空槽为 null。 */
    private var slots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var highlight = -1
    private var page = 0
    private var totalPages = 0

    private val density = resources.displayMetrics.density
    /**
     * 箭头**触摸区**每侧的宽度(px):点这一条就翻页,候选槽位也从这里开始算。
     *
     * **与 [arrowInset] 分开是有意的**(2026-10-02 用户「翻页左右箭头到屏幕边缘近一些」):
     * 此前两者共用一个 `arrowWidth`,于是"箭头往边缘挪"只能靠把触摸区一起调窄来换 ——
     * 而翻页恰恰是手指最容易滑偏的动作,触摸区不该跟着视觉走。
     * 那个共用值 26dp 今天原封不动留给触摸用。
     */
    private val arrowWidth = 26f * density

    /**
     * 箭头**图形中心**到该侧边缘的距离(px):13dp → **8dp**。
     *
     * 候选行自身已有 `bar_safe_edge`(16dp)左右内边距(避开 Titan 2 底部两角的物理圆角),
     * 所以箭头中心离**屏幕**边缘 = 16 + 8 = 24dp(此前是 29dp)。
     *
     * 没有再往里压是因为字形 `◀`/`▶` 本身约 8dp 宽:中心再靠近,字形就会被行边裁掉一角。
     */
    private val arrowInset = 8f * density
    private val touchSlop = 12f * density

    /**
     * 下滑选字的**抬手**判据(仍按 24dp):短促的"快速一划直接选中",不是跟手拖动,用绝对距离合适。
     * 与翻页的 [pageDeadPx]/[pageCommitPx] 是两回事,别混。
     */
    private val flingDistance = 24f * density

    /**
     * **翻页手势阈值按行宽比例取(不是绝对 dp)**——2026-09-29 用户定案「翻页阈值也需要调整」:
     * 此前是写死的 24dp 抬手判据,在 598dp 宽(1440px 通栏)的候选行上只占 4%,随手一碰就翻页,完全不跟手。
     * 改成行宽比例后跨机型一致。死区 / 跟手 / 提交线与 L5 翻页、上滑飞字**共用**
     * [DragGesture] 那份数学(2026-09-29「手感对齐」),这里只提供阈值:
     * 触发线 = 行宽的 [pageCommitFrac],死区 = 触发线的 1/4。
     *
     * **可调**(2026-10-02 用户「左右滑动阈值需要可调节性」):设置页「左右滑翻页所需距离」
     * 的档位经这里注入,[PAGE_COMMIT_FRAC] 只是出厂默认值。
     */
    var pageCommitFrac: Float = PAGE_COMMIT_FRAC

    /**
     * 横滑的**固定死区**(px):[DragGesture.DEAD_DP_SWIPE] 换算,**不随 [pageCommitFrac] 变**。
     *
     * 与上滑的 [flyDeadPx](用 [DragGesture.DEAD_DP])**是两个常量**:2026-10-04 用户要
     * 「翻页更灵」但「不改上滑」,而此前两者共用一个常量、无法分开调。
     *
     * 2026-10-02 用户「死区大小不随着触发阈值变化」。此前取 `触发线 × 1/4`,
     * 标准档就是 59dp —— 手指先走 59dp 屏幕纹丝不动。
     */
    private val pageDeadPx: Float get() = DragGesture.DEAD_DP_SWIPE * density

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

    /** 主题主色:第一候选的强调色。 */
    private var accentColor = dev.hwkbd.ime.ui.ImeTheme.DEFAULT.accent

    /** 应用主题:即时刷新全部界面色。 */
    fun setTheme(t: dev.hwkbd.ime.ui.ImeTheme) {
        bgPaint.color = t.barBg
        pressedPaint.color = t.pressed
        dividerPaint.color = t.divider
        wordPaint.color = t.fg
        arrowPaint.color = t.hint
        accentColor = t.accent
        invalidate()
    }

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var downCell = -1

    // ---- 翻页:横向**跟手**拖动(与上滑飞字同一套模型:死区 → 跟手 → 松手过阈值才提交)
    //
    // [pageSlide] = 本次过渡的**画面位移(px)**,0 = 整行停在 [pageFrom],`width` = 完全换成 [pageTo]。
    // **它是像素不是进度**(2026-09-30 真机报「翻页不跟手」「动画太短」修):此前这个量是归一化
    // 进度,绘制时又乘整行宽,而提交线只有行宽 1/3 ⇒ 手指走 1/3、画走满整行,放大 3 倍。
    // 跟手 = 画面位移 = 手指位移,所以这里存像素。提交判定另走 [DragGesture.progress]。
    // 稳态(没有过渡在跑)时 [pageFrom] 为空,直接画 [slots]。
    // 一次手势内 [pageDir] 固定(定方向那一下就决定),手指往回拖只是让 pageSlide 变小(在收回);
    // 真要翻去反方向,靠**下一次**手势接管正在播的动画来表达([beginPageDrag] 的角色对调)。
    // [pageCommitted]:目标页是不是已经交给 controller 了——接管后又往回拖并松手时要用它撤销提交。
    private var pageSlide = 0f
    private var pageDir = 1f                 // +1 下一页:旧页左出/新页右入;-1 相反
    private var pageFrom: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var pageTo: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var pageCommitted = false
    private var lastPage = 0

    /** 翻页拖动的基线 x:手指从这里起算 [pageSlide];接管动画时按当前 pageSlide 重定基线,接着走不跳。 */
    private var pageDragBaseX = 0f
    private var pageDragging = false

    /** 本次拖动沿 [pageDir] 的手指位移(px,已取正):提交判定用,不参与绘制。 */
    private var pageDragTravel = 0f

    /** 翻页指尖速度跟踪(px/s):松手时决定惯性滑多远、补完动画多长。 */
    private val pageSpeed = SpeedTracker()

    /** 飞字指尖速度跟踪(px/s):飞出去的速度与飞多远由它决定。 */
    private val flySpeed = SpeedTracker()

    /**
     * 触感反馈。所有震动都收口在 [Haptics](一个总开关),这里只负责在"状态真的改变"时点一下。
     * 由 service 注入,View 自己不去碰 Vibrator —— 否则开关控制不住(见 [setHaptics])。
     */
    private var haptics: dev.hwkbd.ime.ime.Haptics? = null

    fun setHaptics(h: dev.hwkbd.ime.ime.Haptics?) {
        haptics = h
    }

    /** 相邻两页的显示槽(跟手拖动时提前摆出来预览),越界为空表;随 [setState] 刷新。 */
    private var pageNextSlots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var pagePrevSlots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()

    // ---- 飞字动画:选中的词随手指上移,越过阈值后飞完;其他候选随上划渐隐
    //
    // 进度 flyP 用一根数轴表达两段:
    //   [0,1]  拖动段——跟着手指走(上移到 26dp、其余候选淡到 0.4)
    //   (1,2]  完成段——越过阈值后由动画接管,加速飞到 64dp、其余候选加速淡完
    private var flyP = -1f
    private var flyZone = -1
    private var flyX = 0f
    private var flyWord = ""
    private var flyOutSlots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()

    /** true = 正在播完成段(已上屏),不接受新的拖动打断;false = 拖动中/淡回中。 */
    private var flyCompleting = false

    /** 飞字进行中(拖动或完成动画):两态 UI 需保持候选行可见。 */
    /**
     * 现在是不是还在飞(拖动中 / 起飞中 / 放弃淡回中)。
     *
     * **必须把两支弹簧都算进去**(2026-10-02 换弹簧时补的):原来只有 `flyP >= 0f`,
     * 换引擎之后 `flyAnim != null` 这个判据没了,而 `flyP` 在动画**结束回调**清 0 之前一直 ≥ 0,
     * 所以单看 `flyP` 恰好还对 —— 但那是巧合,不是理由。写全了才不用靠巧合:
     * 弹簧还在跑、而 `flyP` 恰好被 `onFrame` 写成 0 的那一帧,状态就是"在飞但看起来没在飞"。
     */
    fun isFlyAnimating(): Boolean = flyP >= 0f || flyUp.isRunning || flyBack.isRunning

/**
     * 下一次 [onPageIndexChanged] 要撤销回哪一页(`-1` = 没有待处理的撤销)。
     *
     * 2026-10-09 真机实测:「拉回线以下松手」明明走了取消分支,画面却补了一整页动画 ——
     * 详见 [onPageIndexChanged] 开头那段。
     *
     * ## 这里存的是**页号**而不是布尔
     *
     * 第一版用 `Boolean pendingRollback`,结果把下一次**真正的翻页**也吞掉了
     * (`select-global-index` 当场变红:pageStep 一次没调、page 停在 0)。
     * 原因是那次 `onPage(-step)` **不一定**真的产生页号变化(比如中途那次提交
     * 其实没发出去),标记就留在那儿等着吞下一次翻页。
     *
     * ⇒ [onPageIndexChanged] 只在 `newPage` **正好等于它**时才当撤销;
     * 否则一律当普通翻页,并顺手清掉。
     */
    private var pendingRollbackTo = -1

    /**
     * 本次拖动**起手时**的页号 —— 撤销要回到的就是它。
     *
     * 不能用抬手那一刻的 [page]:中途那次提交之后它可能已经变成新页了。
     */
    private var pageDragOriginPage = 0

    /** [onDraw] 埋点的上一次签名,只为「变了才打一行」。见那里的说明。 */
    private var lastDrawSig = 0

    /** 绘制分支的名字,与 [onDraw] 埋点里的 `kind=` 数字一一对应。 */
    private val KIND_NAME = listOf("slots", "fly", "pageTurn", "rubber")

    // ## 埋点:视图**收到**了什么(2026-10-09)
    //
    // 与 [onDraw] 里那行「画了什么」配对 —— 用户报「部分消费后 hao 不出候选」,
    // 控制器侧 45 个候选一路正常,所以要能分清:
    //   ① 状态就空(上游)  ② 状态到了但 `pageSlots()` 取不到东西(这里)  ③ 画不出来(视图)
    //
    // 之前这三者全都没有记录,于是只能靠猜。**每一次 `setState` 都打**,
    // 因为它本身就是低频事件(按键/生命周期),不像 `onDraw` 那样每帧都来。
    fun setState(state: ImeState) {
        android.util.Log.i(
            TAG,
            "setState: raw=\"${state.raw}\" n=${state.candidates.size} page=${state.page}/" +
                "${state.totalPages} hl=${state.highlight} slots=${state.pageSlots().size} " +
                    "nonNull=${state.pageSlots().count { it != null }}"
        )
        val newSlots = state.pageSlots()
        // 跟手翻页要预览相邻页,先备好(越界为空表 = 那一侧没有页,拖过去也不动)
        pageNextSlots = state.pageSlotsAt(state.page + 1)
        pagePrevSlots = state.pageSlotsAt(state.page - 1)
        val pageTurned = state.page != lastPage
        if (pageTurned) onPageIndexChanged(state.page, newSlots)
        lastPage = state.page
        // 变化动画要在 slots 换掉**之前**算:要对比新旧每一槽是不是同一个词
        if (!pageTurned) startSwapAnim(slots, newSlots)
        // ## 拖动中那次提交是**暂定**的,`slots` 不能跟着换(2026-10-09 真机)
        //
        // 「过阈值当帧就翻」意味着拖到一半 controller 的页号就变了,于是这一行会把
        // **下一页的候选词**换成当前页。但那次提交还可能被撤销(拉回线以下松手),
        // 于是:
        //
        // ```
        // 拖过线 → 提交 → 渲染 → slots = 下一页
        // 拉回来 → pageSlide 归 0 → showPageTurn 变假 → drawSlots(slots) = 下一页  ← 跳变
        // 松手取消 → controller 回本页 → 渲染 → slots 又变回来                    ← 跳回
        // ```
        //
        // 用户原话:「拉回来之后候选词短暂的跳变为下一页后又恢复到当前页」。
        //
        // 拖动中那一页的候选在 [pageTo] 里,靠位移滑进来(见 [onPageIndexChanged]
        // 的「拖动中提交」分支 —— 位移接着走、不起动画)。所以 `slots` 保持起手那一页。
        //
        // ## 那提交落定时谁来换?—— [pageSpring] 播完会回调 [onPageSettled]
        //
        // 跳过这一行之后,成功翻页会停在旧页上(动画播完 `clearPageTurn()`,
        // `onDraw` 就只剩 `slots` 可画)。所以收尾必须补一次渲染,
        // 那一页 `pageDragging` 已是 false,`slots` 自然换成新页。
        if (!pageDragging) slots = newSlots
        highlight = state.highlight
        page = state.page
        totalPages = state.totalPages
        invalidate()
    }

    // ---------------------------------------------------------------- 候选词变化动画

    /** 变化中的槽:index → 该槽的入场进度(0 = 还没进,1 = 到位)。空表 = 没有动画在跑。 */
    private val swapSlots = HashMap<Int, Float>()
    private var swapAnim: SpringChannel? = null

    /** 截图脚本与单测可关掉入场动画(它们要的是静止终态)。 */
    @Volatile
    var enterSwapDisabled = false

    /**
     * 候选词变化的入场动画(2026-09-30 用户「候选词变化需要有动画」)。
     *
     * 改组词、选中一个词、翻完页回来 —— 每一槽的词都可能在**同一次按键内**换掉,
     * 此前是直接替换:字"啪"地跳过去。观感上像闪了一下,眼睛要重新找位置。
     *
     * 三条设计:
     * 1. **只动真正变了的槽**:逐槽比 `word`,没变的原地不动 —— 否则每敲一个字母
     *    五把键全抖一遍,比不动画更糟;
     * 2. **从中心往外依次进场**(延迟按离中心的距离排),因为用户第一眼看的是中间那个;
     * 3. **幅度克制**:轻微上移 + 缩放 + 淡入,不飞进飞出 —— 候选栏只有 48dp 高,
     *    飞行的幅度会越界。
     *
     * 曲线走 [Fling.DECELERATE](与全应用一致的非线性缓动)。
     * 翻页过渡与飞字拖动期间**不播**这个动画 —— 那两种已经在动同一个画面,叠加会打架。
     */
    private fun startSwapAnim(
        oldSlots: List<dev.hwkbd.ime.engine.Candidate?>,
        newSlots: List<dev.hwkbd.ime.engine.Candidate?>,
    ) {
        if (enterSwapDisabled || newSlots.isEmpty()) return
        // 正在翻页/飞字就别叠动画了。
        // 翻页判据是 `pageSpring.isRunning` + [pageFrom] 非空 —— 换引擎后 `pageAnim`
        // 恒为 null,若还拿它当判据,翻页动画进行中仍会叠一次候选词替换动画
        if (pageFrom.isNotEmpty() || pageSpring.isRunning || isFlyAnimating()) return

        val changed = ArrayList<Int>()
        for (i in newSlots.indices) {
            val a = oldSlots.getOrNull(i)?.word
            val b = newSlots[i]?.word
            if (a != null && b != null && a != b) changed.add(i)
        }
        if (changed.isEmpty()) return

        swapAnim?.cancel()
        changed.forEach { swapSlots[it] = 0f }
        val total = SwapAnim.totalMs(changed.map { it }, CENTER_SLOT)
        // **弹簧驱动,错峰数学一个字没改**:`SwapAnim.localProgress` 内部本来就把
        // 延迟与时长化成比例(`delayMs/total`、`BASE_MS/total`),所以它要的只是一个
        // 0..1 的全局进度 —— `ValueAnimator.animatedFraction` 与弹簧的 `currentValue`
        // 是同一种东西,换驱动不需要动它。
        val swap = SpringChannel(
            from = 0f,
            spec = SpringSpec.SWAP_IN,
            onFrame = { value, _ ->
                for (i in changed) swapSlots[i] = SwapAnim.localProgress(value, i, total, CENTER_SLOT)
                invalidate()
            },
            onFinish = { cancelled ->
                if (cancelled) return@SpringChannel
                swapSlots.clear()
                invalidate()
            },
        )
        // 从 0 走到 1:弹簧的归一化进度,与旧的 animatedFraction 同义。
        // 赋给字段再 settle:onFrame 回调里要用它读 currentValue,
        // 所以必须先落到字段上(用局部变量则回调闭包捕获不到)。
        swapAnim = swap
        swap.settle(0f, 1f)
    }

    /** 单槽的入场变换:返回 (上移 px, 缩放)。没在动的槽返回恒等,零开销。 */
    private fun swapTransform(i: Int): Pair<Float, Float> {
        val p = swapSlots[i] ?: return 0f to 1f
        return SwapAnim.transform(p)
    }

    /** 单槽的入场透明度。 */
    private fun swapAlpha(i: Int): Float = swapSlots[i]?.let { SwapAnim.alpha(it) } ?: 1f

    /**
     * 页号变了(controller 那边已经改完):把新页接进当前的翻页过渡。
     *
     * 三种情形:
     * - **没有过渡在跑**(箭头点按/方向键/键盘表面横滑):冻结当前页当旧页,整页切过去;
     * - **过渡在跑且方向反了**(松手提交后又被接管、往回拖到提交线以下又松手):
     *   把旧页/新页两个角色对调、进度取镜像(1-u)——**画面完全不动**,只是"接下来去哪儿"换了,
     *   然后继续把剩下的位移补完。这正是用户要的「反向滑动可以反向动画」;
     * - 目标对不上(理论上不会发生,页号一次只走 ±1):退回整页切,宁可跳一下也不画错内容。
     */
    private fun onPageIndexChanged(newPage: Int, newSlots: List<dev.hwkbd.ime.engine.Candidate?>) {
        val dir = if (newPage > lastPage) 1f else -1f
        // ## 撤销中途那次提交:**不是一次新的翻页**(2026-10-09 真机实测)
        //
        // 「过阈值当帧就翻」意味着拖动途中已经 `onPage(+1)` 过。用户拉回线以下松手时
        // [endPageDrag] 会 `onPage(-1)` 撤销 —— 而那个回调是**异步**的,到达时
        // `pageDragging` 已经是 false,于是走下面「整页重切」那条:
        //
        // ```
        // commit: 过线当帧翻 travel=40 thr=20 dir=1.0 slide=40   ← 中途提交(第3→第4页)
        // page end: step=1 by=fling slide=0 landing=6 thr=20 v=394 commit=false page=4/9
        // settle:   from=0 target=1360 thr=448                   ← ★ 撤销后补了整页动画
        // ```
        //
        // `slide=0` 说明用户确实拉回来了(`byFinger`/`byFling` 都为假,走的是取消分支),
        // 但那一整页补完动画把「取消」顶掉了 —— 用户看到的就是「松手之后还是翻页了」。
        //
        // ⇒ 撤销要**就地落定**:不清零、不补完、不重新标记成已提交。
        // 用户为这次取消付的代价应该是 0(位移本来就被他拉回 0 了)。
        if (newPage == pendingRollbackTo) {
            pendingRollbackTo = -1
            pageCommitted = false
            pageSlide = 0f
            pageFrom = emptyList()
            pageTo = emptyList()
            pageDragTravel = 0f
            pageDirMirrored = false
            android.util.Log.i(
                TAG,
                "rollback: 撤销中途那次提交,就地落定 newPage=$newPage 同页=" +
                    "${newSlots == slots} slide=$pageSlide"
            )
            invalidate()
            return
        }
        pendingRollbackTo = -1 // 不是撤销:别让它留给下一次真正的翻页
        pageCommitted = true
        val full = width.toFloat()
        // ## 这三处补完动画的时长**也走 [PageTiming] 公式**,不写死
        //
        // 原来它们直接 `settlePage(full, SpringSpec.PAGE_COMMIT)`,而
        // [SpringSpec.PAGE_COMMIT] 的 response 是**写死的 0.45s**。
        //
        // 实测(2026-10-08 真机):整页从 0 补完时
        //   settle: from=0 target=1360 response=450ms rem=1360 speed=3022px/s entryV=0
        //   ring: 第 1 次 value=1411.72 target=1360   ⇒ **首过超调 51.7px**
        //
        // 而走公式的那一档(rem=669, entryV=1586)超调只有 **4.26px**。
        // 同一个阻尼比、同一个 ζ=1.0,差别只在**时长** ⇒
        // **又是「时间不够」,不是 ζ 的问题。**
        //
        // ⇒ 统一:所有「补完/收回」都经 [PageTiming.withResponse],
        // 于是「不震荡」由**一个公式**保证,而不是每处各写一个常数。
        // 这一档起点是 0、没有抬手速度,所以 `entrySpeed` 取 0 ——
        // 但 `rem=full` 走距离项本身就落在 [PAGE_MAX_RESPONSE]。
        if (pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageFrom = slots
            pageTo = newSlots
            pageDir = dir
            pageSlide = 0f
            if (pageFrom.isNotEmpty() && pageTo.isNotEmpty()) {
                settlePage(full, PageTiming.withResponse(SpringSpec.PAGE_COMMIT, full))
            } else {
                clearPageTurn()
            }
            return
        }
        if (newSlots == pageFrom && pageDir == -dir) {
            // 往回翻:旧页↔新页对调、位移镜像 → 同一画面,换个方向继续
            val f = pageFrom
            pageFrom = pageTo
            pageTo = f
            pageDir = -pageDir
            pageSlide = full - pageSlide
            settlePage(full, PageTiming.withResponse(SpringSpec.PAGE_COMMIT, full))
        } else {
            // 目标对不上:整页重切
            pageFrom = slots
            pageTo = newSlots
            pageDir = dir
            if (pageDragging) {
                // **拖动中提交**(过阈值当帧就翻,[setPageDrag]):位移**接着走**,不回零也不起动画。
                // 回零的话画面会先跳回旧页再滑过去,而手指还按着 —— 看着像被弹回去,
                // 跟"过阈值就翻"想要的连贯正好相反。位移继续由手指驱动,松手时 [endPageDrag] 收尾。
                if (pageFrom.isEmpty() || pageTo.isEmpty()) clearPageTurn()
            } else {
                pageSlide = 0f
                if (pageFrom.isNotEmpty() && pageTo.isNotEmpty()) {
                    settlePage(full, PageTiming.withResponse(SpringSpec.PAGE_COMMIT, full))
                } else {
                    clearPageTurn()
                }
            }
        }
    }

    /**
     * 翻页拖动开始:冻结当前页当过渡的旧页,按方向取相邻页当新页。**可重入**——
     * 正在播动画(上次松手后的正/反向)时接管:同向接着走,反向把两个角色对调、进度取镜像,
     * 手指基线按当前 [pageSlide] 重定 ⇒ 接管那一帧画面不跳,能直接接着拖。
     *
     * ## 「同方向、且本来就是手指在驱动」= **空操作**(2026-10-02 修)
     *
     * 这条守卫的来历值得记下来:[RubberDrag] 的类注释里当年写着
     * 「翻页那边没这个毛病,因为 `beginPageDrag` 在 `pageFrom/pageTo` 非空时**不**重置
     * —— 不对称就在这儿」。**那个结论是错的**:`beginPageDrag` 确实不重置两个角色,
     * 但它**每帧都重设基线**(旧版无条件下 `pageDragBaseX = x + pageSlide * pageDir`),
     * 而 `takeOverPageDrag` 是 MOVE 分支里**每帧**调的。
     *
     * 于是每帧都形成一个不动点:
     * ```
     * baseX  = x + pageSlide * dir
     * travel = -(x - baseX) * dir = pageSlide        ← 恒等于当前位移
     * slide  = slide(travel, …)      = pageSlide     ← 位移原地不动
     * ```
     * 手指在动、位移锁死在 0 ⇒ **跟手动画从来没有、提交线永远到不了**,
     * 抬手一律走 `endPageDrag` 的反向收回分支 —— 读起来就是"怎么划都是反向动画"。
     * 症状与"跟手没实现"完全一致,所以前几轮都往动画参数上找,方向全错。
     *
     * 同一处的 `pageSpeed.reset()` 每帧执行还有第二个后果:**惯性速度恒为 0**,
     * 甩也甩不动。
     *
     * 所以规矩与 [RubberDrag.begin] 对齐:**同方向重复调用必须是空操作**。
     * 只有三种情况才算真正的起手,才允许动基线和速度:
     * 1. 角色还没定(首次起手);
     * 2. 方向反了(两个角色对调、位移取镜像,基线要跟着换算);
     * 3. 同方向但手指**不在**驱动(接管上一次松手后正在飞的动画)—— 此时基线要
     *    重定到当前位移,否则新落指的 x 与上一条手势的基线毫无关系,位移会跳。
     */
    private fun beginPageDrag(dir: Float, x: Float, eventTimeMs: Long = 0L) {
        val rolesSet = pageFrom.isNotEmpty() && pageTo.isNotEmpty()
        // 埋点:这一行是「一次 swipe 触发了**几次**起手」的唯一证据(2026-10-05)。
        //
        // 此前 `page begin` 只在 [takeOverPageDrag] 打,而它每次接管都打 ——
        // 于是**看不出是同一个手势打了两次,还是两次独立手势**:
        // 2026-10-05 E2E 里一次 swipe 打出两次 `page begin`,与「翻页没生效」现场撞在一起。
        //
        // `rolesSet` / `pageDragging` / `pageCommitted` 三个状态都打出来,
        // 因为**走哪个分支**完全由它们决定,而那个分支正是当时推不出的那一环:
        // `rolesSet=false` 会重定基线并把 `pageCommitted` 抹成 false,
        // `rolesSet=true && 同向 && 正在拖` 则整个函数是空操作。
        // ## 这行必须打在**去重判断之后**,而且要打两次
        //
        // 原来它在 [beginPageDrag] 开头打印 ⇒ 读到的是**重置之前**的
        // `pageCommitted`。而 `rolesSet=false` 那条分支在几行之后会把它抹成 false,
        // 于是日志与真实状态**不一致**。
        //
        // 2026-10-08 真机 E2E 就是被这个骗了一轮:看到
        // `rolesSet=false ... pageCommitted=true` 判定「状态没清干净」,
        // 而那条路径**本来就会清** —— 真正的现象是那次手势压根没走到 `page end`,
        // 跟 `pageCommitted` 无关。
        //
        // ⇒ 打两行:`begin#` 是**进入时**的快照(含「来之前是什么」),
        // `begin*` 是**决定之后**的快照。两者不一致本身就是信息
        // (说明走了重置分支),一致则说明是空操作路径。
        android.util.Log.i(
            TAG,
            "page begin#: dir=$dir rolesSet=$rolesSet pageDragging=$pageDragging " +
                "pageCommitted=$pageCommitted page=$page/$totalPages slide=${pageSlide.toInt()}"
        )
        if (rolesSet && dir == pageDir && pageDragging) {
            // 同向且手指正在驱动 → 空操作:基线与速度都不动,只继续喂样本
            android.util.Log.i(
                TAG,
                "page begin*: dir=$dir rolesSet=$rolesSet noop(同向且正在拖) " +
                    "pageCommitted=$pageCommitted page=$page/$totalPages"
            )
            if (eventTimeMs > 0L) pageSpeed.add(eventTimeMs, x)
            return
        }
        val full = width.toFloat()
        pageSpeed.reset()
        if (eventTimeMs > 0L) pageSpeed.add(eventTimeMs, x)
        val rebased = !rolesSet
        if (!rolesSet) {
            pageDir = dir
            pageDragOriginPage = page // 撤销要回到的就是它(见 pendingRollbackTo)
            pageFrom = slots
            pageTo = if (dir > 0f) pageNextSlots else pagePrevSlots
            pageSlide = 0f
            pageCommitted = false
            pageDirMirrored = false // 新手势,闩锁复位
        } else if (dir != pageDir) {
            if (!allowPageMirror(pageSlide, pageCommitPx(), pageDirMirrored)) {
                // 反向但不镜像:**基线不动**,让 [setPageDrag] 按手指把它带回 0。
                // 这时 `travel` 变负 ⇒ [DragGesture.slide] 夹到 0 ⇒ 位移自然回原页,
                // 而弹簧还没起跑,所以没有任何跳变。
                if (eventTimeMs > 0L) pageSpeed.add(eventTimeMs, x)
                return
            }
            val f = pageFrom
            pageFrom = pageTo
            pageTo = f
            pageDir = dir
            pageSlide = full - pageSlide
            pageDirMirrored = true
        }
        // 手指接管:取消弹簧动画,但**保留 [pageSlide] 当前值** ——
        // 下一帧 `setPageDrag` 会按它重算基线,所以画面不会跳。
        // (换引擎时这里取消的是一个已恒为 null 的 `pageAnim`,弹簧其实还在跑。)
        pageSpring.cancel()
        pageDragging = pageFrom.isNotEmpty() && pageTo.isNotEmpty()
        // 去重判断之后的快照 —— 见上面那段说明:只有这行里的 `pageCommitted`
        // 才是**这一帧真正生效**的值,`page begin#` 那行是「来之前是什么」。
        if (rebased) {
            android.util.Log.i(
                TAG,
                "page begin*: dir=$dir rebased pageDragging=$pageDragging " +
                    "pageCommitted=$pageCommitted page=$page/$totalPages"
            )
        }
        // 旧页当前停在 -pageSlide(方向由 pageDir 带),把基线对齐到这个位移,手指接着走
        pageDragBaseX = x + pageSlide * pageDir
    }

    /**
     * 跟手:**画面位移 = 手指位移**(死区内按 [DragGesture.DEAD_SLIP] 打折,上限整行宽)。
     * 手指往回拖位移就变小——那就是"在收回",松手不过线会反向动画退回原页。
     *
     * 提交判定用的进度另存在 [pageDragTravel],绘制不掺它(否则又变成放大)。
     */
    private fun setPageDrag(x: Float, eventTimeMs: Long = 0L) {
        if (!pageDragging) return
        val w = width.toFloat()
        if (eventTimeMs > 0L) pageSpeed.add(eventTimeMs, x)
        val travel = pageDragTravel(x, pageDragBaseX, pageDir)
        val slide = pageDragSlide(x, pageDragBaseX, pageDir, w, pageCommitPx(), pageDragDeadPx())
        pageDragTravel = travel
        if (slide != pageSlide) {
            pageSlide = slide
            invalidate()
        }
        // **过阈值当帧就翻**,与上滑一致(2026-10-02 用户「改为过阈值当帧就翻」):
        // 手指不用抬,拖过线就换页。
        // 提交后画面**不由动画接管**(见 [onPageIndexChanged] 里 `pageDragging` 那条分支)——
        // 手指还按着,位移继续由这里驱动,松手时 [endPageDrag] 收尾。
        if (!pageCommitted &&
            DragGesture.progress(travel, pageCommitPx(), pageDragDeadPx()) >= 1f
        ) {
            // 埋点:提交是**当帧**发生的,而 `pageCommitted` 要等 render 回调
            // ([onPageIndexChanged]) 才置位。这中间如果手指在阈值附近抖动,
            // 就会重复触发 —— 那是「小幅多次振盪」的另一个候选机制。
// **必须在这里同步置位**(2026-10-09 用户报「手指在键盘上的抖动也识别了」)
            //
            // 原来只发 `onPage(...)`,指望 [onPageIndexChanged] 回来把标志置上。
            // 那个回调跨进程异步、外面还套着 renderPump 合并 —— 于是一个 MOVE 帧里
            // 就能连发好几次 `onPage(±1)`,页码一次跳两三级。
            // 抖动不需要多大:只要在提交线附近来回,每一次「过线当帧」都成立。
            //
            // 置位挪到这里之后,[onPageIndexChanged] 里那句无条件
            // `pageCommitted = true` 变成幂等的,不必再动。
            pageCommitted = true
android.util.Log.i(
                TAG,
                "commit: 过线当帧翻 travel=${travel.toInt()} thr=${pageCommitPx().toInt()} " +
                    "dir=$pageDir slide=${pageSlide.toInt()}"
            )
            onPage?.invoke(if (pageDir > 0f) 1 else -1)
            haptics?.confirm()
        }
    }

    /**
     * **横滑接管**:有相邻页就起跟手翻页,到头就起橡皮筋。@return 是否接管了。
     *
     * ## 为什么要抽出来(2026-10-02 真机报「弹性动画和跟手动画还是没有」)
     *
     * 此前这段决策只写在 [onTouchEvent] 的 MOVE 分支里,而**键盘表面上左右划也是翻页**
     * —— 那条路的事件根本到不了候选行,于是它走的是 [ImeService] 里一条
     * 「抬手 → `pageStep()`」的旧路径:**抬手才翻、整条拖动不画位移、到头什么都不发生**。
     * 同一个"翻页"有两套实现,于是"跟手和弹性到底有没有"取决于手落在哪块屏幕上。
     *
     * 抽出来之后两条路共用:跟手 1:1、过阈值当帧提交、到头橡皮筋拉伸+弹回+轻震,
     * 全部只有一份实现,不会再分叉。**各自不同的只有提交线的量纲**(见 [externalCommitPx])。
     */
    private fun takeOverPageDrag(dir: Float, x: Float, eventTimeMs: Long, external: Boolean): Boolean {
        // 翻页接管时正在上滑的词要放回去,别两条拖动同时跑
        if (flyP >= 0f && !isFlyAnimating()) cancelFlyDrag()
        // **本次拖动是谁发起的**:两条路共用绘制,但提交线的量纲不同(见 [pageCommitPx]),
        // 所以每次起手都要重新标一遍 —— 不能靠 externalCommitPx != 0 判断,
        // 那个值是常驻的,会顺带把候选行自己的翻页也改成 60px(划一下就翻)。
        externalDrive = external
        // ## 归属一旦确定就不再重判(2026-10-09)
        //
        // 本函数每个 MOVE 帧都被调,而「这一侧还有没有页」这个判据**在一次手势之内会变**:
        // 到头起手那一帧 `pagePrevSlots` 是空的(橡皮筋拿到了手势),
        // 紧接着在飞的翻页回滚经 `onPageIndexChanged` 把上一页填回去,
        // 下一帧 `neighbour.isNotEmpty()` 就又成立 ⇒ 走进 if 分支 ⇒
        // 里面的 `settleRubberBack()` 把橡皮筋 `stop()` 掉 ⇒ 抬手时 `rubber=false`。
        //
        // 真机实测(2026-10-09):`takeover: 到头 … accepted=true` 之后 85ms,
        // 抬手分流表打出 `rubber=false pageDragging=true pageFrom=5`
        // —— 橡皮筋一帧都没真正驱动过,需求二在真机上从未发生。
        //
        // 所以这条判据必须**在任何邻居查询之前**先问一次:
        // 「这条手势已经归橡皮筋了吗?」是了就一直是它的。
        // 与 [RubberDrag.begin]「同方向已在拖时是空操作」是同一条规矩的两半。
        if (rubberDrag.tracking && rubberDrag.dir == dir) {
            return beginRubber(dir, x) // 同方向是空操作,返回 true 让调用方继续 [setRubber]
        }
        val neighbour = if (dir > 0f) pageNextSlots else pagePrevSlots
        // 那一侧有页 → 跟手翻页;没有页 → **橡皮筋拉伸**(到头也有反馈,
        // 此前是整条拖动都不接管,手指在动屏幕纹丝不动,像卡了)
        return if (neighbour.isNotEmpty() && neighbour.any { it != null }) {
            // **反向从橡皮筋接管**:拉伸量弹回,而不是被清零(见 [settleRubberBack])
            if (rubberDrag.tracking || rubberDrag.raw > 0f) settleRubberBack()
            // 埋点:跟手翻页接管。`springRunning` 是这次排查的关键 ——
            // 用户报「滑动没有打断动画」,而这条分支里的 [beginPageDrag] 会 cancel 弹簧,
            // 所以这里打出 true 就说明打断**应该**发生,false 则说明根本没接管到。
            android.util.Log.i(
                TAG,
                "takeover: 跟手翻页 dir=$dir springRunning=${pageSpring.isRunning}"
            )
            // ## `page begin` 必须**只在真正起手那一帧**打,不能每帧都打
            //
            // [takeOverPageDrag] 是在**每个 MOVE 帧**被调用的(见它的文档),
            // 而这条日志原来打在 [beginPageDrag] 的**去重判断之前** ⇒
            // 一次 swipe 打出 N 条 `page begin`,只有第一条对应真正的起手。
            //
            // **症状(2026-10-08 真机 E2E 首次跑到):** `select-global-index` 场景
            // 打印「`page begin# 2` 一次手势起了两次手」,而实际那次 `page end`
            // 带着 `commit=false` ⇒ 断言判「没翻页」。**引擎与手势链路其实都是通的**
            // (同批 `page-flip` 场景全绿、页码递增正确),红的是这条日志的次数。
            //
            // 为什么以前没暴露:2026-10-05 修过一次**同源**的问题,方向相反 ——
            // 当时是「一次 swipe **没打** page begin」。同一个判断,两个症状;
            // 两次都因为「这条日志是去重判断的前哨,而不是结果」。
            //
            // ⇒ 判据改成 `beginPageDrag` 内部的去重结论:只有**这一次是起手**
            // (`rolesSet` 为 false,即 `pageFrom` 还是空的)才打。
            // ## `page begin` 只打「真正起手」的那一帧,而判据是 **`rolesSet`**
            //
            // [takeOverPageDrag] 在**每个 MOVE 帧**被调用,所以不能每帧都打。
            //
            // **第一版用 `pageFrom.isEmpty()` 当判据 —— 错了(2026-10-08)。**
            // 它只在「新基线」时为 true,而**打断弹簧接管**那条路径
            // (`rolesSet=true`、带着已存在的 `pageFrom`)于是**一次都不打**。
            // 症状:E2E 的 `page-flip` 从全绿变红,断言「日志应含
            // `page begin: dir=-1.0`」失败 —— 而那次翻页**功能上是好的**
            // (`page end: step=-1 … commit=true page=1/9`)。
            //
            // 这与 2026-10-05 那次是**同一个 bug 的另一个方向**:
            // 当时是「该打时没打」,更早是「每帧都打」。三次都因为
            // **判据选错了量** —— 这次选的是内部状态 `pageFrom`,
            // 而该问的是「这次调用算不算一次新意图」。
            //
            // ⇒ 判据:**没有正在进行的拖动**,或**方向变了** —— 两者都是用户的
            // 新意图。只有「同向且 `pageDragging` 为真」才是空操作。
            //
            // 注意这里**不能**引用 `rolesSet`:它是 [beginPageDrag] 的局部变量,
            // 而这个判断在它被调用**之前**。`pageDragging` 是同一件事的等价量 ——
            // 它在 [beginPageDrag] 末尾按 `pageFrom/pageTo` 非空设置,
            // 而 [takeOverPageDrag] 返回 true 恰好意味着那两个列表非空。
            val isNewGesture = !pageDragging || dir != pageDir
            if (isNewGesture) {
                // 与 [endPageDrag] 里 `step` 同一套符号约定:dir>0 = 往下一页
                android.util.Log.i(
                    TAG,
                    "page begin: dir=$dir → 跟手翻页 $page→${page + (if (dir > 0f) 1 else -1)} " +
                        "(page=$page/$totalPages)"
                )
            }
            beginPageDrag(dir, x, eventTimeMs)
            true
        } else {
            // ## 一次手势只有一个主人:这段清理**只在第一帧跑**(2026-10-09)
            //
            // 本函数是在**每个 MOVE 帧**被调的,而这个 else 分支原来也无条件跑。
            // 一屏 200ms 的滑动里它跑了 12 次,于是:
            //
            // 1. `reversePageTurn()` → `settlePage(0, ROLLBACK)`。此刻 `from == target == 0`
            //    但 `pageFrom` 非空 ⇒ `settlePage` 走**动画**分支而不是早退分支
            //    ⇒ `clearPageTurn()` 永远不执行(实测一屏 12 条一模一样的
            //    `settle: from=0 target=0`,而且每条都从 denormal 值重启弹簧:
            //    `interrupt: from=4.2E-45 v=-1.4E-44`)。
            // 2. `reversePageTurn()` 把上一页填回 `pagePrevSlots` ⇒ [beginRubber] 的
            //    `atEnd` 判据随之失败 ⇒ **橡皮筋每一帧都被拒**。
            //    而「到头走橡皮筋」那条日志在 `beginRubber` **之前**打,
            //    于是它宣称走了橡皮筋,实际一帧都没走成 —— **日志在说谎**。
            // 3. `pageFrom` 永不清空 ⇒ ACTION_UP 被下面 `if (pageFrom.isNotEmpty()) return true`
            //    静默吃掉 ⇒ [endRubber] 一次都不执行。
            //
            // 净效果:需求二「到头继续滑动要有橡皮筋动画」**在真机上完全没发生过**,
            // 而且三条现象在日志里都表现为「什么都没有」。
            //
            // 修法沿用 [RubberDrag.begin] 自己那条规矩(同方向已在拖时是空操作):
            // **这条手势已经归橡皮筋了,就一直是它的** —— 不因为中途 slots 被填回去而反悔。
            val rubberOwns = rubberDrag.tracking && rubberDrag.dir == dir
            if (!rubberOwns) {
                // **反向从翻页接管**:把这次翻页收干净再交给橡皮筋。
                // 不收的话 [pageDragging] 与 [rubberDrag.active] 会同时为真,
                // [pageDragMove] 的 when 里橡皮筋优先 —— 翻页那条名义上在拖却吃不到事件,
                // 抬手又走 [endRubber],于是 pageDragging 永远清不掉,画面卡在中间态。
                if (pageDragging || pageFrom.isNotEmpty()) {
                    android.util.Log.i(
                        TAG,
                        "takeover: 反向收回 reversePageTurn springRunning=${pageSpring.isRunning}"
                    )
                    reversePageTurn()
                } else if (pageSpring.isRunning) {
                    // **弹簧在跑、但既不是跟手拖动也不是翻页回收** ⇒ 手指抢过来的是橡皮筋。
                    // 原先这里什么都不做,于是 [beginRubber] 起橡皮筋的同时
                    // pageSpring 还在写 pageSlide —— **两支动画同时动同一行**,
                    // 读作「滑动没有打断动画」,而抬手时 [pageDragEnd] 只走 endRubber,
                    // pageSpring 没人管,自己跑完。
                    //
                    // 这里显式取消并把位移落回 0(取消会把弹簧停在半路,不落定就是错位的画面)。
                    android.util.Log.i(TAG, "takeover: 取消在飞的翻页动画(此前没人管它)")
                    pageSpring.cancel()
                    pageSlide = 0f
                    clearPageTurn()
                }
            }
            val accepted = beginRubber(dir, x)
            // 带上 totalPages:2026-10-03 这个 bug(只有一页 → 恒「到头」)就是靠
            // 「dir 两个方向都到头」推出来的,而**页数**是那条线索的直接证据。
            //
            // `accepted` 必须打出来 —— 原来这条日志在 [beginRubber] 之前打,
            // 于是「宣称走了橡皮筋」与「真的走了橡皮筋」分不开,正是它掩盖了上面那个 bug。
            android.util.Log.i(
                TAG,
                "takeover: 到头 dir=$dir accepted=$accepted rubberOwns=$rubberOwns " +
                    "springRunning=${pageSpring.isRunning} (page=$page/$totalPages)"
            )
            return accepted
        }
    }

    /**
     * **打断时的反向收回**(2026-10-02 用户「左右滑动动画打断之后需要反向动画」)。
     *
     * 手势往没有页的那一侧走 = 用户改主意了,这一页不该翻过去。
     * 所以把进行中的过渡**动画收回原页**:既不走完(那是强按用户的意思),
     * 也不瞬移(那一下跳变正是"打断之后没有反向动画"的观感)。
     *
     * 已提交过的还要把那次提交撤回来,否则 controller 的页号和画面对不上
     * —— 与 [endPageDrag] 的反向分支同一套做法。
     */
    private fun reversePageTurn() {
        val wasCommitted = pageCommitted
        pageDragging = false
        pageCommitted = false
        if (wasCommitted) onPage?.invoke(if (pageDir > 0f) -1 else 1)
        settlePage(0f, SpringSpec.PAGE_ROLLBACK)
    }

    /**
     * 键盘表面横滑翻页的**提交线**(px),由 [ImeService] 注入指尖位移档位。
     *
     * **0 = 用候选行自己的量纲**(行宽 × [pageCommitFrac])。
     *
     * 两处翻页共用跟手绘制与橡皮筋,但**提交线必须各按各的**:键盘表面上能划的距离
     * 本来就小(触摸区就那么大),照搬"行宽 1/3 = 475px"会让轻轻一划根本翻不动。
     * 所以候选行按行宽比例取,键盘面按指尖位移档位取 —— 见
     * [ImeService.FlyDetector.pageThresholdPx]。
     */
    var externalCommitPx = 0f

    /** 本次拖动是不是键盘表面驱动进来的(见 [pageDragBegin])。决定 [pageCommitPx] 走哪条量纲。 */
    private var externalDrive = false

    // ---- 振铃探测的状态(埋点,见 pageSpring 的 onFrame)---------------------
    /** 上一次采样时 `value` 在目标的哪一侧:-1 / 0 / +1。 */
    private var pageRingSide = 0
    /** 这一段动画里已经穿过目标几次。 */
    private var pageRingCount = 0
    /**
     * 这一段动画在用的物理参数 —— **不能读 `pageSpring.spec`**:
     * 埋点在 `pageSpring` 自己的 `onFrame` 里,那是字段初始化期,自引用编译不过。
     */
    /**
     * 本次手势是否已经镜像过方向(闩锁,见 [allowPageMirror])。
     *
     * 一次 `ACTION_DOWN`..`ACTION_UP` 之间只允许镜像一次,
     * 抬手时由 [endPageDrag] / [clearPageTurn] 清掉。
     */
    private var pageDirMirrored = false

    private var pageRingSpec: SpringSpec = SpringSpec.DEFAULT

    /**
     * 本次翻页拖动的提交线(px,**从基线量起**)。
     *
     * 两条路给同一个函数,量纲却不同:
     * - 候选行自己划:行宽 × [pageCommitFrac] —— 本来就是从基线量的,直接用;
     * - 键盘表面划([externalCommitPx]):档位**本身就是死区之外的提交距离**
     *   (2026-10-02 用户「键盘面改用死区外的提交距离,不再含死区」)——
     *   [beginPageDrag] 把基线定在死区被跨过的那一帧,死区已经走完了,所以直接用,
     *   只过一遍下限夹值([externalCommitFromBase])。
     *
     * **不能只看 [externalCommitPx] != 0**:那是常驻值,不区分发起方就会把候选行
     * 自己的翻页也改成键盘面的阈值(轻轻一划就翻页)。所以用 [externalDrive] 这个
     * 每次起手重新标的状态来判。
     */
    private fun pageCommitPx(): Float = pageCommitLine(
        externalDrive = externalDrive,
        externalPx = externalCommitPx,
        commitFrac = pageCommitFrac,
        viewW = width.toFloat(),
    )

    /**
     * 本次拖动的**死区**(px):键盘表面驱动时是 **0**。
     *
     * [beginPageDrag] 把基线定在"死区被跨过的那一帧" —— 那 32px 死在基线**之前**,
     * 已经走完了。所以从基线量的行程里**不能再扣一次死区**:
     * `DragGesture.progress` 是 `(travel - dead) / (threshold - dead)`,
     * 死区传两次就会把分母算成负数(死区 16、提交线 8 ⇒ 进度 NaN)。
     * 候选行自己的拖动基线就在 downX,所以照常传 [pageDeadPx]。
     */
    private fun pageDragDeadPx(): Float = if (externalDrive) 0f else pageDeadPx

    /**
     * 键盘表面横滑翻页:**起手**。@return 是否接管(到头时接管的是橡皮筋,一样返回 true)
     *
     * 与候选行自己收到的那条横滑走的是同一个 [takeOverPageDrag],所以手感不会分叉。
     * 坐标用哪个空间都行 —— 位移只取差值([pageDragTravel] 只用 `x - baseX`)。
     */
    fun pageDragBegin(dir: Float, x: Float, eventTimeMs: Long = 0L): Boolean =
        takeOverPageDrag(dir, x, eventTimeMs, external = true)

    /** 键盘表面横滑翻页:**跟手**。橡皮筋与翻页拖动各自喂自己那条。 */
    fun pageDragMove(x: Float, eventTimeMs: Long = 0L) {
        when {
            rubberDrag.active -> setRubber(x, eventTimeMs)
            pageDragging -> setPageDrag(x, eventTimeMs)
        }
    }

    /** 键盘表面横滑翻页:**收尾**。跟手拖动走翻页判定,橡皮筋走弹回。 */
    fun pageDragEnd(x: Float) {
        // **`externalDrive` 必须在分发之后才清**(2026-10-04 修,真机报「小幅多次振盪」)。
        //
        // 原来它在前面就被清成 false,而 [endPageDrag] 里面才调 [pageCommitPx] ——
        // 于是**松手时的提交线用错了量纲**:键盘表面驱动时读成
        // `行宽 × pageCommitFrac`(Titan 2 上 302px),而不是档位的「死区外提交距离」(8px)。
        //
        // 症状(埋点实测,commit 时 thr=8 而 end/settle 时 thr=302):
        //   1. 手指划过 8px 就**当帧提交**翻页(拖动中的判定是对的);
        //   2. 划到 30~300px 松手,松手时用 302px 判 → `byFinger` 为**假** → **撤销提交**;
        //   3. 于是「翻过去又翻回来」,画面来回抽动 —— 这就是那个振铃。
        // 而且 `byFling` 要求惯性落点 ≥ 302px,快甩的落点只有几十 px ⇒ **甩也甩不动**。
        //
        // 这与 [externalCommitPx] 注释里记的那条是同一类错:**发起方必须在读提交线的时刻还在位**。
        when {
            rubberDrag.active -> {
                setRubber(x)
                endRubber()
            }
            pageDragging -> endPageDrag(x)
        }
        externalDrive = false
    }

    /** 键盘表面横滑翻页:**被打断**(事件取消)——当作松手,别把候选行卡在拖动态。 */
    fun pageDragCancel() {
        // 同 [pageDragEnd]:分发之后再清标志([reversePageTurn] 走的是收回,
        // 但保持一致,免得下次有人加一条读提交线的分支又踩到)
        if (rubberDrag.active) cancelRubber()
        if (pageDragging) reversePageTurn()
        externalDrive = false
    }

    /**
     * 松手:过提交线才真的翻页(**先把目标页交给 controller**,再补完剩下的位移);
     * 没过线就把这次过渡反向动画收回原页——若上一次松手已经提交过(接管后往回拖的情况),
     * 还要把那次提交也撤回来,否则 controller 的页号和画面会脱节。
     *
     * 补完动画按**惯性**走(2026-09-30):松手速度决定滑多远、补多久;速度够快时
     * 还没过提交线也能"甩"过去 —— 这是真实物理里的甩动,此前只有死板的手指位移说了算。
     */
    private fun endPageDrag(x: Float) {
        if (!pageDragging) return
        setPageDrag(x) // 抬手前最后跟一次手指
        pageDragging = false
        pageDirMirrored = false // 手势结束,闩锁复位(clearPageTurn 不一定走到)
        val step = if (pageDir > 0f) 1 else -1
        val full = width.toFloat()
        val threshold = pageCommitPx()
        // 速度取沿翻页方向的符号:往下一页(dir=+1)时手指向左,速度也要是"朝新页"才算甩
        val v = pageSpeed.velocity() * pageDir
        // 惯性**只用来判「甩不甩得过去」**,不参与落点(见下面提交分支的注释)
        val landing = Landing.project(pageSlide, full, v)
        // 提交线:手指位移过线 **或** 惯性落点在过线一侧(甩过去)
        val byFinger = DragGesture.progress(pageDragTravel, threshold, pageDragDeadPx()) >= 1f
        val byFling = v > 0f && landing >= threshold
        if (byFinger || byFling) {
            // 埋点:`pageCommitted` 决定要不要发 `onPage`,而它此前**没有**被记录 ——
            // 于是「发了提交但页码没动」与「压根没发」在 logcat 里同形(2026-10-05)。
            // `willSend` 是那一行的判据,打出来才能把两种情况分开。
            android.util.Log.i(
                TAG,
                "end: 判定提交 pageCommitted=$pageCommitted willSend=${!pageCommitted} " +
                    "travel=${pageDragTravel.toInt()} thr=${threshold.toInt()} dead=${pageDragDeadPx().toInt()}"
            )
            if (!pageCommitted) {
                onPage?.invoke(step)
                haptics?.confirm() // 翻页提交的一次轻震:跟飞字过阈值同一套反馈语义
            }
            pageCommitted = true
            android.util.Log.i(
                TAG,
                "end: 提交 byFinger=$byFinger byFling=$byFling v=${v.toInt()} " +
                    "slide=${pageSlide.toInt()} thr=${threshold.toInt()} " +
                    "page=$page/$totalPages"
            )
            // **落到 `full`,不是落到 `landing`**(2026-10-04 修)。
            //
            // 原来这里落的是 `Landing.project(pageSlide, full, v)` —— 那个函数答的是
            // 「如果任它惯性滑行,会停在哪」,不是「这一页还剩多少没走完」。
            // 于是一次翻页的**整页行程(pageSlide: 0 → width = 1440px)从来没走完**:
            // 过提交线那一帧 `pageSlide` 才十几 px,惯性最多再推 `v²/(2a)` ≈ 166px,
            // 于是弹簧只从十几 px 走到一百多 px,然后 `onFinish` 直接 `clearPageTurn()`
            // —— 画面是**跳**到新页的,不是滑过去的。
            //
            // 症状就是用户报的「松手时翻不过去」+「翻过去/收回的动画不对」,
            // 以及「翻頁困難」:整页没走完 ⇒ 视觉上根本不像发生了一次翻页。
            //
            // **分工**:惯性只决定**翻不翻**([byFling]),落点由「走完 / 回原位」决定。
            // 这与 [SpringChannel.settle] 故意不给速度参数是同一个道理:
            // 同一个物理量只在一个地方计入。
            // `v` 是抬手瞬间沿翻页方向的速度(已按 `pageDir` 归一),
            // **它决定时长**而不只是落点 —— 见 [PageTiming.response]:
            // 临界阻尼永不过冲的前提是「给足时间把进场速度吃掉」,
            // 而真机实测 ζ=1.0 仍首过超调 14px,就是因为时长只按行程算、没算它。
            val (t, sp) = pageSettleFor(
                committed = true,
                full = full,
                from = pageSlide,
                entrySpeed = v,
            )
            settlePage(t, sp)
        } else {
            // 撤销上一次提交。**必须先打标记**:那个回调是异步的,到达时
            // `pageDragging` 已经是 false,不告诉视图「这是撤销」的话,它会当成一次
            // 新的翻页补一整页动画,把这次取消顶掉(见 [onPageIndexChanged])。
            if (pageCommitted) {
                pendingRollbackTo = pageDragOriginPage
                onPage?.invoke(-step)
            }
            pageCommitted = false
            android.util.Log.i(
                TAG,
                "end: 收回 byFinger=$byFinger byFling=$byFling v=${v.toInt()} " +
                    "slide=${pageSlide.toInt()} thr=${threshold.toInt()} " +
                    "page=$page/$totalPages"
            )
            // 同理:收回的落点是 **0**(回原页),不是 `pageSlide - 惯性`。
            val (t, sp) = pageSettleFor(
                committed = false,
                full = full,
                from = pageSlide,
                entrySpeed = v,
            )
            settlePage(t, sp)
        }
        // 这条日志是**必需**的,不是调试残留(2026-10-03 E2E 才发现):
        //
        // 横滑在 MOVE 里就被接管去画跟手动画(`FlyDetector.paging = true`),
        // 抬手走的是这里 —— **不经过** ACTION_UP 那条兜底分支。
        // 而 `fly judge` / `fly page: next` 两条日志都只打在兜底分支上,
        // 于是「翻页成功」这条**正常路径在 logcat 里一个字都没有**:
        // E2E 场景 page-flip 断言 `fly judge` + `fly page: next`,三条全红,
        // 看起来像翻页坏了,其实它可能好得很 ———只是没有证据。
        //
        // 判据选「过线方式」而不只是「翻没翻」,是因为到头橡皮筋那条路
        // ( neighbour 为空 → 接管但没有新页)也走同一个 endPageDrag,
        // 只看结果分不出「翻页」与「到头弹回」。
        android.util.Log.i(
            TAG,
            "page end: step=$step by=${if (byFinger) "finger" else "fling"} " +
                "slide=${pageSlide.toInt()} landing=${landing.toInt()} " +
                "thr=${threshold.toInt()} v=${v.toInt()} commit=${byFinger || byFling} " +
                "page=$page/$totalPages"
        )
    }

    /**
     * 把剩下的位移补完/收回:**target 是像素**——`width` = 走到新页,0 = 回旧页。
     * 播完即清过渡(稳态直接画 [slots])。
     *
     * ## 走官方弹簧,不再用 `ValueAnimator`(2026-10-02 用户「使用物理引擎重构手势和动画」)
     *
     * **落点**仍由 [Landing.project] 定(匀减速 + 库仑摩擦算惯性还能滑多远),
     * **怎么过去**交给 [SpringChannel] 的官方 `SpringAnimation`。分工见 [Landing]。
     *
     * 换掉固定时长动画器消掉的是三件事,都是真机报过的:
     * 1. **松手那一下的顿挫**:旧代码 `ValueAnimator.ofFloat(from, target)` 从**零初速**起步,
     *    而此刻指尖正以 `v0` 离开 —— 画面速度瞬间从 `v0` 掉到 0。现在把 `v0` 作为
     *    弹簧初速度交进去,速度是连续的。
     * 2. **打断必跳变**:动画播到一半被手指按住,旧代码要 `cancel()` 再开一个新动画器,
     *    新动画器又从静止起步 ⇒ 顿一下。现在**同一个 channel 上 [SpringChannel.retarget]**
     *    改目标,值与速度都保留。
     * 3. **时长与行程脱钩**:旧代码时长 = `stopDurationMs(v0)`,与还剩多少距离无关 ——
     *    差 1px 和差半页用同一个时长。现在时长由弹簧按行程与初速自己算。
     */
    private fun settlePage(target: Float, spec: SpringSpec = SpringSpec.DEFAULT) {
        val from = pageSlide
        if (from == target || pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageSpring.cancel()
            pageSlide = target
            clearPageTurn()
            invalidate()
            return
        }
        pageSettleTarget = target
        // 每一段动画重新数振铃(埋点,见 onFrame 里的说明)
        pageRingSide = 0
        pageRingCount = 0
        pageRingSpec = spec
        // 入口速度:静止起步应是 0;**非零说明是被手指打断带进来的**
        // (`animateToFinalPosition` 保留速度)。
        val entryV = pageSpring.currentVelocity
        pageSpring.settle(from, target, spec)
        // **必须打 response**:这一版的时长是**算出来的**(PageTiming.withResponse),
        // 不打出来的话,用户再报「还是快」时只能靠猜 —— 而这一版的主题就是
        // 「量,不要猜」。剩下的行程也一并打出来,那是 response 的输入。
        val remaining = target - from
        android.util.Log.i(
            TAG,
            "settle: from=${from.toInt()} target=${target.toInt()} ζ=${spec.dampingRatio} " +
                "response=${(spec.response * 1000).toInt()}ms rem=${remaining.toInt()} " +
                "speed=${if (spec.response > 0f) (kotlin.math.abs(remaining) / spec.response).toInt() else 0}px/s " +
                "entryV=${entryV.toInt()} thr=${pageCommitPx().toInt()}"
        )
    }

    /**
     * [settlePage] 的落点,给弹簧走完时收尾用。
     *
     * 单列一个字段,而不是在 `onFinish` 里读 `pageSpring.target` —— 那会让字段初始化时
     * 自引用,Kotlin 编译不过(`must be initialized`)。语义上也更直白:
     * **"我这次要落到哪"是调用方的意图,不是引擎的状态**。
     */
    private var pageSettleTarget = 0f

    /**
     * 翻页位移的弹簧通道。**生命周期比一次手势长**,所以打断时复用同一支而不是重开 ——
     * 这正是 [SpringChannel.settle] 要解决的。
     */
    private val pageSpring: SpringChannel = SpringChannel(
        from = 0f,
        spec = SpringSpec.DEFAULT,
        onFrame = { value, velocity ->
            // ---- 振铃探测(2026-10-04 埋点,用户报「小幅多次振盪」)------------
            //
            // **不能在这里读 `pageSpring` 自己** —— 字段初始化时自引用,
            // Kotlin 编译不过(`must be initialized`)。与 [pageSettleTarget] 同一个坑,
            // 所以速度用回调给的第二参数,物理参数用 [pageRingSpec] 这个独立字段。
            //
            // **过冲每穿过目标一次,`(value - target)` 的符号就翻一次。**
            // 数符号翻转次数 = 振铃次数,而且**一次振铃必然一次翻转** ——
            // 这让它成为无歧义的判据:
            //   0 次 ⇒ 从没穿过目标(单调逼近)
            //   1 次 ⇒ 单次过冲(ζ<1 的正常表现)
            //   ≥3 次 ⇒ 真在振荡。而 ζ=0.72/0.95 的欠阻尼解**不可能**这样
            //          ⇒ 说明不是弹簧本身在振,是别的东西在动
            //
            // 只在翻转时打一行,所以日志量与振铃次数成正比(而不是每帧)。
            val side = if (value < pageSettleTarget) -1 else if (value > pageSettleTarget) 1 else 0
            if (side != 0 && pageRingSide != 0 && side != pageRingSide) {
                pageRingCount++
                android.util.Log.i(
                    TAG,
                    "ring: 第 $pageRingCount 次穿过目标 value=$value target=$pageSettleTarget " +
                        "ζ=${pageRingSpec.dampingRatio} v=${velocity.toInt()}"
                )
            }
            if (side != 0) pageRingSide = side
            // --------------------------------------------------------------
            pageSlide = value
            invalidate()
        },
        onFinish = { cancelled ->
            // 被打断时**不能**收尾:新的一段(或手指)马上接管,此刻清过渡会把新状态一起清掉。
            // 这条区分是旧代码反复出问题的根源之一 —— `cancel()` 与 `onAnimationEnd` 混为一谈。
            if (cancelled) return@SpringChannel
            pageSlide = pageSettleTarget
            clearPageTurn()
            invalidate()
            // **必须报收尾**(2026-10-09):[setState] 在拖动中**不**把 `slots` 换成新页
            // (中途那次提交是暂定的,换了就会「短暂跳到下一页又跳回来」),
            // 所以成功翻页之后得有人来渲染一趟,那一页 `pageDragging` 已是 false,
            // `slots` 才会换成新页。不报收尾 = 翻完了停在旧页。
            // 与 [onFlyFinished] 同一套理由。
            onPageSettled?.invoke()
        },
    )

    private fun clearPageTurn() {
        pageFrom = emptyList()
        pageTo = emptyList()
        pageSlide = 0f
        pageDragTravel = 0f
        pageDirMirrored = false // 手势已结束(或从未开始),闩锁复位
    }

    /** 飞字拖动开始:冻结当前候选,后续 [setFlyDrag] 按手指进度实时驱动。可重入(拖回又拖上来时接管淡回)。 */
    fun beginFlyDrag(zone: Int) {
        if (flyCompleting) return
        if (flyP >= 0f && flyZone == zone && !isFlyAnimating()) return
        cancelFlyDragNow()
        flyZone = zone
        flyX = (zone + 0.5f) * cellWidth
        flyWord = slots.getOrNull(zone)?.word ?: ""
        flyOutSlots = slots
        flyP = 0f
        flySpeed.reset()
        invalidate()
    }

    /**
     * 飞字拖动进度(0..1):词随手指上移,其余候选按比例渐隐。
     *
     * 顺带记录指尖速度(飞出去多远由它决定)与**过阈值的一次震动反馈**:
     * 上滑越过阈值是"这次上屏定了"的确认点,给一下轻震让手指不用看屏幕也知道成了 ——
     * 2026-09-30 用户要求。震动只在这一处触发(首次越过阈值那一下),拖动过程中不震,
     * 免得一路嗡嗡。
     */
    fun setFlyDrag(progress: Float, eventTimeMs: Long = 0L) {
        if (flyP < 0f || flyBack.isRunning) return
        val p = progress.coerceIn(0f, 1f)
        val crossed = flyP < 1f && p >= 1f // 本次拖动里第一次越过阈值
        // 速度在 ACTION_MOVE 里用**原始 y** 统一采样(见那里的注释),这里不再重复喂。
        flyP = p
        if (crossed) haptics?.confirm() // 过阈值 = "这次上屏定了"
        invalidate()
    }

    /**
     * 拖动被放弃(松手未过阈值/转为横滑/取消):整组淡回原位。
     *
     * **走官方弹簧**(2026-10-02 用户「向上滑动飞字也需要物理引擎」)。
     * 旧实现是 `ValueAnimator` + `stopDurationMs(flyP * 1000f)`:`flyP` 是**进度**(0..2)
     * 不是速度,乘 1000 当 px/s 是个凑数,而且时长固定 —— 于是"轻轻推一下却飞很久"。
     * 现在时长由弹簧按行程算,放弃与起飞是同一支弹簧,松手瞬间不会顿。
     */
    fun cancelFlyDrag() {
        if (flyP < 0f || flyBack.isRunning) return
        flyBack.settle(flyP, 0f)
    }

    /** 放弃淡回的弹簧:[SpringSpec.FLY_BACK](过阻尼,回来时不要来回晃)。 */
    private val flyBack = SpringChannel(
        from = 0f,
        spec = SpringSpec.FLY_BACK,
        onFrame = { value, _ ->
            flyP = value
            invalidate()
        },
        onFinish = { cancelled ->
            if (cancelled) return@SpringChannel
            resetFly()
        },
    )

    private fun cancelFlyDragNow() {
        flyBack.cancel()
        resetFly()
    }

    private fun resetFly() {
        flyP = -1f
        flyZone = -1
        flyWord = ""
        flyOutSlots = emptyList()
        flyCompleting = false
        invalidate()
    }

    /**
     * **飞字动画彻底结束了**(`flyP` 已回到 [resetFly] 的"不在飞"状态)。
     *
     * 2026-10-02 真机报「偶发拼音都消费完还显示候选词栏」。根因就是缺这个信号:
     * 候选行靠 [isFlyAnimating] 保持可见(service 的两态判定),而归位原先只靠一个
     * **猜的**固定延时(260ms)。可完成段动画最长 [Fling.MAX_MS] = 520ms ——
     * 动画还没结束那次归位就白跑了,而动画自己结束时只有 `invalidate()`,不触发渲染,
     * 于是候选行卡在可见态,直到下一次别的事件碰巧渲染一把(所以看着"偶发")。
     *
     * **按键选词必踩**:那条路没有拖动、没有速度,时长走 `remain * 1000f` 的兜底 = 520ms,
     * 正好是 260ms 的两倍。
     *
     * 让动画自己报完成,状态就跟着动画走,不再有"猜早了/猜晚了"这回事。
     */
    var onFlyFinished: (() -> Unit)? = null

    /**
     * 翻页补完/收回动画**播完**。
     *
     * 存在的理由见 [pageSpring] 的 onFinish 与 [setState] 里那句
     * 「拖动中那次提交是暂定的」:拖动中不换 slots,所以收尾要有人来渲染一趟。
     */
    var onPageSettled: (() -> Unit)? = null

    /**
     * 飞字完成段:从当前拖动进度(没拖过则从 0)飞到 flyP=2。
     * 越过阈值即调用,所以拖到 1 时只剩完成段动画;词起飞后再由调用方选字上屏。
     */
    fun startFlyAnim(displayIndex: Int, word: String) {
        // 先把要继续的拖动状态抓出来:下面 cancel() 会走 resetFly 把它们清掉
        val from = flyP.coerceAtLeast(0f)
        val zone = if (flyZone >= 0) flyZone else displayIndex
        val x = if (flyZone >= 0) flyX else (displayIndex + 0.5f) * cellWidth
        val w = if (flyWord.isNotEmpty()) flyWord else word
        val out = if (flyOutSlots.isNotEmpty()) flyOutSlots else slots
        flyBack.cancel()
        flyZone = zone
        flyX = x
        flyWord = w
        flyOutSlots = out
        flyP = from
        flyCompleting = true
        // **弹簧从静止起跑**(与翻页同一套分工,见 SpringChannel.settle):
        // 词是"飞走"不是"被甩出去",落点恒为 2,没有惯性能算 —— 所以速度不参与,
        // 由弹簧按剩余行程自己决定飞多久。
        flyUp.settle(from, 2f)
    }

    /** 起飞弹簧:欠阻尼,和翻页一样要有到位感(这是"确认上屏"的那一下)。 */
    private val flyUp = SpringChannel(
        from = 0f,
        spec = SpringSpec.FLY_UP,
        onFrame = { value, _ ->
            flyP = value
            invalidate()
        },
        onFinish = { cancelled ->
            if (cancelled) return@SpringChannel
            resetFly()
            // **必须报完成**(见 onFlyFinished):service 靠 isFlyAnimating 判两态,
            // 不报就没人重新渲染,候选行会卡在可见态
            onFlyFinished?.invoke()
        },
    )

    /**
     * 每槽 = **全宽 / 5**——Titan 2 物理键盘正好 10 列,槽宽 = 2 个键列,
     * 分隔线、词位、点按与上滑触发区都与物理键列对齐(2026-09-29 用户定案)。
     * 两侧翻页箭头占的窄条只挡点按/起手入口([cellIndexAt]),不移动分区边界。
     */
    private val cellWidth: Float get() = width / 5f

    private fun cellIndexAt(x: Float): Int {
        if (slots.all { it == null }) return -1
        if (x < arrowWidth || x >= width - arrowWidth) return -1
        val idx = ((x / cellWidth).toInt()).coerceIn(0, 4)
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
                lastX = downX
                lastY = downY
                longPressFired = false
                externalDrive = false
                // 滑动许可只在这里问一次(见 swipeGate);点按与长按不看它
                swipeArmed = swipeGate?.invoke() != true
                // 越界橡皮筋的起点:从哪按下就从哪算(2026-09-30 用户「翻页到头要有拉伸变形动画」)
                rubberDrag.reset()
                postDelayed(longPressRunnable, 600L)
                performClick()
            }
            MotionEvent.ACTION_MOVE -> {
                // 速度跟踪喂**原始位移**:SpeedTracker 自己按相邻两点差分算速度,
                // 而这里算过的 travel 是"扣死区后的进度化位移",喂进去算出的速度是错的。
                // 只在真的开始拖动后才喂 —— 按下到过死区之间手是静止的,不必。
                if (pageDragging || rubberDrag.active || flyP >= 0f) {
                    pageSpeed.add(event.eventTime, event.x)
                    flySpeed.add(event.eventTime, event.y)
                    rubberSpeed.add(event.eventTime, event.x)
                }
                lastX = event.x
                lastY = event.y
                // 移动出按下槽位 / 移动超过触摸容差则取消长按(上滑拖动中绝不能误触发长按删除)
                if (!longPressFired && (
                    cellIndexAt(event.x) != downCell ||
                        kotlin.math.abs(event.x - downX) > touchSlop ||
                        kotlin.math.abs(event.y - downY) > touchSlop
                    )
                ) {
                    removeCallbacks(longPressRunnable)
                }
                // 上滑选字改成"拖动即反馈":词随手指起升、其余候选渐隐(拖回来又拖上去时重新接管)
                // 死区内(≤ 阈值 1/4)整体忽略:不启动拖动;过死区后进度从 0 重算
                val dx = event.x - downX
                val dy = downY - event.y
                val adx = kotlin.math.abs(dx)
                val ady = kotlin.math.abs(dy)
                // 翻页先判:横滑接管时正在上滑的词要放回去,别两条拖动同时跑
                var takeOverPage = false
                if (swipeArmed && swipePageEnabled && adx > pageDeadPx && adx > ady) {
                    val dir = if (dx < 0f) 1f else -1f
                    takeOverPage = takeOverPageDrag(dir, event.x, event.eventTime, external = false)
                }
                when {
                    // 越界橡皮筋优先:到头的那一侧要给出拉伸反馈,不能跟翻页抢
                    rubberDrag.active -> setRubber(event.x, event.eventTime)
                    // 横滑翻页同样改成"拖动即反馈":整页跟手指走,松手过提交线才真的翻页
                    // (与上滑同一套模型:死区 → 跟手 → 松手判定 → 没过线反向动画收回)
                    takeOverPage || pageDragging -> setPageDrag(event.x, event.eventTime)
                    swipeArmed && swipeSelectEnabled && downCell >= 0 && dy > flyDeadPx && dy > adx -> {
                        beginFlyDrag(downCell) // 自带防重入
                        setFlyDrag(DragGesture.progress(dy, flyThresholdPx, flyDeadPx), event.eventTime)
                    }
                    flyP >= 0f && !isFlyAnimating() -> cancelFlyDrag()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                // 抬手的分流表。**必须打**,而且要在任何 return 之前。
                //
                // 2026-10-09:需求二查不出来,就是因为这一段全是静默 return ——
                // 「抬手被哪个分支吃掉了」在日志里只能靠猜。三个 owner 的归属
                // 一次就能在这里对完账。
                android.util.Log.i(
                    TAG,
                    "touch UP: rubber=${rubberDrag.active} raw=${rubberDrag.raw.toInt()} " +
                        "pageDragging=$pageDragging pageFrom=${pageFrom.size} dir=$pageDir " +
                        "springRunning=${pageSpring.isRunning}"
                )
                // 拖动过阈值 → 在此提交(松手即飞完);没过 → 淡回原位;正在淡回则忽略本次抬手
                if (flyP >= 0f) {
                    if (!isFlyAnimating()) {
                        val dx = event.x - downX
                        val dy = downY - event.y
                        if (dy > 0f && dy > kotlin.math.abs(dx)) {
                            setFlyDrag(DragGesture.progress(dy, flyThresholdPx, flyDeadPx), event.eventTime)
                        }
                        if (flyP >= 1f) {
                            val zone = flyZone
                            val word = flyWord
                            startFlyAnim(zone, word)
                            onSelect?.invoke(zone)
                        } else {
                            cancelFlyDrag()
                        }
                    }
                    return true
                }
                val dx = event.x - downX
                val dy = event.y - downY
                // 橡皮筋中 → 弹回边界(不翻页:那一侧本来就没有页)
                if (rubberDrag.active) {
                    setRubber(event.x, event.eventTime)
                    endRubber()
                    return true
                }
                // 翻页拖动中 → 松手判定(过线提交 / 没过线反向收回)
                if (pageDragging) {
                    endPageDrag(event.x)
                    return true
                }
                // 接管后的反向动画还在播:本次抬手不重复判定
                if (pageFrom.isNotEmpty()) return true
                when {
                    longPressFired -> Unit // 长按已在定时器里处理
                    kotlin.math.abs(dx) < touchSlop && kotlin.math.abs(dy) < touchSlop -> onTap(downX)
                    swipeArmed && swipeSelectEnabled && dy < -flingDistance && -dy > kotlin.math.abs(dx) -> {
                        val i = cellIndexAt(downX)
                        if (i >= 0) onSelect?.invoke(i)
                    }
                    // **候选词上横向滑动也能翻页**(用户 2026-09-30「在候选词上滑动一样也可以翻页」):
                    // 抬手时若位移够大且横向占优,直接翻。之前只有点两侧箭头能翻,
                    // 拖动必须过死区才接管,快速轻扫会落进 touchSlop 里什么都不发生。
                    swipeArmed && swipePageEnabled && kotlin.math.abs(dx) > flingDistance &&
                        kotlin.math.abs(dx) > kotlin.math.abs(dy) -> {
                        onPage?.invoke(if (dx < 0) 1 else -1)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                cancelFlyDrag()
                // 橡皮筋被打断:直接弹回,不弹震动(不是用户主动确认的动作)
                cancelRubber()
                // 翻页拖动被系统打断:当作松手,走同一套判定(过线提交/没过线收回)
                if (pageDragging) endPageDrag(downX)
            }
        }
        return true
    }

    // ---------------------------------------------------------------- 越界橡皮筋(拉伸变形)

    /**
     * 翻到头还继续拖时的**拉伸变形**(2026-09-30 用户「翻页到翻到头要有拉伸变形动画」)。
     *
     * 首/末页往回拖时没有相邻页,此前是**整条拖动都不接管**,于是手指在动、屏幕纹丝不动 ——
     * 那不是"到头了"的反馈,是"卡了"。真实的边界手感是**弹性**:拉得动,有阻力,
     * 松手弹回去,而且拉得越远越拉不动。
     *
     * 做法:
     * - [RubberDrag.raw] = 手指超出边界的那段位移(px),方向朝外为正;
     * - 显示位移 = [RubberDrag.raw] 经阻尼([Rubber.shift]),越拉越沉,封顶 [rubberLimit];
     * - 松手 → 弹回 0(动画,非瞬移),并给一次轻震说"到头了"。
     *
     * 只在**两侧都没有相邻页**时启用;还有页可翻时照常跟手翻页,不与之冲突。
     */
    /** 橡皮筋松手瞬间的指尖速度(px/s):弹回时长由它决定,拉得快就弹得快。 */
    private val rubberSpeed = SpeedTracker()

    /**
     * 橡皮筋的拖动状态(纯类,见 [RubberDrag])。
     *
     * **这里原来有三个字段(`rubberOn`/`rubberRaw`/`rubberDir`),搬进来是因为那条
     * 「每帧重置基线」的 bug 只有在状态被类型管住之后才不会再来一遍** ——
     * 详见 [RubberDrag] 的类注释。
     */
    private val rubberDrag = RubberDrag()

    /**
     * 橡皮筋阻尼长度(px):约 dip(50)。公式与封顶在 [Rubber] 里(纯函数、可单测)。
     * 渐近于这个长度 ⇒ 拉多远都不会飞出去。
     */
    private fun rubberScale(): Float = dip(50).toFloat()

    /** 橡皮筋位移封顶(px):dip(56),略大于阻尼长度但仍是"看得见的拉伸"。 */
    private fun rubberLimit(): Float = dip(56).toFloat()

    /** 开始橡皮筋(方向 dir:+1 拖向下一页方向,-1 上一)。@return 是否真的接管了 */
    private fun beginRubber(dir: Float, x: Float): Boolean {
        val atEnd = (dir > 0f && pageNextSlots.none { it != null }) || (dir < 0f && pagePrevSlots.none { it != null })
        // **已经在同一条橡皮筋上就算继续给它**,不看 atEnd。
        //
        // 2026-10-09:`reversePageTurn()` 会把上一页填回 `pagePrevSlots`,
        // 而它就在 [beginRubber] 之前跑(见 [takeOverPageDrag])⇒ 橡皮筋起手的
        // 那一帧必然 `atEnd == false` ⇒ **一次都起不来**。
        // 「到头」是这次手势开始时就定下的事实,不该因为中途把 slots 收干净而翻案 ——
        // 那正是 [RubberDrag.begin]「同方向已在拖时是空操作」那条规矩的另一半:
        // **归属一旦确定就不再重判**。
        val owned = rubberDrag.tracking && rubberDrag.dir == dir
        if (!atEnd && !owned) return false
        // **同方向已在拖时 begin 是空操作**(RubberDrag 保证)。这里可以每帧无条件调 ——
        // 之前就是这里每帧重置基线,把位移清成 0,橡皮筋于是永远画不出来
        rubberDrag.begin(dir, x)
        return true
    }

    /** 橡皮筋拖动进度。 */
    private fun setRubber(x: Float, eventTimeMs: Long = 0L) {
        if (!rubberDrag.tracking) return
        rubberDrag.move(x)
        invalidate()
    }

    /** 松手:弹回边界。 */
    private fun endRubber() {
        // ## 这条埋点必须**无条件**打,而且必须在最前面(2026-10-09)
        //
        // 原来 `runRubberAnim` 里才有日志,而 `runRubberAnim` 只在 `raw > 0` 时才被调。
        // 于是三种完全不同的情况在日志里**长得一模一样:什么都没有**:
        //   ① ACTION_UP 根本没进这个 View
        //   ② 进了但 `tracking=false`(橡皮筋中途被谁抢走了)
        //   ③ 进了、`tracking=true`,但 `raw == 0`(拉了一路却一像素都没形变)
        //
        // 需求二「到头继续滑动要有橡皮筋动画」当时就是**这样无法验收的** ——
        // 不是断言写错,是根本没有可观测量。
        val wasTracking = rubberDrag.tracking
        val rawAtRelease = rubberDrag.raw
        if (!wasTracking) {
            android.util.Log.i(
                TAG,
                "rubber end: decide=not-tracking raw=${rawAtRelease.toInt()} dir=${rubberDrag.dir}"
            )
            return
        }
        rubberDrag.stop() // 只停"手指不再驱动",方向与位移留给弹回动画用
        if (rubberDrag.raw <= 0f) {
            android.util.Log.i(
                TAG,
                "rubber end: decide=zero-raw raw=0 dir=${rubberDrag.dir}"
            )
            rubberDrag.reset()
            invalidate()
            return
        }
        haptics?.confirm() // 到头了:给一下震说"没下一页了"
        // 回弹时长按**松手速度**给(与翻页/飞字同一套惯性):拉得快弹得快。
        // 速度样本不足时按"拉伸量 ∝ 12"折算,保证拉得越远弹得越慢。
        val v = rubberSpeed.velocity()
        // **`decide=` 一律用 ASCII**:logcat 的中文经 adb → shell → 文件这条路会被
        // 按 GBK 解一次,`→` 变成乱码,断言里的正则就再也匹配不上(2026-10-09 栽过)。
        // 机器读的判据不该依赖编码。
        android.util.Log.i(
            TAG,
            "rubber end: decide=spring raw=${rubberDrag.raw.toInt()} " +
                "shift=${Rubber.shift(rubberDrag.raw, rubberScale(), rubberLimit()).toInt()} " +
                "v=${v.toInt()} dir=${rubberDrag.dir}"
        )
        runRubberAnim(if (kotlin.math.abs(v) >= Landing.MIN_VELOCITY) v else rubberDrag.raw * 12f)
    }

    /**
     * **打断时的反向回弹**(2026-10-02 用户「左右滑动动画打断之后需要反向动画」)。
     *
     * 正在拉伸时手势反向(往有页的那一侧划走)时用:拉伸量**从当前值弹回 0**,
     * 不是被 [cancelRubber] 归零 —— 归零那一帧画面会跳一下,那正是
     * "打断之后没有反向动画"的观感。
     *
     * **不弹轻震**:那下震是给"用户拉到底又松手"的,不是给"他改主意了"的。
     */
    private fun settleRubberBack() {
        rubberDrag.stop()
        if (rubberDrag.raw <= 0f) {
            rubberDrag.reset()
            invalidate()
            return
        }
        runRubberAnim(rubberDrag.raw * 12f)
    }

    /**
     * 回弹动画本体([endRubber] 主动松手 / [settleRubberBack] 被打断共用)。
     *
     * **走官方弹簧**:旧代码 `ValueAnimator` + `stopDurationMs(v0)` 固定时长,
     * 撞墙那一刻画面速度从指尖速度掉到 0(顿一下),而且中途再撞一次就重开动画器、又顿一次。
     * 现在 [SpringSpec.RUBBER_BACK] 是**过阻尼**(ζ=2.4)的弹簧 ——「撞到头」本来就该是
     * "顶住了滑回来",不是弹簧床来回晃;并且打断时改目标保留速度,不再重复顿挫。
     */
    /**
     * 橡皮筋弹回。
     *
     * ## 目标恒为 0,这里**不用** [Landing](2026-10-09)
     *
     * 「落点」那套是为**有另一个合法状态**的运动准备的:翻页可以落到下一页、
     * 飞字可以落到下一格,`target - from` 是两个合法位置之差。
     *
     * 橡皮筋不是这种运动:边界是**绝对的**,拉伸量必须回到 0,没有「越过去」这个状态
     * —— [Rubber.shift] 对 `raw <= 0` 直接返回 0,越界渲染不出来。
     *
     * 而 [Landing.project] 的落点被 `coerceIn(min(from,target), max(from,target))`
     * **夹在起点与目标之间**,所以 `project(raw, 0f, v)` 的返回值恒在 `[0, raw]` ——
     * **永远不可能是 0**。真机实测(2026-10-09):
     *
     * ```
     * rubber end:    raw=291 shift=87 v=3500 dir=-1.0 → 弹回
     * rubber settle: from=291 target=291 v=3500 engZ=2.4
     * ```
     *
     * `from == target` ⇒ 弹簧一帧内就到终点 ⇒ `onFinish` 立刻 `reset()`
     * ⇒ **松手后橡皮筋是「啪」地消失,没有回弹动画**。
     * 这正是需求二「有橡皮筋动画」在真机上从来没发生过的原因。
     *
     * ⇒ 目标写死 0,让弹簧自己决定怎么走回去。`Landing` 的语义在橡皮筋上不成立,
     * 硬套进来只会得到一个「什么都不做」的落点。
     */
    private fun runRubberAnim(v: Float) {
        val from = rubberDrag.raw
        // 每一段橡皮筋动画重数振铃(埋点,理由与翻页的 `ring:` 一样)
        rubberRingSide = 0
        rubberRingCount = 0
        rubberSettleTarget = 0f
        // 埋点:把 [Landing] 本来会算出来的落点也打出来,好让「为什么不用它」有据可查:
        // 它恒在 [0, raw],即**永远到不了 0**。
        android.util.Log.i(
            TAG,
            "rubber settle: from=${from.toInt()} target=0 v=${v.toInt()} " +
                "landingIfUsed=${Landing.project(from, 0f, v).toInt()} " +
                "spec=${SpringSpec.RUBBER_BACK} engK=${rubberSpring.force.stiffness} " +
                "engZ=${rubberSpring.force.dampingRatio}"
        )
        rubberSpring.settle(from, 0f)
    }

    /** 橡皮筋这一段动画的目标。振铃判据用的就是这个,不是 `0`。 */
    private var rubberSettleTarget = 0f

    /** 上一帧落在目标的哪一侧;`0` 表示还没定边。见 [rubberSpring] 里的说明。 */
    private var rubberRingSide = 0

    /** 这一段橡皮筋动画已经穿过目标几次。 */
    private var rubberRingCount = 0

    /**
     * 橡皮筋弹回用的弹簧通道。
     *
     * ## 振铃探测:目标恒在动,所以判据只能是「有没有来回穿」
     *
     * 翻页那一支的目标是固定的 `full`,而橡皮筋的目标由 [Landing] 给,
     * **两支的振铃含义不同**:
     * - 翻页:目标是「这一页的终点」,穿过去 = 视觉上过冲 = bug;
     * - 橡皮筋:目标是「松手后按摩擦还能退多远」,它**本来就可能在 0 另一侧**
     *   (那正是"甩出去"的语义),所以不能断言「不越目标」,只能断言**不来回**。
     *
     * ⇒ 判据是**穿越次数**而不是「有没有越界」:[RUBBER_BACK] 是过阻尼(ζ=2.4),
     * 从静止出发的解 `(A+Bt)e^(-ωt)` 是单调的 ⇒ 期望值是 **0 次**。
     * 真出现 ≥2 次就说明在振荡,那时这条埋点才有价值。
     */
    private val rubberSpring = SpringChannel(
        from = 0f,
        spec = SpringSpec.RUBBER_BACK,
        onFrame = { value, velocity ->
            val side = when {
                value < rubberSettleTarget -> -1
                value > rubberSettleTarget -> 1
                else -> 0
            }
            if (side != 0 && rubberRingSide != 0 && side != rubberRingSide) {
                rubberRingCount++
                android.util.Log.i(
                    TAG,
                    "rubber ring: 第 $rubberRingCount 次穿过目标 value=$value " +
                        "target=$rubberSettleTarget v=${velocity.toInt()}"
                )
            }
            if (side != 0) rubberRingSide = side
            rubberDrag.animateTo(value)
            invalidate()
        },
        onFinish = { cancelled ->
            if (cancelled) return@SpringChannel
            rubberDrag.reset()
            invalidate()
        },
    )

    /**
     * 取消橡皮筋并立刻归零。
     *
     * **必须取消 [rubberSpring]**,不只是 `rubberDrag.reset()`:弹簧还在跑的话,
     * 下一帧的回调会把 `raw` 又写回去 —— 画面弹一下回来,橡皮筋"归零不掉"。
     * (换引擎时这里曾只取消一个已恒为 null 的 `rubberAnim`,正是这个坑。)
     */
    private fun cancelRubber() {
        rubberSpring.cancel()
        rubberDrag.reset()
        invalidate()
    }

    /** 当前橡皮筋显示位移(px,已阻尼并封顶)。 */
    private fun rubberShift(): Float = Rubber.shift(rubberDrag.raw, rubberScale(), rubberLimit())

    /**
     * 越界拉伸的绘制:横向压缩 + 沿越界方向平移一点。
     *
     * 压缩是**围绕行中心**的,不是把整行推向某一侧 —— 推向一侧看着像"内容没对齐",
     * 围绕中心压缩才像"这层被拽住变窄"。平移给出方向:往没有下一页的那侧拉。
     */
    private fun drawRubber(canvas: Canvas) {
        val shift = rubberShift()
        val scale = rubberSquash()
        val savedAlpha = wordPaint.alpha
        val savedColor = wordPaint.color
        // **裁剪在本区域内**(用户「起始动画不要超过边距 …… 不要影响到区域以外」):
        // 压缩后的行宽比视口窄,平移一点就会露边,露出来的部分直接切掉。
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        // 围绕中心压缩
        canvas.scale(scale, 1f, width / 2f, height / 2f)
        // 再沿越界方向拉出(方向由 [RubberDrag.dir] 带)
        canvas.translate(-rubberDrag.dir * shift, 0f)
        drawSlots(canvas, slots, highlight, dividers = true)
        canvas.restore()
        wordPaint.alpha = savedAlpha
        wordPaint.color = savedColor
    }

    /**
     * 橡皮筋的**形变比例**:横向压缩(1 − 比例)并沿越界方向拉出一点。
     *
     * 只压不拉的形变看着像"卡住了被顶回来";配合横向拉伸才有"这层有弹性"的观感 ——
     * 就像 iOS 列表到底时那样。比例取 [rubberShift] / 行宽,天然很小(封顶 56dp),
     * 所以形变幅度也小,不会把 5 个槽挤到认不出。
     */
private fun rubberSquash(): Float = 1f - Rubber.squash(rubberShift(), width.toFloat())

private fun onTap(x: Float) {
        when {
            x < arrowWidth -> onPage?.invoke(-1)
            x >= width - arrowWidth -> onPage?.invoke(1)
            else -> cellIndexAt(x).takeIf { it >= 0 }?.let { onSelect?.invoke(it) }
        }
    }

    override fun onDraw(canvas: Canvas) {
        // ## 埋点:这一帧**真的走了哪条绘制分支**(2026-10-09)
        //
        // 起因:用户报「部分消费后 hao 不出候选」,而控制器侧一路正常
        // (`query#N … n=45`、组词区也写对了)。也就是说断点在**状态 → 画面**之间,
        // 而那一段此前一个字都没记 —— 「状态对、画不出」与「状态就空」在 logcat 里同形。
        // 这是同一个错第四次:`imeShown()` vs 组词态、`ring:` vs 振幅、
        // `spec.ζ` vs 引擎收到的值、这一次是**内部状态 vs 用户看到的画面**。
        //
        // 只在签名变化时打一行,并且签名用 **Int 哈希**而不是字符串:
        // `onDraw` 每帧都跑,每帧拼一个字符串是白付的分配(动画 600ms 就是几十个)。
        val nonNull = slots.count { it != null }
        val kind = when {
            flyP >= 0f -> 1
            pageFrom.isNotEmpty() && pageTo.isNotEmpty() && pageSlide > 0f -> 2
            rubberDrag.raw > 0f -> 3
            else -> 0
        }
        val sig = kind * 31 + nonNull * 17 + pageFrom.size * 13 + pageTo.size * 7 +
            pageSlide.toInt() * 3 + rubberDrag.raw.toInt() * 5 + width * 11 + height
        if (sig != lastDrawSig) {
            lastDrawSig = sig
            android.util.Log.i(
                TAG,
                "draw: kind=${KIND_NAME[kind]} drawn=$nonNull/${slots.size} " +
                    "words=${slots.filterNotNull().joinToString("|") { it.word }} " +
                    "page=$page/$totalPages hl=$highlight flyP=$flyP " +
                    "turn=${pageFrom.size}/${pageTo.size} slide=${pageSlide.toInt()} " +
                    "rubber=${rubberDrag.raw.toInt()} size=${width}x$height vis=$visibility"
            )
        }
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 翻页箭头(到边界置暗)
        val dim = arrowPaint.alpha
        arrowPaint.alpha = if (page > 0) 0xFF else 0x40
        canvas.drawText("◀", arrowInset - arrowPaint.measureText("◀") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = if (page < totalPages - 1) 0xFF else 0x40
        canvas.drawText("▶", width - arrowInset - arrowPaint.measureText("▶") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = dim

        // 翻页:旧页随手指/动画滑出,新页从另一侧滑入(等宽等距 ⇒ 中线恒过中心键心/中缝)
        val showPageTurn = flyP < 0f && pageFrom.isNotEmpty() && pageTo.isNotEmpty() && pageSlide > 0f
        if (flyP >= 0f) {
            drawFly(canvas) // 飞字拖动/完成段:词起升,其余候选渐隐
        } else if (showPageTurn) {
            drawPageTurn(canvas, extraSquash = rubberShift())
        } else if (rubberDrag.raw > 0f) {
            // 越界拉伸:整行横向压缩 + 沿越界方向拉出,键帽跟着变形(2026-09-30 用户要"拉伸变形动画")
            drawRubber(canvas)
        } else {
            drawSlots(canvas, slots, highlight, dividers = true)
        }
    }

    /**
     * 翻页的绘制:旧页随手指/动画滑出,新页从另一侧滑入(等宽等距 ⇒ 中线恒过中心键心/中缝)。
     *
     * @param extraSquash 额外叠一层横向压缩(px,已阻尼)。**翻页接管自橡皮筋时非零** ——
     * 那一侧的拉伸量正在弹回,把它套在翻页外面,于是"拉伸松开"与"整页跟手"**同时**发生。
     * 不这么合成的话只能二选一:先弹完再翻,中间那一帧整页会突然出现在已经走过的位移上
     * —— 那一下跳变正是"打断之后需要反向动画"要消掉的东西。
     */
    private fun drawPageTurn(canvas: Canvas, extraSquash: Float = 0f) {
        val savedAlpha = wordPaint.alpha
        if (extraSquash > 0f) canvas.save()
        if (extraSquash > 0f) {
            // 裁剪在本区域内(同 [drawRubber]):压缩后比视口窄,露出来的部分切掉
            canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
            canvas.scale(1f - Rubber.squash(extraSquash, width.toFloat()), 1f, width / 2f, height / 2f)
        }
        // 画面位移直接用 [pageSlide](px,= 手指位移),不再"进度 × 行宽"——那会放大 3 倍。
        val slide = pageDir * pageSlide
        val frac = (pageSlide / width).coerceIn(0f, 1f) // 只用于透明度过渡
        // 旧页:向翻页方向滑出
        wordPaint.alpha = ((1f - frac) * 255).toInt()
        canvas.save()
        canvas.translate(-slide, 0f)
        drawSlots(canvas, pageFrom, highlightOf = -1, dividers = false)
        canvas.restore()
        // 新页:从翻页反方向滑入
        wordPaint.alpha = (frac * 255).toInt()
        canvas.save()
        canvas.translate(-slide + pageDir * width, 0f)
        drawSlots(canvas, pageTo, highlight, dividers = false)
        canvas.restore()
        wordPaint.alpha = savedAlpha
        if (extraSquash > 0f) canvas.restore()
    }

    /**
     * 飞字绘制。flyP 一根数轴两段:
     * 拖动段 [0,1] 跟手指——词缓慢上移(0→26dp)、其余候选线性渐隐(→0.4);
     * 完成段 (1,2] 越过阈值后接管——词加速飞到 64dp 并淡出,其余候选加速淡完。
     */
    private fun drawFly(canvas: Canvas) {
        val p = flyP
        val drag = if (p < 1f) p else 1f
        val s = if (p > 1f) p - 1f else 0f
        // 完成段的快进慢停由 [Fling.DECELERATE] 这个**时间**维度的插值器负责,
        // `s` 当线性比例用。此前在绘制里手乘 `s²` 造曲线,是把时间曲线混进空间计算:
        // 时长、抬升、淡出三处各乘一遍,调任何一个都会牵动另外两个(2026-09-30 改)。
        val others = if (s <= 0f) 1f - 0.6f * drag else 0.4f * (1f - s)
        val out = if (flyOutSlots.isNotEmpty()) flyOutSlots else slots
        drawSlots(canvas, out, highlightOf = -1, dividers = others > 0.02f, alpha = others, skip = flyZone)

        if (flyWord.isEmpty()) return
        val base = 17f * resources.displayMetrics.scaledDensity
        // 飞的是中槽第一候选时,把行内的强调(主色+略大)原样带上,飞行前后观感一致
        val first = flyZone == CENTER_SLOT
        wordPaint.textSize = base *
            (if (flyWord.length > 6) 0.7f else 1f) *
            (if (first) FIRST_BOOST else 1f) *
            (1f + 0.2f * (p / 2f))
        val savedColor = wordPaint.color
        wordPaint.color = if (first) accentColor else savedColor
        // setColor 连 alpha 一起覆盖:先上色、后设透明度,否则完成段的淡出被抹掉
        wordPaint.alpha = ((if (s <= 0f) 1f else 1f - s) * 255).toInt()
        val lift = drag * dip(26) + (if (s <= 0f) 0f else (dip(64) - dip(26)) * s * s)
        val w = wordPaint.measureText(flyWord)
        val x = (flyX - w / 2f).coerceIn(dip(8), width - w - dip(8))
        val y = height / 2f - (wordPaint.ascent() + wordPaint.descent()) / 2f - lift
        canvas.drawText(flyWord, x, y, wordPaint)
        wordPaint.color = savedColor
        wordPaint.textSize = base
        wordPaint.alpha = 255
    }

    private fun drawSlots(
        canvas: Canvas,
        list: List<dev.hwkbd.ime.engine.Candidate?>,
        highlightOf: Int,
        dividers: Boolean,
        alpha: Float = 1f,
        skip: Int = -1,
    ) {
        val savedAlpha = wordPaint.alpha
        val savedColor = wordPaint.color
        // 目标透明度:飞字走 alpha 参数;翻页动画由调用方先设好 alpha(取进画笔的 savedAlpha)。
        // 关键:Paint.setColor 连 alpha 一起覆盖——循环里每次上色后必须补设,
        // 否则"其余候选随上滑渐隐"会在第一遍 setColor 时被抹回不透明(真机看不到渐隐的根因)。
        val fadeAlpha = if (alpha < 1f) (alpha * 255).toInt() else savedAlpha
        wordPaint.alpha = fadeAlpha
        val base = 17f * resources.displayMetrics.scaledDensity

        // 分割线**先整排画完,再画槽**。2026-10-02 真机报「上滑动画时候选词左侧分割线会消失」:
        // 原先分割线画在槽 `i` 的左侧、而 `if (i == skip) continue` 在它**之前** ——
        // 上滑时飞起来的那个槽要空出来,连它左边的界一起空了。中槽(第 2 个)一飞,
        // 槽 1 与槽 2 之间那条就没了,看着像"左边那一列突然粘住了"。
        //
        // 线是**界**,不属于任何一个槽,所以它不该跟着 skip 走。翻页动画传
        // dividers=false 是另一回事:那是有意的(两页交叠时不该有界),与本条无关。
        if (dividers) {
            for (i in 1 until 5) {
                val x = i * cellWidth
                canvas.drawRect(x, dip(8), x + 1f, height - dip(8), dividerPaint)
            }
        }

        for (i in 0 until 5) {
            if (i == skip) continue
            val left = i * cellWidth
            val c = list.getOrNull(i) ?: continue
            if (i == highlightOf) {
                canvas.drawRect(left, 0f, left + cellWidth, height.toFloat(), pressedPaint)
            }
            // 中槽第一候选轻度强调:主色 + 略大一号。先定尺寸再测量,免得居中跑偏
            val first = i == CENTER_SLOT
            // 变化动画的入场变换(没在动的槽返回恒等,零开销)
            val (lift, scale) = swapTransform(i)
            wordPaint.textSize = base *
                (if (c.word.length > 6) 0.7f else 1f) *
                (if (first) FIRST_BOOST else 1f) *
                scale
            // 缩放只改 textSize,measureText 量出的就是缩放后的宽 —— 照常居中即可,
            // 不需要额外补偿(试过"按比例反推中心"的写法,结果是字朝一侧漂)
            val w = wordPaint.measureText(c.word)
            val x = left + (cellWidth - w) / 2f
            val y = height / 2f - (wordPaint.ascent() + wordPaint.descent()) / 2f - lift
            wordPaint.color = if (first) accentColor else savedColor
            // setColor 会覆盖 alpha:取"渐隐 × 入场"两者的较小值
            val a = (fadeAlpha * swapAlpha(i)).toInt().coerceIn(0, 255)
            wordPaint.alpha = a
            canvas.drawText(c.word, x, y, wordPaint)
            wordPaint.textSize = base
        }
        wordPaint.color = savedColor
        wordPaint.alpha = savedAlpha
    }

    private fun dip(v: Int): Float = v * density

    private companion object {
        /**
         * 日志 tag。翻页手势的成败要能被 E2E 断言,所以这个 tag **必须**
         * 出现在 `tools/aitest/harness.mjs` 的 `takeLog` 过滤里
         * (`logcat -s ImeService:* CandidateBar:*`)—— `-s` 会把不在列表里的
         * tag 全部丢掉,加日志而不改过滤 = 白加。
         */
        private const val TAG = "CandidateBar"

        /** 中槽 = 第一候选(空格键位)。 */
        const val CENTER_SLOT = 2

        /** 第一候选的强调幅度(尺寸)。 */
        const val FIRST_BOOST = 1.1f

        // 候选词变化入场动画的参数在 [SwapAnim] 里(纯函数,可单测):幅度刻意小,
        // 候选行只有 48dp 高,飞进飞出就越界了。
    }
}

/**
 * 翻页触发线 = **行宽的 0.22**(默认档「灵敏」):拖过它松手才真的翻页,否则反向动画收回原页。
 *
 * 死区**不在这里定**,是固定常量 [DragGesture.DEAD_DP](8dp),不随本比例变 ——
 * 2026-10-02 用户「死区不使用比例计算 采用固定值」,`DEAD_FRAC` 与 `dead(threshold)`
 * 已经删掉,`progress`/`slide` 的 `dead` 是必填参数。
 *
 * **可调**(2026-10-02 用户「左右滑动阈值需要可调节性」):本值只是出厂默认,
 * 设置页的档位经 [CandidateBarView.pageCommitFrac] 注入。改动这里的常量只影响新默认值,
 * 并且必须与 [ImeService] 的 `PAGE_THRESHOLD_TIERS` 默认档保持同一个数
 * (`PageThresholdTierTest.默认档与两处出厂常量一致` 盯这条)。
 */
internal const val PAGE_COMMIT_FRAC = 0.22f

/**
 * 跟手翻页的进度换算(纯函数,便于单测,见 `CandidateBarPageDragTest`)。
 * 走的是三处手势**共用**的 [DragGesture.progress],这里只负责"手指 x 坐标 → 沿翻页方向的位移":
 *
 * - `dir = +1`(下一页)时手指**向左**移(`x` 减小)才推进;`dir = -1` 反之
 *   ⇒ 反向滑动自动把进度拉回 0,松手就是"收回原页";
 * - 死区 = 阈值的 1/4(共用),越过死区后与手指 1:1,所以是**跟手**而不是抬手才跳。
 */
internal fun pageDragProgress(
    x: Float,
    baseX: Float,
    dir: Float,
    viewW: Float,
    commitPx: Float,
    dead: Float,
): Float {
    val travel = pageDragTravel(x, baseX, dir)
    return DragGesture.progress(travel, threshold = commitPx, dead = dead)
}

/**
 * 翻页的**跟手画面位移**(px):手指沿翻页方向走多少,画面就走多少,夹在 `[0, viewW]`。
 *
 * 这是绘制**唯一**该用的量(2026-09-30 真机报「翻页不跟手」「动画太短」修):
 * 跟手的定义是画面位移 = 手指位移。此前绘制用的是 `pageDragProgress × width`,
 * 而进度在**阈值**(行宽 1/3)就满了 ⇒ 手指走 1/3、画走满整行,放大 3 倍;
 * 前 8% 行宽还在死区里画面零变化;拖过阈值后画面干等,松手时 from == target 直接零动画。
 *
 * 死区内仍有响应([DragGesture.DEAD_SLIP]),消掉"手指在动、屏幕不动"那段静默。
 */
internal fun pageDragSlide(
    x: Float,
    baseX: Float,
    dir: Float,
    viewW: Float,
    commitPx: Float,
    dead: Float,
): Float = DragGesture.slide(
    travel = pageDragTravel(x, baseX, dir),
    threshold = commitPx,
    limit = viewW,
    dead = dead,
)

/**
 * 拖动中途手指反向时,要不要把这次翻页**镜像**过来(纯函数,便于单测)。
 *
 * ## 这里原来无条件镜像 ⇒ 一帧跳整整一页(2026-10-04 真机报「滑動還是過沖」)
 *
 * [CandidateBarView.beginPageDrag] 里的 `dir != pageDir` 分支会做
 * `pageSlide = full - pageSlide`。单看那一次是对的:手指改主意了,
 * 同一屏位置应该改成「朝另一页走」,镜像能保住画面连续。
 *
 * **问题在于它每个 MOVE 都会重新判定一次。** `dir` 是按 `event.x - downX`
 * 算的,而 MOVE 分支的门槛只有 `pageDeadPx`(4dp)—— 于是:
 *
 *     手指往左 5px → dir=+1 → pageSlide = 5
 *     手指回右 1px → dir=-1 → pageSlide = full - 5     ← 一帧跳了 1435px
 *     手指再往左  → dir=+1 → pageSlide = 5             ← 又跳回去
 *
 * `pageSlide` 在这种抖动下**永远很小**(手指还没走出几 px),所以每次镜像
 * 都是接近整页的跳变。屏幕上读起来就是「翻页反复回弹好几次」。
 *
 * 这与阻尼比**无关** —— 抖动发生在拖动阶段,弹簧还没起跑,
 * 所以 ζ 从 0.72 一路调到 1.0 都不可能改善它。
 *
 * ## 两个条件缺一不可
 *
 * 1. **已经走出提交距离**才镜像:位移只有几 px 时镜像的视觉代价最大而语义为零
 *    (这时用户改主意,正确做法是让 [DragGesture.slide] 把位移自然带回 0)。
 * 2. **一次手势只镜像一次**:镜像后 `pageSlide` 变大,再反向又会满足条件 1,
 *    于是又镜像回去 —— 必须用闩锁掐断。
 *
 * @param pageSlide 当前位移(px,恒 ≥ 0)
 * @param commitPx 提交距离(px)
 * @param mirrored 本次手势是否已经镜像过
 */
internal fun allowPageMirror(pageSlide: Float, commitPx: Float, mirrored: Boolean): Boolean =
    !mirrored && pageSlide > commitPx

/**
 * 翻页收尾的落点与物理参数(纯函数,便于单测)。
 *
 * ## 这里为什么**不能**用 [Landing.project]
 *
 * `Landing.project(from, target, v0)` 答的是「如果任它惯性滑行,会停在哪」——
 * 它是**投影**,不是**完成**。用在翻页收尾上,后果是整页行程(`pageSlide: 0 → width`)
 * 从来没走完:过提交线那一帧 `pageSlide` 才十几 px,惯性最多再推 `v²/(2a)` ≈ 166px,
 * 于是弹簧只走一百多 px 就被 `clearPageTurn()` 截断,画面是**跳**过去的。
 * (2026-10-04 修;用户报「松手时翻不过去」「翻过去/收回的动画不对」「翻頁困難」。)
 *
 * 所以分工是:**惯性只决定翻不翻,落点只由「走完 / 回原位」决定。**
 *
 * ## 两支弹簧而不是一支
 *
 * 提交是**确认**(翻到新页)⇒ 欠阻尼,要有到位感;
 * 收回是**放弃**(滑回原页)⇒ 过阻尼,不该来回晃。
 * 与 `SpringSpec.FLY_BACK`(飞字放弃)/`RUBBER_BACK`(撞头)同一类语义。
 */
internal fun pageSettleFor(committed: Boolean, full: Float): Pair<Float, SpringSpec> {
    val base = if (committed) SpringSpec.PAGE_COMMIT else SpringSpec.PAGE_ROLLBACK
    // 目标与提交方向绑在一起,不能一律返回 `full` ——
    // 收回的目标是 **0**。第一版这里写成了 `return full to …`,被单测当场抓到。
    val target = if (committed) full else 0f
    // 起点是 0(整段都还没走),所以 response 按整页算。
    return target to PageTiming.withResponse(base, full)
}

/**
 * 同上,但行程已知(松手那一刻 `pageSlide` 就是起点)。时长按**剩余行程**算,
 * 而不是按整页 —— 手指已经划了 585px 就只补剩下的 791px,不该再等一整页的时间。
 */
internal fun pageSettleFor(
    committed: Boolean,
    full: Float,
    from: Float,
    entrySpeed: Float = 0f,
): Pair<Float, SpringSpec> {
    val base = if (committed) SpringSpec.PAGE_COMMIT else SpringSpec.PAGE_ROLLBACK
    val target = if (committed) full else 0f
    return target to PageTiming.withResponse(base, target - from, entrySpeed)
}

/** 手指 x 坐标 → 沿翻页方向的位移(px,顺着翻页方向为正)。 */
internal fun pageDragTravel(x: Float, baseX: Float, dir: Float): Float =
    -(x - baseX) * dir // dir=+1 下一页时手指向左移才为正

/**
 * 外部注入的提交线**下限夹值**(纯函数,便于单测)。
 *
 * ## 口径:档位 = **死区之外**还要走多远(2026-10-02 用户定)
 *
 * [beginPageDrag] 把基线定在"死区被跨过的那一帧" —— 那 8dp 死在基线**之前**,
 * 已经走完了。所以设置页档位直接就是"从基线量的提交距离",**这里不做减死区换算**。
 *
 * 旧口径是"手指总行程",于是每个调用点都得记得减一次死区,而漏一处就偏一整段:
 * - [CandidateBarView.pageCommitPx] 减了;
 * - [dev.hwkbd.ime.service.ImeService.FlyDetector] 的抬手兜底忘了减,那个阈值与
 *   其余各处的量纲对不上;
 * - 三档的**台阶被死区压扁**:旧值 40/60/90 减掉死区后只剩 8/28/58,最灵敏那档
 *   距离死区只有 8px,再往下调就没东西可让了 —— 想加一档更灵敏的,加不出来。
 *
 * 改成"死区外"之后,档位就是**用户真正要调的那个数**,三档也是均匀的 8/28/58,
 * 而且所有比较点(候选栏、[FlyDetector] 的兜底)都直接拿它比,不用各自记得减。
 *
 * ## 这里夹下界,但**上层压根不该把 0 传进来**(2026-10-04 修正注释)
 *
 * 原注释写的是「档位 ≤ 0 时夹到 1px,让『刚跨过死区就翻』」——
 * 但实际行为不是那样:[pageCommitLine] 的条件是 `externalDrive && externalPx > 0f`,
 * 所以 `externalPx <= 0` 时**根本不会走到这个夹值**,而是退回 `行宽 × pageCommitFrac`。
 *
 * 查了一遍,**代码是对的、注释是错的**:
 * - `externalCommitPx` 由 [dev.hwkbd.ime.service.ImeService] 无条件注入,
 *   档位表最小值是 4(见 `PAGE_THRESHOLD_TIERS`),所以 0 只可能是「没注入成功」;
 * - 两种退化方式里,退回行宽比例(仍能翻页)比夹到 1px(极端灵敏)安全得多。
 *
 * 所以保留代码,改正注释。**教训:注释描述意图,代码描述行为,
 * 两者冲突时要先判断哪个对,而不是改代码去迁就注释。**
 *
 * @param px 设置页档位的值,已经是从基线量的提交距离
 * @return 夹过下界的提交距离(px)
 */
internal fun externalCommitFromBase(px: Float): Float = px.coerceAtLeast(1f)

/**
 * 本次翻页拖动的提交线(px,从基线量起)—— [CandidateBarView.pageCommitPx] 的纯函数版。
 *
 * ## 两条量纲,取决于**发起方**
 *
 * - 键盘表面划([externalPx] > 0):档位本身**就是死区之外的提交距离**,
 *   基线定在「死区被跨过的那一帧」,死区已走完 ⇒ 直接用,只夹下界。
 * - 候选行自己划:行宽 × [commitFrac]。
 *
 * ## 为什么必须是纯函数、而且要有单测(2026-10-04)
 *
 * 这个判断的输入里有一个**状态**(`externalDrive`),而它会被 [CandidateBarView.pageDragEnd]
 * 清掉。曾经因为「先清标志再分发」,导致**松手时读到了另一条量纲**:
 * 拖动中 thr=8(对),松手时 thr=302(错,行宽 × 0.22)。
 * 后果是「划过去又翻回来」的振铃 + 快甩失效。
 *
 * 抽成纯函数 + 单测钉住两种发起方各自的量纲,是为了让**这类错无法再溜过去**:
 * 状态时序错了,纯函数不会错。
 */
internal fun pageCommitLine(
    externalDrive: Boolean,
    externalPx: Float,
    commitFrac: Float,
    viewW: Float,
): Float = if (externalDrive && externalPx > 0f) externalCommitFromBase(externalPx) else viewW * commitFrac

