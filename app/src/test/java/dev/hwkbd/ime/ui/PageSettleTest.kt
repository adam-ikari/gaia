package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻页收尾的落点与阻尼方向 —— 2026-10-04 用户报「松手时翻不过去」
 * 「翻过去/收回的动画不对」「翻頁困難」之后加的。
 *
 * ## 钉住的是一条**语义**,不是一个手感数字
 *
 * 落点不是「算出来的数」,而是「走完这一页」或「回到原位」——它由**是否提交**决定,
 * 与惯性大小无关。这条如果被改坏(例如有人觉得「落点该按惯性算」而改回去),
 * 症状是整页行程走不完、画面靠 `clearPageTurn()` 跳过去,而**编译与单测都不会响**。
 *
 * [SpringChannel] 与 [CandidateBarView] 是 View,JVM 跑不了,所以这里守的是
 * 那个被抽出来的纯函数 [pageSettleFor] —— 它是这条语义的唯一落点。
 */
class PageSettleTest {

    private val full = 1440f // Titan 2 屏宽:候选行宽度就是屏宽

    // ------------------------------------------------------------ 落点

    @Test
    fun `提交时落到整页宽度而不是惯性投影`() {
        val (target, _) = pageSettleFor(committed = true, full = full)
        assertEquals("提交必须把这一页走完", full, target, 0f)
    }

    @Test
    fun `收回时落回 0 而不是惯性投影`() {
        val (target, _) = pageSettleFor(committed = false, full = full)
        assertEquals("收回必须回到原页", 0f, target, 0f)
    }

    /**
     * 这条是**本次事故的核心**。
     *
     * 旧代码把 [Landing.project] 的结果当落点,而它在 `pageSlide` 只有十几 px 时
     * 最多给出 ~166px(最猛的一甩)。也就是说旧落点离「走完」差了一整页 ——
     * 整页行程从来没走完,画面是跳过去的。
     *
     * 这里用真实量级把那个差距量化出来:即便手指已经划过了大半(只剩 200px 没走完)、
     * 甩得再猛,旧落点也只到 `pageSlide + 166`,而正确落点是 `full`。
     */
    @Test
    fun `惯性投影远不足以走完这一页`() {
        val pageSlide = 200f
        val hardFling = 2000f // px/s,相当快的甩
        val byInertia = Landing.project(pageSlide, full, hardFling)
        assertTrue(
            "惯性投影($byInertia)不该被当成完成落点",
            byInertia < full,
        )
        // 即便如此,差距仍然是一整页的绝大部分 —— 所以旧实现等于没走完。
        assertTrue(
            "惯性投影与走完之间差了 ${full - byInertia}px,占整页 ${(full - byInertia) / full}",
            (full - byInertia) / full > 0.8f,
        )
    }

    // ------------------------------------------------------------ 阻尼方向

    @Test
    fun `提交用欠阻尼 收回用过阻尼`() {
        val (_, commitSpec) = pageSettleFor(committed = true, full = full)
        val (_, rollbackSpec) = pageSettleFor(committed = false, full = full)
        assertEquals("提交应该是 [PAGE_COMMIT]", SpringSpec.PAGE_COMMIT, commitSpec)
        assertEquals("收回应该是 [PAGE_ROLLBACK]", SpringSpec.PAGE_ROLLBACK, rollbackSpec)
        assertTrue(
            "提交是确认动作 ⇒ 欠阻尼(ζ<1)才有到位感,实际 ${commitSpec.dampingRatio}",
            commitSpec.dampingRatio < 1f,
        )
        assertTrue(
            "收回是放弃 ⇒ 过阻尼(ζ>1)才不来回晃,实际 ${rollbackSpec.dampingRatio}",
            rollbackSpec.dampingRatio > 1f,
        )
    }

    /**
     * 收回必须比提交**更不容易过冲**。
     *
     * 方向反了的话「没翻成、滑回去」会带一次弹跳,读起来像不是自己做的决定 ——
     * 与 `SpringSpec.FLY_BACK`(飞字放弃)、`RUBBER_BACK`(撞头)同一类语义。
     */
    @Test
    fun `收回的过冲量必须小于提交`() {
        fun overshootSpec(z: Float): Float =
            kotlin.math.exp(-Math.PI.toFloat() * z / kotlin.math.sqrt(1f - z * z))

        val commit = overshootSpec(SpringSpec.PAGE_COMMIT.dampingRatio)
        val rollback = overshootSpec(SpringSpec.PAGE_ROLLBACK.dampingRatio)
        assertTrue("收回过冲($rollback)应远小于提交过冲($commit)", rollback * 5f < commit)
    }

    @Test
    fun `两支都不能是临界阻尼`() {
        // ζ=1 是所有不过冲曲线里**最慢**的一条 —— 这正是「弹性太强」的根因(见 brain
        // 的 spring-damping-direction)。翻页提交要的是冲量,收回要的是"赶紧回去"。
        assertTrue(SpringSpec.PAGE_COMMIT.dampingRatio != 1f)
        assertTrue(SpringSpec.PAGE_ROLLBACK.dampingRatio != 1f)
    }
}