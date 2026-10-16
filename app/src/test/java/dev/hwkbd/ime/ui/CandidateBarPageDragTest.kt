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
 * 行宽取 Titan 2 真机候选行 1440px:面板 1440×1440,候选行通栏(两端只露底色不缩进)。
 * 注意这是**物理像素**,与用户改过的最小逻辑宽度(598dp)无关。
 */
class CandidateBarPageDragTest {

    private val viewW = 1440f
    private val commitPx = viewW * PAGE_COMMIT_FRAC
    private val DEAD = DragGesture.DEAD_DP * 2f // density 2.0 ⇒ 16px,死区常量不再是阈值的比例

    /** 位移向**左**为正(左滑=下一页),与页面里 `dir` 无关的直观写法。 */
    private fun progress(dxLeft: Float): Float =
        pageDragProgress(
            x = 1000f - dxLeft, baseX = 1000f, dir = 1f,
            commitPx = commitPx, dead = DEAD,
        )

    @Test
    fun `dead zone swallows small flicks`() {
        assertEquals(0f, progress(0f), 1e-6f)
        assertEquals(0f, progress(DEAD * 0.5f), 1e-6f)
        assertEquals(0f, progress(DEAD * 0.99f), 1e-6f)
    }

    @Test
    fun `committing requires dragging a third of the row`() {
        // 恰好 1/3 行宽 = 触发线
        assertEquals(1f, progress(commitPx), 1e-3f)
        assertTrue("不到 1/3 不提交", progress(commitPx * 0.9f) < 1f)
        assertTrue("过 1/3 提交", progress(commitPx * 1.1f) >= 1f)
    }

    @Test
    fun `旧的 24dp 一划不翻页 但现在会跟手一小段`() {
        // 旧判据 24dp,在 598dp 逻辑宽对应的密度(~2.4)上约 58px
        val old = 24f * 2.4f
        // 死区改成常量 ${DEAD}px 之后,58px **已经在死区之外**了 ——
        // 所以它不再是被死区吞掉,改由触发线(${commitPx}px)挡住。结论不变:不翻页。
        assertTrue("旧判据已越过常量死区", old > DEAD)
        assertTrue("但仍远不到触发线", old < commitPx)
        // 进度不为 0(已在死区之外,该有响应),但远不到提交线 —— 所以不翻页
        assertTrue("已越过死区,应有一点点进度", progress(old) > 0f)
        assertTrue("但不到提交线,所以不翻页", progress(old) < 1f)
        // 而且现在画面会跟手一小段(死区的 DEAD_SLIP 响应),比"纹丝不动"好
        assertTrue("死区之外应当有跟手响应", DragGesture.slide(old, viewW, DEAD) > 0f)
    }

    @Test
    fun `死区是常量 不随触发线变`() {
        // 2026-10-02 用户「死区大小不随着触发阈值变化」:三档触发线(158/238/324dp)
        // 下死区都一样。此前死区取阈值的 1/4,标准档就是 59dp —— 手指先走 59dp
        // 屏幕纹丝不动,这就是"翻页不跟手"的直接来源。
        for (frac in listOf(0.22f, 0.33f, 0.45f)) {
            val t = viewW * frac
            assertEquals(
                "触发线 ${viewW * frac}px 下,死区内不该有进度",
                0f, DragGesture.progress(DEAD - 1f, t, DEAD), 1e-6f,
            )
        }
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
            pageDragProgress(1300f, 1000f, 1f, commitPx, DEAD),
            1e-6f,
        )
        assertEquals(
            0f,
            pageDragProgress(700f, 1000f, -1f, commitPx, DEAD),
            1e-6f,
        )
    }

    @Test
    fun `previous-page direction is the mirror of next-page`() {
        // 同一段手指位移,dir=-1(上一页)且手指往右移 = 等价于往左移且 dir=+1
        val viaNext = pageDragProgress(700f, 1000f, 1f, commitPx, DEAD)
        val viaPrev = pageDragProgress(1300f, 1000f, -1f, commitPx, DEAD)
        assertEquals(viaNext, viaPrev, 1e-6f)
    }
}
