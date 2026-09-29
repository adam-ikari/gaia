package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选行**横滑翻页**的阈值换算:跟手 + 可反向。
 *
 * 2026-09-29 用户定案:「翻页阈值也需要调整 …… 横向滑动没有到阈值的之后是跟手的动画,
 * 跟上滑动画一致,松手或者反向滑动可以反向动画」。此前是一条写死的 24dp **抬手**判据,
 * 既不跟手也太容易误翻。
 *
 * 进度数学已与其他两处手势共用 [DragGesture](见 [DragGestureTest]);这里钉的是本视图
 * 特有的那层:**手指 x 坐标 → 沿翻页方向的位移**。
 *
 * 行宽取 Titan 2 真机的 1360px(598dp 屏 - 两侧 16dp 安全区)。
 */
class CandidateBarPageDragTest {

    private val viewW = 1360f
    private val commitPx = viewW * PAGE_COMMIT_FRAC

    /** 位移向**左**为正(左滑=下一页),与页面里 `dir` 无关的直观写法。 */
    private fun progress(dxLeft: Float): Float =
        pageDragProgress(x = 1000f - dxLeft, baseX = 1000f, dir = 1f, viewW = viewW)

    @Test
    fun `dead zone swallows small flicks`() {
        val dead = DragGesture.dead(commitPx)
        assertEquals(0f, progress(0f), 1e-6f)
        assertEquals(0f, progress(dead * 0.5f), 1e-6f)
        assertEquals(0f, progress(dead * 0.99f), 1e-6f)
    }

    @Test
    fun `committing requires dragging a third of the row`() {
        // 恰好 1/3 行宽 = 触发线
        assertEquals(1f, progress(commitPx), 1e-3f)
        assertTrue("不到 1/3 不提交", progress(commitPx * 0.9f) < 1f)
        assertTrue("过 1/3 提交", progress(commitPx * 1.1f) >= 1f)
    }

    @Test
    fun `the old 24dp flick now falls inside the dead zone`() {
        val old = 24f * 2.4f // 旧判据 24dp,在 2.4x 密度上 57.6px
        assertTrue("旧阈值比新死区(${DragGesture.dead(commitPx)}px)还小", old < DragGesture.dead(commitPx))
        assertEquals("所以 24dp 一划现在不再翻页", 0f, progress(old), 1e-6f)
    }

    @Test
    fun `dragging back toward the origin pulls progress down - the reverse animation`() {
        // 都在线性段内比:超过 commitPx 的进度一律夹在 1,比不出"变小"。
        // 这正是"跟手"的意义——手指往回走,画面就跟着往回退,松手就是收回原页。
        val forward = progress(commitPx * 0.6f)
        val back = progress(commitPx * 0.2f)
        assertTrue("反向滑动让进度变小", back < forward)
        assertTrue("拖到触发线进度为 1", progress(commitPx) >= 1f)
        assertEquals("拖过触发线后夹住", 1f, progress(commitPx * 2f), 1e-6f)
    }

    @Test
    fun `a single gesture commits one way - opposite direction yields no progress`() {
        // dir=+1(下一页)时手指往**右**拖:进度 0,不会把"想翻回去"也认成同一次拖动。
        // 往回翻要靠下一次手势接管(见 beginPageDrag 的角色对调),这是刻意的。
        assertEquals(
            0f,
            pageDragProgress(x = 1300f, baseX = 1000f, dir = 1f, viewW = viewW),
            1e-6f,
        )
        assertEquals(
            0f,
            pageDragProgress(x = 700f, baseX = 1000f, dir = -1f, viewW = viewW),
            1e-6f,
        )
    }

    @Test
    fun `previous-page direction is the mirror of next-page`() {
        // 同一段手指位移,dir=-1(上一页)且手指往右移 = 等价于往左移且 dir=+1
        val viaNext = pageDragProgress(x = 700f, baseX = 1000f, dir = 1f, viewW = viewW)
        val viaPrev = pageDragProgress(x = 1300f, baseX = 1000f, dir = -1f, viewW = viewW)
        assertEquals(viaNext, viaPrev, 1e-6f)
    }
}
