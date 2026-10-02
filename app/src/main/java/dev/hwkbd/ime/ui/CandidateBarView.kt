package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.animation.ValueAnimator
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
    private val flyDeadPx: Float get() = DragGesture.dead(flyThresholdPx)

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

    private val pageThresholdPx: Float get() = width * pageCommitFrac

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
    private var pageAnim: ValueAnimator? = null
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
    private var flyAnim: ValueAnimator? = null
    private var flyP = -1f
    private var flyZone = -1
    private var flyX = 0f
    private var flyWord = ""
    private var flyOutSlots: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()

    /** true = 正在播完成段(已上屏),不接受新的拖动打断;false = 拖动中/淡回中。 */
    private var flyCompleting = false

    /** 飞字进行中(拖动或完成动画):两态 UI 需保持候选行可见。 */
    fun isFlyAnimating(): Boolean = flyP >= 0f

    fun setState(state: ImeState) {
        val newSlots = state.pageSlots()
        // 跟手翻页要预览相邻页,先备好(越界为空表 = 那一侧没有页,拖过去也不动)
        pageNextSlots = state.pageSlotsAt(state.page + 1)
        pagePrevSlots = state.pageSlotsAt(state.page - 1)
        val pageTurned = state.page != lastPage
        if (pageTurned) onPageIndexChanged(state.page, newSlots)
        lastPage = state.page
        // 变化动画要在 slots 换掉**之前**算:要对比新旧每一槽是不是同一个词
        if (!pageTurned) startSwapAnim(slots, newSlots)
        slots = newSlots
        highlight = state.highlight
        page = state.page
        totalPages = state.totalPages
        invalidate()
    }

    // ---------------------------------------------------------------- 候选词变化动画

    /** 变化中的槽:index → 该槽的入场进度(0 = 还没进,1 = 到位)。空表 = 没有动画在跑。 */
    private val swapSlots = HashMap<Int, Float>()
    private var swapAnim: ValueAnimator? = null

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
        // 正在翻页/飞字就别叠动画了
        if (pageFrom.isNotEmpty() || pageAnim != null || flyP >= 0f || flyAnim != null) return

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
        swapAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = total
            interpolator = Fling.DECELERATE
            addUpdateListener { a ->
                val global = a.animatedValue as Float
                for (i in changed) swapSlots[i] = SwapAnim.localProgress(global, i, total, CENTER_SLOT)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    swapAnim = null
                    swapSlots.clear()
                    invalidate()
                }
            })
            start()
        }
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
        pageCommitted = true
        val full = width.toFloat()
        if (pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageFrom = slots
            pageTo = newSlots
            pageDir = dir
            pageSlide = 0f
            if (pageFrom.isNotEmpty() && pageTo.isNotEmpty()) {
                settlePage(full)
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
            settlePage(full)
        } else {
            // 目标对不上:整页重切
            pageFrom = slots
            pageTo = newSlots
            pageDir = dir
            pageSlide = 0f
            if (pageFrom.isNotEmpty() && pageTo.isNotEmpty()) {
                settlePage(full)
            } else {
                clearPageTurn()
            }
        }
    }

    /**
     * 翻页拖动开始:冻结当前页当过渡的旧页,按方向取相邻页当新页。**可重入**——
     * 正在播动画(上次松手后的正/反向)时接管:同向接着走,反向把两个角色对调、进度取镜像,
     * 手指基线按当前 [pageU] 重定 ⇒ 接管那一帧画面不跳,能直接接着拖。
     */
    private fun beginPageDrag(dir: Float, x: Float, eventTimeMs: Long = 0L) {
        val full = width.toFloat()
        pageSpeed.reset()
        if (eventTimeMs > 0L) pageSpeed.add(eventTimeMs, x)
        if (pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageDir = dir
            pageFrom = slots
            pageTo = if (dir > 0f) pageNextSlots else pagePrevSlots
            pageSlide = 0f
        } else if (dir != pageDir) {
            val f = pageFrom
            pageFrom = pageTo
            pageTo = f
            pageDir = dir
            pageSlide = full - pageSlide
        }
        pageAnim?.cancel()
        pageAnim = null
        pageDragging = pageFrom.isNotEmpty() && pageTo.isNotEmpty()
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
        val slide = pageDragSlide(x, pageDragBaseX, pageDir, w, pageCommitFrac)
        pageDragTravel = travel
        if (slide != pageSlide) {
            pageSlide = slide
            invalidate()
        }
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
        val step = if (pageDir > 0f) 1 else -1
        val full = width.toFloat()
        val threshold = full * pageCommitFrac
        // 速度取沿翻页方向的符号:往下一页(dir=+1)时手指向左,速度也要是"朝新页"才算甩
        val v = pageSpeed.velocity() * pageDir
        val landing = Fling.projectLanding(pageSlide, full, v)
        // 提交线:手指位移过线 **或** 惯性落点在过线一侧(甩过去)
        val byFinger = DragGesture.progress(pageDragTravel, threshold) >= 1f
        val byFling = v > 0f && landing >= threshold
        if (byFinger || byFling) {
            if (!pageCommitted) {
                onPage?.invoke(step)
                haptics?.confirm() // 翻页提交的一次轻震:跟飞字过阈值同一套反馈语义
            }
            pageCommitted = true
            settlePage(landing.coerceAtLeast(pageSlide), v)
        } else {
            if (pageCommitted) onPage?.invoke(-step) // 撤销上一次提交
            pageCommitted = false
            settlePage(Fling.projectLanding(pageSlide, 0f, v).coerceAtMost(pageSlide), v)
        }
    }

    /**
     * 把剩下的位移补完/收回:**target 是像素**——`width` = 走到新页,0 = 回旧页。
     * 播完即清过渡(稳态直接画 [slots])。
     *
     * **惯性时长**:松手速度决定滑多久([Fling.stopDurationMs]),所以轻轻推一下补完很快、
     * 猛地一甩补很久,和真实滑动一致。此前是"剩余进度 × 固定基准时长",速度与手指快慢无关,
     * 看着像被机器推走。曲线用 [Fling.DECELERATE](= 匀减速 `2p − p²`,与 [Fling] 的物理模型等价)。
     */
    private fun settlePage(target: Float, v0: Float = 0f) {
        pageAnim?.cancel()
        pageAnim = null
        val from = pageSlide
        if (from == target || pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageSlide = target
            clearPageTurn()
            invalidate()
            return
        }
        pageAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = Fling.stopDurationMs(if (v0 != 0f) v0 else DragGesture.fallbackVelocity(from, target))
            interpolator = Fling.DECELERATE
            addUpdateListener { a ->
                pageSlide = a.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pageAnim = null
                    pageSlide = target
                    clearPageTurn()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun clearPageTurn() {
        pageFrom = emptyList()
        pageTo = emptyList()
        pageSlide = 0f
        pageDragTravel = 0f
    }

    /** 飞字拖动开始:冻结当前候选,后续 [setFlyDrag] 按手指进度实时驱动。可重入(拖回又拖上来时接管淡回)。 */
    fun beginFlyDrag(zone: Int) {
        if (flyCompleting) return
        if (flyP >= 0f && flyZone == zone && flyAnim == null) return
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
        if (flyP < 0f || flyAnim != null) return
        val p = progress.coerceIn(0f, 1f)
        val crossed = flyP < 1f && p >= 1f // 本次拖动里第一次越过阈值
        // 速度在 ACTION_MOVE 里用**原始 y** 统一采样(见那里的注释),这里不再重复喂。
        flyP = p
        if (crossed) haptics?.confirm() // 过阈值 = "这次上屏定了"
        invalidate()
    }

    /** 拖动被放弃(松手未过阈值/转为横滑/取消):整组淡回原位。 */
    fun cancelFlyDrag() {
        if (flyP < 0f || flyAnim != null) return
        flyAnim = ValueAnimator.ofFloat(flyP, 0f).apply {
            // 放弃时的淡回:按惯性退回(匀减速),曲线与飞出去一致,手感统一
            duration = Fling.stopDurationMs(flyP * 1000f)
            interpolator = Fling.DECELERATE
            addUpdateListener { a ->
                flyP = a.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    flyAnim = null
                    resetFly()
                }
            })
            start()
        }
    }

    private fun cancelFlyDragNow() {
        flyAnim?.cancel()
        flyAnim = null
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
        flyAnim?.cancel()
        flyZone = zone
        flyX = x
        flyWord = w
        flyOutSlots = out
        flyP = from
        flyCompleting = true
        // 惯性时长:松手速度决定飞多久(2026-09-30 用户「计算惯性质量和加速度」)。
        // 速度不足时按"匀速飞完剩余段"兜底,免得轻轻一推却飞很久。
        val remain = 2f - from
        val v = -flySpeed.velocity() // 上滑为正,存的是 -p ⇒ 取负号还原向上的速度
        flyAnim = ValueAnimator.ofFloat(from, 2f).apply {
            duration = if (remain <= 0f) Fling.MIN_MS
            else Fling.stopDurationMs(if (kotlin.math.abs(v) >= Fling.MIN_VELOCITY) v else remain * 1000f)
            interpolator = Fling.DECELERATE
            addUpdateListener { a ->
                flyP = a.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    flyAnim = null
                    resetFly()
                    // **必须报完成**(见 onFlyFinished):service 靠 isFlyAnimating 判两态,
                    // 不报就没人重新渲染,候选行会卡在可见态
                    onFlyFinished?.invoke()
                }
            })
            start()
        }
    }

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
                if (swipeArmed && swipePageEnabled && adx > DragGesture.dead(pageThresholdPx) && adx > ady) {
                    if (flyP >= 0f && flyAnim == null) cancelFlyDrag()
                    val dir = if (dx < 0f) 1f else -1f
                    val neighbour = if (dir > 0f) pageNextSlots else pagePrevSlots
                    // 那一侧有页 → 跟手翻页;没有页 → **橡皮筋拉伸**(到头也有反馈,
                    // 此前是整条拖动都不接管,手指在动屏幕纹丝不动,像卡了)
                    if (neighbour.isNotEmpty() && neighbour.any { it != null }) {
                        beginPageDrag(dir, event.x, event.eventTime)
                        takeOverPage = true
                    } else if (beginRubber(dir, event.x)) {
                        takeOverPage = true
                    }
                }
                when {
                    // 越界橡皮筋优先:到头的那一侧要给出拉伸反馈,不能跟翻页抢
                    rubberDrag.active -> setRubber(event.x, event.eventTime)
                    // 横滑翻页同样改成"拖动即反馈":整页跟手指走,松手过提交线才真的翻页
                    // (与上滑同一套模型:死区 → 跟手 → 松手判定 → 没过线反向动画收回)
                    takeOverPage || pageDragging -> setPageDrag(event.x, event.eventTime)
                    swipeArmed && swipeSelectEnabled && downCell >= 0 && dy > flyDeadPx && dy > adx -> {
                        beginFlyDrag(downCell) // 自带防重入
                        setFlyDrag((dy - flyDeadPx) / (flyThresholdPx - flyDeadPx), event.eventTime)
                    }
                    flyP >= 0f && flyAnim == null -> cancelFlyDrag()
                }
            }
            MotionEvent.ACTION_UP -> {
                removeCallbacks(longPressRunnable)
                // 拖动过阈值 → 在此提交(松手即飞完);没过 → 淡回原位;正在淡回则忽略本次抬手
                if (flyP >= 0f) {
                    if (flyAnim == null) {
                        val dx = event.x - downX
                        val dy = downY - event.y
                        if (dy > 0f && dy > kotlin.math.abs(dx)) {
                            setFlyDrag((dy - flyDeadPx) / (flyThresholdPx - flyDeadPx), event.eventTime)
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
    private var rubberAnim: ValueAnimator? = null
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
        if (!atEnd) return false
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
        if (!rubberDrag.tracking) return
        rubberDrag.stop() // 只停"手指不再驱动",方向与位移留给弹回动画用
        rubberAnim?.cancel()
        val from = rubberDrag.raw
        if (from <= 0f) {
            rubberDrag.reset()
            invalidate()
            return
        }
        haptics?.confirm() // 到头了:给一下震说"没下一页了"
        rubberAnim = ValueAnimator.ofFloat(from, 0f).apply {
            // 回弹时长按**松手速度**给(与翻页/飞字同一套惯性):拉得快弹得快。
            // 速度样本不足时按"拉伸量 ∝ 12"折算,保证拉得越远弹得越慢。
            val v = rubberSpeed.velocity()
            duration = Fling.stopDurationMs(if (kotlin.math.abs(v) >= Fling.MIN_VELOCITY) v else from * 12f)
            interpolator = Fling.DECELERATE
            addUpdateListener { a ->
                rubberDrag.animateTo(a.animatedValue as Float)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    rubberAnim = null
                    rubberDrag.reset()
                    invalidate()
                }
            })
            start()
        }
    }

    private fun cancelRubber() {
        rubberDrag.reset()
        rubberAnim?.cancel()
        rubberAnim = null
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
        val wordAlpha = wordPaint.alpha
        if (flyP >= 0f) {
            drawFly(canvas) // 飞字拖动/完成段:词起升,其余候选渐隐
        } else if (rubberDrag.raw > 0f) {
            // 越界拉伸:整行横向压缩 + 沿越界方向拉出,键帽跟着变形(2026-09-30 用户要"拉伸变形动画")
            drawRubber(canvas)
        } else if (showPageTurn) {
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
            wordPaint.alpha = wordAlpha
        } else {
            drawSlots(canvas, slots, highlight, dividers = true)
        }
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
        for (i in 0 until 5) {
            if (i == skip) continue
            val left = i * cellWidth
            if (i > 0 && dividers) {
                canvas.drawRect(left, dip(8), left + 1, height - dip(8), dividerPaint)
            }
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
        /** 中槽 = 第一候选(空格键位)。 */
        const val CENTER_SLOT = 2

        /** 第一候选的强调幅度(尺寸)。 */
        const val FIRST_BOOST = 1.1f

        // 候选词变化入场动画的参数在 [SwapAnim] 里(纯函数,可单测):幅度刻意小,
        // 候选行只有 48dp 高,飞进飞出就越界了。
    }
}

/**
 * 翻页触发线 = **行宽的 1/3**(默认档):拖过它松手才真的翻页,否则反向动画收回原页。
 * 死区不在这里定——三处手势统一取阈值的 1/4,见 [DragGesture.DEAD_FRAC]。
 *
 * **可调**(2026-10-02 用户「左右滑动阈值需要可调节性」):本值只是出厂默认,
 * 设置页的档位经 [CandidateBarView.pageCommitFrac] 注入。改动这里的常量只影响新默认值。
 */
internal const val PAGE_COMMIT_FRAC = 0.33f

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
    commitFrac: Float = PAGE_COMMIT_FRAC,
): Float {
    val travel = pageDragTravel(x, baseX, dir)
    return DragGesture.progress(travel, threshold = viewW * commitFrac)
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
    commitFrac: Float = PAGE_COMMIT_FRAC,
): Float = DragGesture.slide(
    travel = pageDragTravel(x, baseX, dir),
    threshold = viewW * commitFrac,
    limit = viewW,
)

/** 手指 x 坐标 → 沿翻页方向的位移(px,顺着翻页方向为正)。 */
internal fun pageDragTravel(x: Float, baseX: Float, dir: Float): Float =
    -(x - baseX) * dir // dir=+1 下一页时手指向左移才为正

