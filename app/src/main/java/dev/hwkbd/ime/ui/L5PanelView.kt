package dev.hwkbd.ime.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.LinearLayout
import dev.hwkbd.ime.ime.L5Key
import dev.hwkbd.ime.ime.L5Layout
import dev.hwkbd.ime.ime.L5Ui
import dev.hwkbd.ime.keymap.KeyOut
import kotlin.math.abs

/**
 * L5 面板(原 2026-10-15 的 L6 虚拟键盘;2026-10-19 与 L5 溢出行合并后统一叫 L5,
 * 支持多个 tab 页和上下滑动翻页)。
 *
 * ## 结构:tab 条 + 键格
 * 竖排两块:顶上一行 tab([L5Tab.label],点一下切 tab),下面是当前页的键格([L5Page.keys]
 * 落在同一套 [L5Ui.GRID_COLUMNS] 列基础网格上,长键按 [L5Key.span] 跨格,不裁字、键帽按 L4 的样式画)。点键 → 交还 [onKey],走与 L4
 * 面板**同一条**上屏通路([dev.hwkbd.ime.ime.ImeController.commitL4Key])——
 * 虚拟键盘的键与状态行里的键在「干什么」上没区别,只是长在不同面板上。
 * **高度固定 [L5Ui.ROWS_PER_PAGE] 行**(2026-10-20):这一页装不满就补空行,多出来的进下一页,
 * 于是切 tab / 翻页时面板高矮不跳。
 *
 * ## 上下滑 = tab 内翻页(2026-10-15 定案「不跨 tab」)
 * 定方向那一下判**垂直**(死区 [DragGesture.DEAD_DP_SWIPE] 固定、不随阈值变,
 * 与候选行 / L5 面板横滑同一套 [DragGesture] 常量)。到头不循环 —— 越界交还
 * [L5Ui.stepPage] 判定,它返回 false 说明没翻,本视图原样退回。
 *
 * ## 只管画,不管状态
 * 「当前在哪个 tab / 哪一页」是 [L5Ui] 的状态机字段,由控制器改;本视图在 [setContent]
 * 里**照单全收**地重建,不做自己的记性。面板的显隐也归 service([ImeService.renderViews]),
 * 不归本视图 —— 与项目「状态机纯逻辑、绘制靠真机」的分工一致。
 */
class L5PanelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** 点面板里的一颗键(文本 / 功能键原样交出,上屏由控制器走 [commitL4Key])。 */
    var onKey: ((KeyOut) -> Unit)? = null

    /**
     * 点**整行键**(剪贴板 tab:每条一行)时的回调,**不走** [onKey] ——
     * 剪贴板条目是「粘贴」不是「按键」:纯字母的剪贴内容若走 [onKey] 会被当成拼音进组词
     * (面板定案「面板上点符号=直接上屏」的前提是符号盘没有 a-z,剪贴板不满足)。
     */
    var onRowKey: ((KeyOut) -> Unit)? = null

    /** 点 tab 条上的一个 tab(下标)。切 tab 由控制器 [l5SelectTab] 归零页码。 */
    var onTab: ((Int) -> Unit)? = null

    /**
     * 上下滑翻页。`delta = +1` 下一页 / `-1` 上一页;**到头不循环** ⇒ 返回 `false`
     * (控制器 [dev.hwkbd.ime.ime.ImeController.l5StepPage] 的判定),本视图据此把画面退回原页而不是瞬移。
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
     * 照状态机当前态重建 tab 条 + 键格。键格与 L4 同一套色:键帽 [ImeTheme.pressed]、
     * 描边 [ImeTheme.divider]、字 [ImeTheme.fg];面板底取 [ImeTheme.barBg](整条带子同色)。
     *
     * @return 是否真的重建了。面板高度**固定 [L5Ui.ROWS_PER_PAGE] 行**(2026-10-20),
     *   切 tab / 翻页不再改高度 ⇒ 这里其实不必再补报 insets;仍返回重建与否,服务层照旧
     *   在重建后报一次,免得将来某页又需要不同高度时漏报(报少了顶裁)。
     */
    fun setContent(ui: L5Ui, theme: ImeTheme): Boolean {
        fg = theme.fg
        keyBg = theme.pressed
        stroke = theme.divider
        accent = theme.accent
        panelBg = theme.barBg
        // 签名含**当前页键的内容** —— 剪贴板 tab 的条目会变而 tab/页不变,只看 tab/页的话
        // 面板会停在旧内容上(与候选行「签名要含显示内容」同一条教训)。
        val sig = "${theme.id}/${ui.tabIndex}/${ui.pageIndex}/${ui.pageKeys().hashCode()}"
        if (sig == lastSig) return false
        lastSig = sig
        // 翻页/重建把跟手位移清掉:旧页的位移不该带到新页的键上。
        swipeSlide = 0f
        swipeSpring.cancel()
        grid.translationY = 0f
        setBackgroundColor(panelBg)
        tabStrip.setBackgroundColor(panelBg)
        rebuildTabs(ui)
        rebuildGrid(ui)
        return true
    }

    private fun rebuildTabs(ui: L5Ui) {
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

    private fun rebuildGrid(ui: L5Ui) {
        grid.removeAllViews()
        val keys = ui.pageKeys()
        // ROWS(剪贴板):每条一行、整行宽;GRID(符号盘/场景):同一套基础网格,
        // 长键按 [L5Key.span] 跨格(2026-10-19 用户「内容可跨网格增长」)。
        val rowsLayout = ui.tab.layout == L5Layout.ROWS
        val columns = if (rowsLayout) 1 else ui.tab.columns.coerceAtLeast(1)
        val gap = dip(3)
        var idx = 0
        var rows = 0
        while (idx < keys.size) {
            val row = LinearLayout(context).apply { orientation = HORIZONTAL }
            var used = 0
            while (idx < keys.size) {
                val k = keys[idx]
                val span = if (rowsLayout) 1 else k.span.coerceIn(1, columns)
                // 本行剩下的格放不下这颗长键 ⇒ 换行,别把 span 压小(压小必裁字)
                if (used + span > columns) break
                // **外框精确占格、键距画在内层** —— 权重槽才是「格」:外框一律无水平 margin,
                // 每一行的 1 格宽就都等于 `行宽 / columns`;marginEnd 若加在外框上,行长不同
                // (键数不同、跨格组合不同)时每行要扣的 margin 总数也不同,列就会错位
                // (2026-10-19 用户「L5按键网格没对齐」)。视觉 3dp 键距由内层 TextView 的
                // marginEnd 提供:span 格外框宽 = span×格宽,正好等于相邻 span 个单格键 + 键距。
                val cell = FrameLayout(context)
                cell.addView(
                    makeKey(k, direct = rowsLayout),
                    FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
                        .apply { marginEnd = gap },
                )
                row.addView(
                    cell,
                    LinearLayout.LayoutParams(0, dip(36), span.toFloat()).apply { bottomMargin = gap },
                )
                used += span
                idx++
            }
            if (used < columns) {
                // 不满一行时补**等宽空槽**:剩下的键/空槽一起把这一行铺满网格,
                // 不然最后一颗会被权重撑成横贯整行的怪键。
                row.addView(View(context), LinearLayout.LayoutParams(0, dip(36), (columns - used).toFloat()))
            }
            grid.addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            rows++
        }
        // 面板高度**固定 [L5Ui.ROWS_PER_PAGE] 行**(2026-10-20 用户「L5的高度固定两行 多的内容需要翻页」):
        // 这一页装不满两行(如场景 4 颗跨格键只占一行)时补空行,切 tab / 翻页高度不跳。
        while (rows < L5Ui.ROWS_PER_PAGE) {
            grid.addView(emptyRow(columns, gap), LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            rows++
        }
    }

    /** 一行占位空格(整行权重铺满),只为把面板垫到固定行高。 */
    private fun emptyRow(columns: Int, gap: Int): LinearLayout =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            addView(
                View(context),
                LinearLayout.LayoutParams(0, dip(36), columns.toFloat()).apply { bottomMargin = gap },
            )
        }

    private fun makeKey(k: L5Key, direct: Boolean = false): View = TextView(context).apply {
        text = k.label
        maxLines = 1
        // GRID 键帽的字**居中落在自己的格心**:键框按权重对齐只是第一层,
        // 字若默认贴左上角,视觉上仍读成「没对齐」。ROWS(剪贴板)保持左对齐、垂直居中,
        // 长文本从头读更自然。
        gravity = if (direct) Gravity.START or Gravity.CENTER_VERTICAL else Gravity.CENTER
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
        setTextColor(fg)
        background = makeKeyBg()
        setOnClickListener {
            performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            if (direct) onRowKey?.invoke(k.out) else onKey?.invoke(k.out)
        }
    }

    // ------------------------------------------------------------- 上下滑翻页
    //
    // 与候选行横滑同一模型:**画面位移 = 手指位移**(slide),进度只用来判提交;
    // 死区固定、不随阈值变。只是轴向换成了 Y。

    /**
     * 本次手势的翻页许可(在 DOWN 时向 [swipeGate] 问一次)。默认 true = 没接门就是全放行。
     * 与 L4 同一套:静默窗(用户 2026-10-02「所有的键盘滑动都无效」;2026-10-16 起是
     * "按住键期间 + 抬手后一段",见 [dev.hwkbd.ime.ime.KeyEventGate])只挡滑动,**点键不看它**。
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
     * 抢过来(与 L5 面板横滑同一套,见 [L4PanelView.onInterceptTouchEvent])。
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
                        DragGesture.slide(swipeTravel, swipeLimitPx(), swipeDeadPx)
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
        /** 翻页触发线 = 面板高的比例(与候选行翻页同一个出厂比例)。 */
        const val COMMIT_FRAC = 0.22f
    }
}
