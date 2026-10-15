package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import dev.hwkbd.ime.ime.L6Key
import dev.hwkbd.ime.ime.L6Ui
import dev.hwkbd.ime.keymap.KeyOut
import kotlin.math.abs

/**
 * L6 虚拟键盘面板(2026-10-15 用户「空闲状态 sym+space 打开虚拟键盘,这是 L6 键盘,
 * 支持多个 tab 页和上下滑动翻页」)。
 *
 * ## 结构:tab 条 + 键格
 * 竖排两块:顶上一行 tab([L6Tab.label],点一下切 tab),下面是当前页的键格([L6Page.keys]
 * 每 [COLUMNS] 颗一行,不裁字、键帽按 L4 的样式画)。点键 → 交还 [onKey],走与 L4/L5
 * 面板**同一条**上屏通路([dev.hwkbd.ime.ime.ImeController.commitL4Key])——
 * 虚拟键盘的键与状态行里的键在「干什么」上没区别,只是长在不同面板上。
 *
 * ## 上下滑 = tab 内翻页(2026-10-15 定案「不跨 tab」)
 * 定方向那一下判**垂直**(死区 [DragGesture.DEAD_DP_SWIPE] 固定、不随阈值变,
 * 与候选行 / L5 横滑同一套 [DragGesture] 常量)。到头不循环 —— 越界交还
 * [L6Ui.stepPage] 判定,它返回 false 说明没翻,本视图原样退回。
 *
 * ## 只管画,不管状态
 * 「当前在哪个 tab / 哪一页」是 [L6Ui] 的状态机字段,由控制器改;本视图在 [setContent]
 * 里**照单全收**地重建,不做自己的记性。面板的显隐也归 service([ImeService.renderViews]),
 * 不归本视图 —— 与项目「状态机纯逻辑、绘制靠真机」的分工一致。
 */
class L6PanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** 点面板里的一颗键(文本 / 功能键原样交出,上屏由控制器走 [commitL4Key])。 */
    var onKey: ((KeyOut) -> Unit)? = null

    /** 点 tab 条上的一个 tab(下标)。切 tab 由控制器 [l6SelectTab] 归零页码。 */
    var onTab: ((Int) -> Unit)? = null

    /**
     * 上下滑翻页。`delta = +1` 下一页 / `-1` 上一页;**到头不循环** ⇒ 返回 `false`
     * (控制器 [dev.hwkbd.ime.ime.ImeController.l6StepPage] 的判定),本视图据此把画面退回原页而不是瞬移。
     */
    var onSwipePage: ((Int) -> Boolean)? = null

    private val tabStrip = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val grid = LinearLayout(context).apply { orientation = VERTICAL }

    private var fg = 0xFFEDF0F5.toInt()
    private var keyBg = 0xFF2C313A.toInt()
    private var stroke = 0xFF2A2E36.toInt()
    private var accent = 0xFFC44D3D.toInt()
    private var panelBg = 0xFF000000.toInt()

    /** 重建签名:tab/页/主题都没变就不重建(每帧渲染都进来,免得无谓地重建键格)。 */
    private var lastSig = ""

    init {
        orientation = VERTICAL
        addView(tabStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /**
     * 照状态机当前态重建 tab 条 + 键格。键格与 L4/L5 同一套色:键帽 [ImeTheme.pressed]、
     * 描边 [ImeTheme.divider]、字 [ImeTheme.fg];面板底取 [ImeTheme.barBg](整条带子同色)。
     */
    fun setContent(ui: L6Ui, theme: ImeTheme) {
        fg = theme.fg
        keyBg = theme.pressed
        stroke = theme.divider
        accent = theme.accent
        panelBg = theme.barBg
        val sig = "${theme.id}/${ui.tabIndex}/${ui.pageIndex}"
        if (sig == lastSig) return
        lastSig = sig
        // 翻页/重建把跟手位移清掉:旧页的位移不该带到新页的键上。
        swipeSlide = 0f
        swipeSpring.cancel()
        grid.translationY = 0f
        setBackgroundColor(panelBg)
        tabStrip.setBackgroundColor(panelBg)
        rebuildTabs(ui)
        rebuildGrid(ui)
    }

    private fun rebuildTabs(ui: L6Ui) {
        tabStrip.removeAllViews()
        for ((i, tab) in ui.tabs.withIndex()) {
            val active = i == ui.tabIndex
            val tv = TextView(context).apply {
                text = tab.label
                gravity = Gravity.CENTER
                maxLines = 1
                setPadding(dip(16), dip(6), dip(16), dip(6))
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(if (active) panelBg else fg)
                background = if (active) makeTabBg(accent) else null
                setOnClickListener {
                    performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onTab?.invoke(i)
                }
            }
            // 权重 1 ⇒ 所有 tab 等宽铺满,与状态行三区「等宽槽」同一版式直觉。
            tabStrip.addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        }
    }

    private fun rebuildGrid(ui: L6Ui) {
        grid.removeAllViews()
        val keys = ui.pageKeys()
        val rows = (keys.size + COLUMNS - 1) / COLUMNS
        var idx = 0
        val density = resources.displayMetrics.density
        repeat(rows) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            repeat(COLUMNS) {
                if (idx < keys.size) {
                    val k = keys[idx++]
                    row.addView(makeKey(k), LinearLayout.LayoutParams(0, dip(36), 1f).apply {
                        marginEnd = (3 * density).toInt()
                        bottomMargin = (3 * density).toInt()
                    })
                } else {
                    // 不满一行时补**等宽空槽**:不然剩下的键会被权重撑满整行,读成一条横贯的怪键。
                    row.addView(View(context), LinearLayout.LayoutParams(0, dip(36), 1f))
                }
            }
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }

    private fun makeKey(k: L6Key): View = TextView(context).apply {
        text = k.label
        maxLines = 1
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(fg)
        background = makeKeyBg()
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            onKey?.invoke(k.out)
        }
    }

    // ------------------------------------------------------------- 上下滑翻页
    //
    // 与候选行 / L5 横滑同一模型:**画面位移 = 手指位移**(slide),进度只用来判提交;
    // 死区固定、不随阈值变。只是轴向换成了 Y。

    /**
     * 本次手势的翻页许可(在 DOWN 时向 [swipeGate] 问一次)。默认 true = 没接门就是全放行。
     * 与 L4/L5 同一套:静默窗(用户 2026-10-02「所有的键盘滑动都无效」)只挡滑动,**点键不看它**。
     */
    var swipeGate: (() -> Boolean)? = null

    private var swipeArmed = true
    private var swipeDownX = 0f
    private var swipeDownY = 0f
    private var swipeTracking = false
    private var swipeDir = 1f // 上滑(dy<0)= +1(下一页),下滑 = -1
    private var swipeSlide = 0f
    private var swipeTravel = 0f

    private val density get() = resources.displayMetrics.density
    private val swipeDeadPx: Float get() = DragGesture.DEAD_DP_SWIPE * density
    private fun swipeThresholdPx(): Float = grid.height * COMMIT_FRAC
    private fun swipeLimitPx(): Float = grid.height.toFloat()

    private val swipeSpring = SpringChannel(
        from = 0f,
        spec = SpringSpec.DEFAULT,
        onFrame = { value, _ ->
            swipeSlide = value
            grid.translationY = -swipeDir * value
        },
        onFinish = { cancelled ->
            if (cancelled) return@SpringChannel
            swipeSlide = 0f
            swipeTravel = 0f
            grid.translationY = 0f
        },
    )

    /**
     * 上下滑起手落在**键帽上**时也要能翻页。键帽是带点击监听的 TextView,会自己吃掉触摸,
     * 父视图的 [onTouchEvent] 根本收不到 MOVE —— 所以用拦截:竖移过死区那一下把后续事件
     * 抢过来(与 L5 行横滑同一套,见 [L4PanelView.onInterceptTouchEvent])。
     *
     * **不抢 DOWN**:先让键帽正常处理 —— 点键(上屏)完全不受影响;竖移没过死区、
     * 或横移为主(误起手),都原样还给子 View。
     */
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeTracking = false
                // 滑动许可只在这里问一次(见 [swipeGate]);键帽点按不看它
                swipeArmed = swipeGate?.invoke() != true
                // 按下就打断正在退回的弹簧(与 [onTouchEvent] 的 DOWN 分支同一条理由):
                // 不然被拦截的手势里,弹簧的 onFrame 会和跟手位移抢着写 grid.translationY。
                swipeSpring.cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                if (swipeArmed && abs(dy) > swipeDeadPx && abs(dy) > abs(dx)) {
                    // 定方向那一下决定整次手势往哪翻:上滑 = 下一页
                    swipeDir = if (dy < 0f) 1f else -1f
                    swipeTracking = true
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> swipeTracking = false
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                // 起手没被 [onInterceptTouchEvent] 记到时补记(两条路径的 DOWN 不一定都走到)
                swipeDownX = ev.x
                swipeDownY = ev.y
                swipeTracking = false
                swipeArmed = swipeGate?.invoke() != true
                // 手指按下打断正在退回的弹簧,否则下一帧又把位移写回去,画面自己在动
                swipeSpring.cancel()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - swipeDownX
                val dy = ev.y - swipeDownY
                // 起手落在**键帽之外**(3dp 键距 / 内边距 / 侧边)时,父视图自己就是 touch target,
                // 这条手势根本不经 [onInterceptTouchEvent] —— 方向判定必须在**这里**也有一份,
                // 否则那种起手竖滑完全没反应(与 L4PanelView.onTouchEvent 同构)。
                if (!swipeTracking && swipeArmed && abs(dy) > swipeDeadPx && abs(dy) > abs(dx)) {
                    swipeDir = if (dy < 0f) 1f else -1f
                    swipeTracking = true
                }
                if (swipeTracking) {
                    swipeTravel = -dy * swipeDir
                    val slide =
                        DragGesture.slide(swipeTravel, swipeThresholdPx(), swipeLimitPx(), swipeDeadPx)
                    // 跟手位移要**记进字段**:松手没过线时 settle(swipeSlide, 0f) 拿它当起点,
                    // 不记的话起点永远是 0 ⇒ 回弹动画从 0 弹到 0(= 硬切),连划时还会闪跳。
                    swipeSlide = slide
                    grid.translationY = -swipeDir * slide
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (swipeTracking) {
                    val dy = ev.y - swipeDownY
                    swipeTravel = -dy * swipeDir
                    val committed = DragGesture.progress(swipeTravel, swipeThresholdPx(), swipeDeadPx) >= 1f
                    if (committed) {
                        // 翻页:控制器改页码后会 render → setContent 重建键格(已把位移清零)。
                        // 这里不播补完动画 —— 内容已换,动画只对旧页有意义。
                        // **`false` = 到头没翻**(如最后一页继续上滑):退回原页,不能瞬移。
                        val moved = onSwipePage?.invoke(if (swipeDir > 0f) 1 else -1) == true
                        if (moved) {
                            grid.translationY = 0f
                            swipeSlide = 0f
                        } else {
                            swipeSpring.settle(swipeSlide, 0f)
                        }
                    } else {
                        // 没过线:弹簧把画面退回原页。
                        swipeSpring.settle(swipeSlide, 0f)
                    }
                }
                swipeTracking = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (swipeTracking) swipeSpring.settle(swipeSlide, 0f)
                swipeTracking = false
                return true
            }
        }
        return super.onTouchEvent(ev)
    }

    private fun makeKeyBg(): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * density
        setColor(keyBg)
        setStroke(1, stroke)
    }

    private fun makeTabBg(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 6f * density
        setColor(color)
    }

    private fun dip(v: Int): Int = (v * density).toInt()

    private companion object {
        /** 每行键数:出厂每页最多 27 颗,8 列 ⇒ 至多 4 行,面板不会过高。 */
        const val COLUMNS = 8

        /** 翻页触发线 = 面板高的比例(与 L5 横滑 / 候选行翻页同一个出厂比例)。 */
        const val COMMIT_FRAC = 0.22f
    }
}
