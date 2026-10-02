package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 越界橡皮筋的**拖动状态**断言。
 *
 * 2026-10-02 真机报「左右滑动候选词滑到头的弹性动画没实现」。数学([Rubber])一直是对的,
 * 坏的是状态:[CandidateBarView] 的 MOVE 分支每帧都会重新调一次 begin,而那个 begin
 * 无条件把基线挪到当前手指位置、进度清零 —— 于是 `raw` 恒为 0,而 onDraw 的守卫是
 * `raw > 0f`,那个分支一次都进不去。
 *
 * **这一类 bug 只有在状态被类型管住之后才不会再来一遍**,所以规矩写在 [RubberDrag]
 * 里,并在这里钉死。视图测不到,所以状态必须抽成纯类(同 [Rubber] 的理由)。
 *
 * ## 方向约定(写测试时最容易搞反的一处)
 *
 * `dir = +1` 是「拖向下一页」= **手指向左**(x 减小);`dir = -1` 反之。
 * 位移按 `[RubberDrag.raw] = max(0, -(x - baseX) * dir)` 算,所以 `dir=+1` 时
 * 必须让 x **变小**才有位移。
 */
class RubberDragTest {

    @Test
    fun `同方向重复 begin 是空操作 不清进度`() {
        val r = RubberDrag()
        assertTrue("首次起手应锚定", r.begin(1f, 100f))
        r.move(40f) // 手指向左 60px
        val after1 = r.raw
        assertTrue("应当有位移,实际 ${r.raw}", after1 > 0f)

        // **这就是那条 bug 的样子**:下一帧又调一次 begin,同方向
        assertFalse("同方向不该重新锚定", r.begin(1f, 35f))
        assertEquals("进度不能被 begin 清掉", after1, r.raw, 0.01f)

        r.move(10f) // 继续往左
        assertTrue("继续跟手", r.raw > after1)
    }

    @Test
    fun `反方向 begin 重新锚定`() {
        val r = RubberDrag()
        r.begin(1f, 100f)
        r.move(40f)
        assertTrue(r.raw > 0f)
        // 手指往回拉:基准要跟着回到这里,否则 raw 会继续往一个方向累
        assertTrue("反方向应重新锚定", r.begin(-1f, 40f))
        assertEquals("重新锚定后进度归零", 0f, r.raw, 0.01f)
    }

    @Test
    fun `往回拉位移会缩到 0 但不为负`() {
        val r = RubberDrag()
        r.begin(1f, 300f)
        r.move(150f)
        assertTrue(r.raw > 0f)
        r.move(300f) // 回到起点
        assertEquals(0f, r.raw, 0.01f)
        r.move(400f) // 越过起点往另一边
        assertEquals("不能为负", 0f, r.raw, 0.01f)
        assertEquals("方向仍是起手那个", 1f, r.dir, 0.001f)
    }

    @Test
    fun `stop 只停手指 方向与位移留给弹回动画`() {
        val r = RubberDrag()
        r.begin(-1f, 500f)
        r.move(560f) // dir=-1 ⇒ 手指向右
        val from = r.raw
        assertTrue(from > 0f)
        r.stop()
        assertFalse("手指不再驱动", r.active)
        // 弹回动画要靠 dir 算往哪边退、靠 raw 当起点 —— 所以这两项不能被 stop 清掉
        assertEquals("方向要留着", -1f, r.dir, 0.001f)
        assertEquals("位移要留着当动画起点", from, r.raw, 0.01f)
        // 动画期间 move 不再起作用
        r.move(600f)
        assertEquals("stop 之后 move 无效", from, r.raw, 0.01f)
    }

    @Test
    fun `animateTo 写动画帧 reset 全清`() {
        val r = RubberDrag()
        r.begin(1f, 100f)
        r.move(40f)
        r.stop()
        r.animateTo(12f)
        assertEquals(12f, r.raw, 0.01f)
        r.reset()
        assertEquals(0f, r.raw, 0.01f)
        assertEquals(0f, r.dir, 0.001f)
        assertFalse(r.active)
    }

    @Test
    fun `没在拖时 move 无效`() {
        val r = RubberDrag()
        r.move(200f)
        assertEquals(0f, r.raw, 0.01f)
    }

    @Test
    fun `接上整条链路 位移不再恒为 0`() {
        // 复刻视图里每帧的动作顺序:begin → move,重复十次。修之前 raw 每帧都被清零,
        // 十次之后仍然是 0,于是绘制分支永不进入。
        val r = RubberDrag()
        var x = 200f
        r.begin(1f, x)
        repeat(10) {
            x -= 12f // 手指持续向左
            r.begin(1f, x)
            r.move(x)
        }
        assertTrue("十帧之后应累计出可见位移,实际 ${r.raw}", r.raw > 60f)
        assertTrue("经阻尼后仍在可见范围", Rubber.shift(r.raw, 125f, 140f) > 30f)
    }
}
