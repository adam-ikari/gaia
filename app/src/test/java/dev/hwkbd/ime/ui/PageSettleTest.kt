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

    companion object {
        /** 一次翻页的完整行程 = 屏宽。用它把「过冲比例」换算成「过冲多少像素」。 */
        const val FULL_PAGE = 1440f
    }

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
     * 这条是**本次事故的核心**:惯性投影**到不了**整页宽度。
     *
     * 旧代码把 [Landing.project] 的结果当落点。实测那个值离「走完」差多远
     * (Titan 2 屏宽 1440px,提交线 8px ⇒ 刚过线时 `pageSlide` 只有十几 px):
     *
     * | pageSlide | v=500 | v=2000 | v=4000 |
     * |---|---|---|---|
     * | 14px(刚过线) | 24 | 181 | 681 |
     * | 200px | 210 | 367 | 867 |
     * | 800px | 810 | 967 | **1440** |
     *
     * 只有「已经划过 800px」且「甩到 4000px/s」这种极端组合才刚好够走完 ——
     * 而 4000px/s 是屏宽/秒多,人手达不到。**这解释了为什么用户会觉得
     * 「时好时坏」**:绝大多数手势下整页行程根本没走完,画面是跳过去的。
     *
     * 断言用「所有现实速度下都到不了」,而不是拍一个百分比阈值 ——
     * 后者只是把某个随手选的数钉住,换屏宽就失效。
     */
    @Test
    fun `惯性投影到不了整页宽度`() {
        for (pageSlide in floatArrayOf(14f, 60f, 200f, 400f, 800f)) {
            for (v in floatArrayOf(300f, 800f, 1500f, 2500f)) {
                val byInertia = Landing.project(pageSlide, full, v)
                assertTrue(
                    "pageSlide=$pageSlide v=$v → 惯性投影 $byInertia 竟然到了 $full",
                    byInertia < full,
                )
            }
        }
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
     * 收回必须**零过冲**,提交必须有冲量。
     *
     * 方向反了的话「没翻成、滑回去」会带一次弹跳,读起来像不是自己做的决定 ——
     * 与 `SpringSpec.FLY_BACK`(飞字放弃)、`RUBBER_BACK`(撞头)同一类语义。
     *
     * ## 这里踩过一个坑:过冲公式只对欠阻尼成立
     *
     * 常用写法 `exp(-πζ/√(1-ζ²))` 是**从欠阻尼解推的**,分母里 `√(1-ζ²)` 要求 ζ<1。
     * 拿它去算 ζ=1.9 会直接 domain error(第一版就是这么写的,单测红了一次)。
     * 过阻尼根本没有过冲:解是纯指数衰减,峰值就是 1。
     *
     * 所以判据写成:**欠阻尼 ⇒ 过冲 > 0;过阻尼 ⇒ 过冲 == 0**。
     */
    @Test
    fun `收回零过冲 提交有过冲`() {
        fun overshootOf(z: Float): Float =
            if (z >= 1f) 0f
            else kotlin.math.exp(-Math.PI.toFloat() * z / kotlin.math.sqrt(1f - z * z))

        val commit = overshootOf(SpringSpec.PAGE_COMMIT.dampingRatio)
        val rollback = overshootOf(SpringSpec.PAGE_ROLLBACK.dampingRatio)
        assertTrue("提交(ζ=${SpringSpec.PAGE_COMMIT.dampingRatio})应当有过冲,实际 $commit", commit > 0f)
        assertEquals("收回(ζ=${SpringSpec.PAGE_ROLLBACK.dampingRatio})必须零过冲", 0f, rollback, 0f)
    }

    /**
     * 提交弹簧的**过冲必须小到看不见**(2026-10-04 用户「降低翻頁動畫彈性」)。
     *
     * 钉的是**过冲的绝对像素**,不是阻尼比本身 —— 因为过冲比与行程无关,
     * 真正决定「弹不弹」的是 `比例 × 行程`。1440px 是 Titan 2 的屏宽,
     * 也是一次翻页的完整行程。
     *
     * 原来 ζ=0.72 时这个值是 **55px**(3.84%),肉眼是明显的回弹;
     * 而它在落点 bug 修好之前是无害的 —— 那时行程只有一百多 px,过冲只有 3.8px。
     * ⇒ 「弹性变强」不是有人调了参数,是**行程变长把一个隐性比例放大了**。
     *
     * 上界取 4px:约 3px 是「看得见一点但不晃」的分界,留一点余量。
     */
    @Test
    fun `提交的过冲在小到看不见的量级`() {
        val z = SpringSpec.PAGE_COMMIT.dampingRatio
        val overshoot = if (z >= 1f) 0f
            else kotlin.math.exp(-Math.PI.toFloat() * z / kotlin.math.sqrt(1f - z * z))
        val px = overshoot * FULL_PAGE
        assertTrue(
            "1440px 行程上的过冲是 ${px}px(ζ=$z),超过 4px 就看得见了",
            px < 4f,
        )
    }

    /**
     * 横滑与上滑的死区**必须是两个不同的常量**(2026-10-04 用户选择拆开)。
     *
     * 这条不是「记一个数」,是防**合并回去**:2026-10-02 用户要求两者统一,
     * 而 10-04 又要求「翻页更灵 + 上滑不动」—— 在共用一个常量时这两条是冲突的,
     * 所以拆开。谁哪天觉得「统一更简洁」把它合回去,横滑就会跟着变慢/变钝。
     *
     * 另外守住方向:横滑必须**更小**(翻页要更灵),不是更大。
     */
    @Test
    fun `横滑死区比上滑小且两者不相等`() {
        assertTrue(
            "横滑死区 ${DragGesture.DEAD_DP_SWIPE}dp 应小于上滑 ${DragGesture.DEAD_DP}dp",
            DragGesture.DEAD_DP_SWIPE < DragGesture.DEAD_DP,
        )
        assertTrue("两个常量不许相等,否则等于没拆", DragGesture.DEAD_DP_SWIPE != DragGesture.DEAD_DP)
    }

    @Test
    fun `两支都不能是临界阻尼`() {
        // ζ=1 是所有不过冲曲线里**最慢**的一条 —— 这正是「弹性太强」的根因(见 brain
        // 的 spring-damping-direction)。翻页提交要的是冲量,收回要的是"赶紧回去"。
        assertTrue(SpringSpec.PAGE_COMMIT.dampingRatio != 1f)
        assertTrue(SpringSpec.PAGE_ROLLBACK.dampingRatio != 1f)
    }
}