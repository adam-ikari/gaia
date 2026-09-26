package dev.hwkbd.ime.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.LinearLayout

/**
 * 输入法根视图。组词中窗口临时全屏(透明、内容贴底),键盘表面被固件映射为
 * 屏幕触摸,滑动落点遍布全屏:内容区之外(child0.top 以上)的事件全部转给
 * 宿主的飞字手势;内容区之内正常分发。
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
        val contentTop = if (childCount > 0) getChildAt(0).top else 0
        val inUi = ev.y >= contentTop
        val h = flyHandler
        if (!inUi || flyTracking) {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN || flyTracking) {
                flyTracking = ev.actionMasked != MotionEvent.ACTION_UP &&
                    ev.actionMasked != MotionEvent.ACTION_CANCEL
                if (h != null && h.invoke(ev)) return true
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        val contentTop = if (childCount > 0) getChildAt(0).top else 0
        if (ev.y < contentTop) {
            val h = flyHandler
            if (h != null && h.invoke(ev)) return true
        }
        return super.dispatchGenericMotionEvent(ev)
    }
}
