package dev.hwkbd.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout

/**
 * 输入法根视图。组词中窗口临时全屏(透明、内容贴底),键盘表面被固件映射为
 * 屏幕触摸,滑动落点遍布全屏:内容区(48dp 条;L5 行可见时上界抬到 L5 行顶)之外的
 * 事件全部转给宿主的飞字手势;内容区之内正常分发。条上方的悬浮拼音条不算内容区。
 */
class ImeRootView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** 返回是否消费该事件。 */
    var flyHandler: ((MotionEvent) -> Boolean)? = null

    /** 组词中=true:测量为全屏高(透明),触摸区覆盖键盘表面映射区域。 */
    var flyMode = false
        set(value) {
            if (field != value) {
                field = value
                requestLayout()
            }
        }

    private var flyTracking = false

    /** 48dp 条容器:contentTop 的默认上界——上方可能还有悬浮拼音条(不算 UI 区,飞字照常接)。 */
    private var barContainer: View? = null

    /** L5 行(条上方):可见时它就是最上面的 UI 区,contentTop 抬到它的顶,否则 L5 的触摸全被飞字吃掉。 */
    private var l5Panel: View? = null

    /** L6 虚拟键盘面板(最顶一层):可见时它盖过 L5,contentTop 取它的顶(同上,免得面板的触摸被飞字吃掉)。 */
    private var l6Panel: View? = null

    override fun onFinishInflate() {
        super.onFinishInflate()
        barContainer = findViewById(dev.hwkbd.ime.R.id.bar_container)
        l5Panel = findViewById(dev.hwkbd.ime.R.id.l5_panel)
        l6Panel = findViewById(dev.hwkbd.ime.R.id.l6_panel)
    }

    /**
     * 内容区上界 = 最上面一条可见的输入法 UI 行:L6 面板(最顶,盖过一切)可见时取它的顶,
     * 否则 L5 行可见时取 L5 的顶,再否则取 48dp 条顶。组词态 L5/L6 恒隐藏(让位),
     * 上界仍是条顶,飞字区不受影响。
     */
    private fun uiTop(): Int {
        val l6 = l6Panel
        if (l6 != null && l6.visibility == View.VISIBLE) return l6.top
        val l5 = l5Panel
        if (l5 != null && l5.visibility == View.VISIBLE) return l5.top
        return barContainer?.top ?: if (childCount > 0) getChildAt(0).top else 0
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        if (flyMode) {
            // WRAP_CONTENT 窗口下 spec 是 AT_MOST:直接取上限(全屏)
            val fullH = MeasureSpec.getSize(heightMeasureSpec)
            if (measuredHeight < fullH) {
                setMeasuredDimension(measuredWidth, fullH)
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val contentTop = uiTop()
        val inUi = ev.y >= contentTop
        val h = flyHandler
        // **只在起手记一笔**:2026-10-03 E2E 的 page-flip 场景三条断言全红且**一条日志都没有**,
        // 而同一批的 fly-select 通过。两条路走的是同一个 dispatchTouchEvent,差别只在
        // 落点 y —— 于是问题必然在「这个 y 算不算 UI 区」这一句上,而不是手势逻辑里。
        //
        // 但那一句取决于三件互相遮蔽的事:`uiTop()`(L5 可见时取 l5.top)、
        // `flyMode`(false 时窗口是 WRAP_CONTENT,ev.y 根本到不了 1100)、以及
        // L5 在组词态恒隐藏。**读代码分不出来**,所以把判据打出来。
        //
        // 只打 DOWN:一次手势一条,足够定位,而且不会把 logcat 冲掉
        // (之前 `fly judge` 那种每次抬手都打的日志,正是被 `tail -20` 截掉的原因)。
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            android.util.Log.i(
                TAG,
                "touch DOWN: y=${ev.y.toInt()} contentTop=$contentTop inUi=$inUi " +
                    "flyMode=$flyMode h=${h != null} winH=$height"
            )
        }
        if (!inUi || flyTracking) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || flyTracking) {
                flyTracking = ev.actionMasked != MotionEvent.ACTION_UP &&
                    ev.actionMasked != MotionEvent.ACTION_CANCEL
                if (h != null && h.invoke(ev)) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private companion object {
        /**
         * tag 必须在 `tools/aitest/harness.mjs` 的 `takeLog` 白名单里
         * (`logcat -s ImeService:* CandidateBar:* ImeRoot:*`)—— `-s` 会丢掉不在列表里的。
         */
        private const val TAG = "ImeRoot"
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val contentTop = uiTop()
        if (ev.y < contentTop) {
            val h = flyHandler
            if (h != null && h.invoke(ev)) return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
