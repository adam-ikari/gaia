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
    private val arrowWidth = 26f * density
    private val touchSlop = 12f * density

    /**
     * 下滑选字的**抬手**判据(仍按 24dp):短促的"快速一划直接选中",不是跟手拖动,用绝对距离合适。
     * 与翻页的 [pageDeadPx]/[pageCommitPx] 是两回事,别混。
     */
    private val flingDistance = 24f * density

    /**
     * **翻页手势阈值按行宽比例取(不是绝对 dp)**——2026-09-29 用户定案「翻页阈值也需要调整」:
     * 此前是写死的 24dp 抬手判据,在 598dp 宽的候选行上只占 4%,随手一碰就翻页,而且完全不跟手。
     * 改成行宽比例后跨机型一致。死区 / 跟手 / 提交线与 L5 翻页、上滑飞字**共用**
     * [DragGesture] 那份数学(2026-09-29「手感对齐」),这里只提供阈值:
     * 触发线 = 行宽的 [PAGE_COMMIT_FRAC],死区 = 触发线的 1/4。
     */
    private val pageThresholdPx: Float get() = width * PAGE_COMMIT_FRAC

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
    private var downCell = -1

    // ---- 翻页:横向**跟手**拖动(与上滑飞字同一套模型:死区 → 跟手 → 松手过阈值才提交)
    //
    // [pageU] = 本次过渡的进度:0 = 整行停在 [pageFrom],1 = 完全换成 [pageTo]。
    // 稳态(没有过渡在跑)时 [pageFrom] 为空,直接画 [slots]。
    // 一次手势内 [pageDir] 固定(定方向那一下就决定),手指往回拖只是让 pageU 变小(在收回);
    // 真要翻去反方向,靠**下一次**手势接管正在播的动画来表达([beginPageDrag] 的角色对调)。
    // [pageCommitted]:目标页是不是已经交给 controller 了——接管后又往回拖并松手时要用它撤销提交。
    private var pageU = 0f
    private var pageDir = 1f                 // +1 下一页:旧页左出/新页右入;-1 相反
    private var pageFrom: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var pageTo: List<dev.hwkbd.ime.engine.Candidate?> = emptyList()
    private var pageCommitted = false
    private var pageAnim: ValueAnimator? = null
    private var lastPage = 0

    /** 翻页拖动的基线 x:手指从这里起算 [pageU];接管动画时按当前 pageU 重定基线,接着走不跳。 */
    private var pageDragBaseX = 0f
    private var pageDragging = false

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
        if (state.page != lastPage) onPageIndexChanged(state.page, newSlots)
        lastPage = state.page
        slots = newSlots
        highlight = state.highlight
        page = state.page
        totalPages = state.totalPages
        invalidate()
    }

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
        if (pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageFrom = slots
            pageTo = newSlots
            pageDir = dir
            pageU = 0f
            if (pageFrom.isNotEmpty() && pageTo.isNotEmpty()) {
                settlePage(1f)
            } else {
                clearPageTurn()
            }
            return
        }
        if (newSlots == pageFrom && pageDir == -dir) {
            // 往回翻:旧页↔新页对调、进度镜像 → 同一画面,换个方向继续
            val f = pageFrom
            pageFrom = pageTo
            pageTo = f
            pageDir = -pageDir
            pageU = 1f - pageU
            settlePage(1f)
        } else {
            // 目标对不上:整页重切
            pageFrom = slots
            pageTo = newSlots
            pageDir = dir
            pageU = 0f
            if (pageFrom.isNotEmpty() && pageTo.isNotEmpty()) {
                settlePage(1f)
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
    private fun beginPageDrag(dir: Float, x: Float) {
        if (pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageDir = dir
            pageFrom = slots
            pageTo = if (dir > 0f) pageNextSlots else pagePrevSlots
            pageU = 0f
        } else if (dir != pageDir) {
            val f = pageFrom
            pageFrom = pageTo
            pageTo = f
            pageDir = dir
            pageU = 1f - pageU
        }
        pageAnim?.cancel()
        pageAnim = null
        pageDragging = pageFrom.isNotEmpty() && pageTo.isNotEmpty()
        // 旧页当前停在 -pageU × 行宽(方向由 pageDir 带),把基线对齐到这个位移,手指接着走
        pageDragBaseX = x + pageU * width * pageDir
    }

    /**
     * 跟手:[pageU] = 手指横向位移扣掉死区后占行宽的比例(位移 1:1 变成进度 1:1,到行宽封 1)。
     * 手指往回拖 [pageU] 就变小——那就是"在收回",松手不过线会反向动画退回原页。
     */
    private fun setPageDrag(x: Float) {
        if (!pageDragging) return
        val u = pageDragProgress(x, pageDragBaseX, pageDir, width.toFloat())
        if (u != pageU) {
            pageU = u
            invalidate()
        }
    }

    /**
     * 松手:过提交线才真的翻页(**先把目标页交给 controller**,再补完剩下的位移);
     * 没过线就把这次过渡反向动画收回原页——若上一次松手已经提交过(接管后往回拖的情况),
     * 还要把那次提交也撤回来,否则 controller 的页号和画面会脱节。
     */
    private fun endPageDrag(x: Float) {
        if (!pageDragging) return
        setPageDrag(x) // 抬手前最后跟一次手指
        pageDragging = false
        val step = if (pageDir > 0f) 1 else -1
        // 提交线恒为 1:进度走满 1 才提交(共用 DragGesture 的死区/跟手/阈值语义)
        if (pageU >= 1f) {
            if (!pageCommitted) onPage?.invoke(step)
            pageCommitted = true
            settlePage(1f)
        } else {
            if (pageCommitted) onPage?.invoke(-step) // 撤销上一次提交
            pageCommitted = false
            settlePage(0f)
        }
    }

    /**
     * 把剩下的位移补完/收回:target 1 = 走到新页,0 = 回旧页。**时长按剩余距离缩放**,
     * 所以接管动画时改 target 就能自然反向。播完即清过渡(稳态直接画 [slots])。
     */
    private fun settlePage(target: Float) {
        pageAnim?.cancel()
        pageAnim = null
        val from = pageU
        if (from == target || pageFrom.isEmpty() || pageTo.isEmpty()) {
            pageU = target
            clearPageTurn()
            invalidate()
            return
        }
        val dur = DragGesture.settleDuration(kotlin.math.abs(target - from))
        pageAnim = ValueAnimator.ofFloat(from, target).apply {
            duration = dur
            addUpdateListener { a ->
                pageU = a.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    pageAnim = null
                    pageU = target
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
        pageU = 0f
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
        invalidate()
    }

    /** 飞字拖动进度(0..1):词随手指上移,其余候选按比例渐隐。 */
    fun setFlyDrag(progress: Float) {
        if (flyP < 0f || flyAnim != null) return
        flyP = progress.coerceIn(0f, 1f)
        invalidate()
    }

    /** 拖动被放弃(松手未过阈值/转为横滑/取消):整组淡回原位。 */
    fun cancelFlyDrag() {
        if (flyP < 0f || flyAnim != null) return
        flyAnim = ValueAnimator.ofFloat(flyP, 0f).apply {
            duration = 110
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
        flyAnim = ValueAnimator.ofFloat(from, 2f).apply {
            duration = ((2f - from) * 110).toLong().coerceAtLeast(60)
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
                longPressFired = false
                postDelayed(longPressRunnable, 600L)
                performClick()
            }
            MotionEvent.ACTION_MOVE -> {
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
                if (swipePageEnabled && adx > DragGesture.dead(pageThresholdPx) && adx > ady) {
                    if (flyP >= 0f && flyAnim == null) cancelFlyDrag()
                    val dir = if (dx < 0f) 1f else -1f
                    val neighbour = if (dir > 0f) pageNextSlots else pagePrevSlots
                    // 那一侧没有页(首/末页往回拖)就不接管,留着给点按/上滑
                    if (neighbour.isNotEmpty() && neighbour.any { it != null }) {
                        beginPageDrag(dir, event.x)
                        takeOverPage = true
                    }
                }
                when {
                    // 横滑翻页同样改成"拖动即反馈":整页跟手指走,松手过提交线才真的翻页
                    // (与上滑同一套模型:死区 → 跟手 → 松手判定 → 没过线反向动画收回)
                    takeOverPage || pageDragging -> setPageDrag(event.x)
                    swipeSelectEnabled && downCell >= 0 && dy > flyDeadPx && dy > adx -> {
                        beginFlyDrag(downCell) // 自带防重入
                        setFlyDrag((dy - flyDeadPx) / (flyThresholdPx - flyDeadPx))
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
                            setFlyDrag((dy - flyDeadPx) / (flyThresholdPx - flyDeadPx))
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
                    swipeSelectEnabled && dy < -flingDistance && -dy > kotlin.math.abs(dx) -> {
                        val i = cellIndexAt(downX)
                        if (i >= 0) onSelect?.invoke(i)
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                removeCallbacks(longPressRunnable)
                cancelFlyDrag()
                // 翻页拖动被系统打断:当作松手,走同一套判定(过线提交/没过线收回)
                if (pageDragging) endPageDrag(downX)
            }
        }
        return true
    }

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
        canvas.drawText("◀", arrowWidth / 2f - arrowPaint.measureText("◀") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = if (page < totalPages - 1) 0xFF else 0x40
        canvas.drawText("▶", width - arrowWidth / 2f - arrowPaint.measureText("▶") / 2f, height / 2f + dip(5), arrowPaint)
        arrowPaint.alpha = dim

        // 翻页:旧页随手指/动画滑出,新页从另一侧滑入(等宽等距 ⇒ 中线恒过中心键心/中缝)
        val showPageTurn = flyP < 0f && pageFrom.isNotEmpty() && pageTo.isNotEmpty() && pageU > 0f
        val wordAlpha = wordPaint.alpha
        if (flyP >= 0f) {
            drawFly(canvas) // 飞字拖动/完成段:词起升,其余候选渐隐
        } else if (showPageTurn) {
            val u = pageU
            val slide = pageDir * u * width // 旧页的位移:顺方向拖多远就移多远(跟手)
            // 旧页:向翻页方向滑出
            wordPaint.alpha = ((1f - u) * 255).toInt()
            canvas.save()
            canvas.translate(-slide, 0f)
            drawSlots(canvas, pageFrom, highlightOf = -1, dividers = false)
            canvas.restore()
            // 新页:从翻页反方向滑入
            wordPaint.alpha = (u * 255).toInt()
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
        val others = if (s <= 0f) 1f - 0.6f * drag else 0.4f * (1f - s * s)
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
            wordPaint.textSize = base *
                (if (c.word.length > 6) 0.7f else 1f) *
                (if (first) FIRST_BOOST else 1f)
            val w = wordPaint.measureText(c.word)
            val x = left + (cellWidth - w) / 2f
            val y = height / 2f - (wordPaint.ascent() + wordPaint.descent()) / 2f
            wordPaint.color = if (first) accentColor else savedColor
            wordPaint.alpha = fadeAlpha // setColor 会覆盖 alpha,补回渐隐值
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

    }
}

/**
 * 翻页触发线 = **行宽的 1/3**:拖过它松手才真的翻页,否则反向动画收回原页。
 * 死区不在这里定——三处手势统一取阈值的 1/4,见 [DragGesture.DEAD_FRAC]。
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
): Float {
    val travel = -(x - baseX) * dir // 顺着翻页方向为正
    return DragGesture.progress(travel, threshold = viewW * PAGE_COMMIT_FRAC)
}

