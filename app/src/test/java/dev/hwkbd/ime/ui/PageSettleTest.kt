package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ------------------------------------------------ 时长按行程给(2026-10-04 用户「一直都很快」)

    /**
     * 翻页的**平均速度**必须落在夹住的范围内,不能像修复前那样固定 0.26s。
     *
     * 根因不在「某个 response 调小了」,而在**位移最大的动画用了最短的时长**:
     * 翻页最多 1440px 却只给 0.26s(≈5540 px/s),而飞字起飞 52px 给 0.22s(≈236 px/s)——
     * **速度差 20 倍**。所以用户说「一直都很快」是准确的。
     *
     * 这条钉的是「速度有上限」而不是「某个秒数」:时长是**算出来的**,
     * 钉一个具体秒数只会让人以为那是调出来的。
     */
    @Test
    fun `翻页时长按行程给 速度被夹住`() {
        // 行程落在中间 ⇒ 精确等于 目标速度
        val mid = PageTiming.response(900f)
        assertEquals(
            "900px 应落在夹取区间内",
            900f / PageTiming.PAGE_TARGET_SPEED, mid, 0.001f,
        )
        // 短行程 ⇒ 撞下限,时长不趋近 0(否则闪一下像跳变)
        assertEquals(PageTiming.PAGE_MIN_RESPONSE, PageTiming.response(50f), 0f)
        assertEquals(PageTiming.PAGE_MIN_RESPONSE, PageTiming.response(0f), 0f)
        // 长行程 ⇒ 撞上限,不让用户等太久
        assertEquals(PageTiming.PAGE_MAX_RESPONSE, PageTiming.response(4000f), 0f)
    }

    /** 实测里出现过的行程都不得快于修复前那个 3042 px/s。 */
    @Test
    fun `实测行程下不许再出现修复前那种速度`() {
        // E2E 埋点里 real 出现过的 from 值(0 / 475 / 585 / 547)
        for (remaining in intArrayOf(0, 100, 300, 475, 585, 791, 901, 1376)) {
            val r = PageTiming.response(remaining.toFloat())
            if (remaining == 0) continue
            val speed = remaining / r
            assertTrue(
                "剩余 $remaining px 用 ${r}s ⇒ $speed px/s,不该快于修复前的 3042",
                speed <= 3100f,
            )
        }
    }

    /** 提交与收回用**同一个** response 基准(用户 2026-10-04 定:「收回也一起慢」)。 */
    @Test
    fun `提交与收回的时长基准一致`() {
        assertEquals(
            SpringSpec.PAGE_COMMIT.response, SpringSpec.PAGE_ROLLBACK.response, 0f,
        )
        // 且两支都只差阻尼比 —— 方向语义不同,时长不该不同
        assertEquals(
            SpringSpec.PAGE_COMMIT.dampingRatio != SpringSpec.PAGE_ROLLBACK.dampingRatio, true,
        )
    }

    /** 时长按**剩余行程**算,不是按整页 —— 手指已经划过的不该再等一次。 */
    @Test
    fun `时长用剩余行程而不是整页`() {
        val (_, wholePageSpec) = pageSettleFor(committed = true, full = FULL_PAGE, from = 0f)
        val (_, halfDoneSpec) = pageSettleFor(committed = true, full = FULL_PAGE, from = FULL_PAGE * 0.5f)
        assertTrue(
            "划了一半之后剩余行程更短,时长应更短(${halfDoneSpec.response} vs ${wholePageSpec.response})",
            halfDoneSpec.response < wholePageSpec.response,
        )
    }

    // ------------------------------------------------ 提交线的量纲(2026-10-04 真机 bug)

    /**
     * 键盘表面划 ⇒ 提交线是档位的「死区外提交距离」(8px),**不是**行宽比例。
     *
     * 这条是被真机 bug 逼出来的:`pageDragEnd` 曾经**先**把 `externalDrive` 清成 false
     * 再分发到 `endPageDrag`,于是松手时读到了 `行宽 × 0.22`(Titan 2 上 302px)。
     * 症状是「划过去又翻回来」的多次振铃 + 快甩失效。
     */
    @Test
    fun `键盘表面划用死区外提交距离而不是行宽比例`() {
        val fromKeyboard = pageCommitLine(
            externalDrive = true, externalPx = 8f, commitFrac = 0.22f, viewW = 1372f,
        )
        assertEquals("键盘表面驱动应直接用档位值", 8f, fromKeyboard, 0f)
    }

    /** 候选行自己划 ⇒ 行宽 × 比例。两条量纲不能互相污染。 */
    @Test
    fun `候选行自己划用行宽比例`() {
        val fromRow = pageCommitLine(
            externalDrive = false, externalPx = 8f, commitFrac = 0.22f, viewW = 1372f,
        )
        assertEquals("候选行驱动应走行宽比例", 1372f * 0.22f, fromRow, 0.01f)
    }

    /**
     * 档位 ≤ 0(只可能是「注入没成功」)时**退回行宽比例**,不是夹到 1px。
     *
     * 这条第一版写反了 —— 我按注释里的意图断言「夹到 1px」,测试红了。
     * 查清之后是**注释错、代码对**:两种退化方式里,退回行宽比例(仍能翻页)
     * 比夹到 1px(极端灵敏)安全。理由写在 [externalCommitFromBase] 的注释里。
     *
     * 记这一条是因为它很容易被当成「代码有 bug」而「改代码去迁就注释」。
     */
    @Test
    fun `档位不大于 0 时退回行宽比例而不是极端灵敏`() {
        for (px in floatArrayOf(0f, -5f)) {
            assertEquals(
                "externalPx=$px 应退回行宽比例",
                1372f * 0.22f,
                pageCommitLine(externalDrive = true, externalPx = px, commitFrac = 0.22f, viewW = 1372f),
                0.01f,
            )
        }
        // 正数才走夹值路径,且下界是 1px
        assertEquals(
            1f,
            pageCommitLine(externalDrive = true, externalPx = 0.4f, commitFrac = 0.22f, viewW = 1372f),
            0f,
        )
    }

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
        // **只比阻尼比,不比整个 spec**:response 现在是按行程算出来的
        // (`PageTiming.withResponse`),整个 spec 相等这个断言已经不成立。
        // 方向语义由阻尼比承载,那才是这条要守的东西。
        assertEquals(
            "提交应该用 [PAGE_COMMIT] 的阻尼比",
            SpringSpec.PAGE_COMMIT.dampingRatio, commitSpec.dampingRatio, 0f,
        )
        assertEquals(
            "收回应该用 [PAGE_ROLLBACK] 的阻尼比",
            SpringSpec.PAGE_ROLLBACK.dampingRatio, rollbackSpec.dampingRatio, 0f,
        )
        assertTrue(
            "提交必须**临界阻尼**:单调逼近、永不穿越目标(用户 2026-10-04「不要过衝 也不要回彈」),实际 ${commitSpec.dampingRatio}",
            commitSpec.dampingRatio == 1f,
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
     * 所以判据写成:**欠阻尼 ⇒ 过冲 > 0;ζ ≥ 1 ⇒ 过冲 == 0**。
     *
     * 2026-10-04 起提交也改成 ζ=1.0(用户「不要过衝 也不要回彈」),所以**两支都是 0**。
     */
    @Test
    fun `两支都零过冲`() {
        fun overshootOf(z: Float): Float =
            if (z >= 1f) 0f
            else kotlin.math.exp(-Math.PI.toFloat() * z / kotlin.math.sqrt(1f - z * z))

        // 从 pageSettleFor 现取,而不是复用另一个测试里的局部变量
        // —— Kotlin 里方法之间的局部变量不可见(第一版这么写编译不过)。
        val commit = overshootOf(pageSettleFor(true, FULL_PAGE).second.dampingRatio)
        val rollback = overshootOf(pageSettleFor(false, FULL_PAGE).second.dampingRatio)
        assertEquals(
            "提交也是零过冲(ζ=${SpringSpec.PAGE_COMMIT.dampingRatio} 临界阻尼,单调逼近),实际 $commit",
            0f, commit, 0f,
        )
        assertEquals("收回(ζ=${SpringSpec.PAGE_ROLLBACK.dampingRatio})必须零过冲", 0f, rollback, 0f)
    }

    /**
     * 提交弹簧的**过冲必须小到看不见**(2026-10-04 用户两次「降低翻頁動畫彈性」)。
     *
     * 钉的是**过冲的绝对像素**,不是阻尼比本身 —— 因为过冲比与行程无关,
     * 真正决定「弹不弹」的是 `比例 × 行程`。1440px 是 Titan 2 的屏宽,
     * 也是一次翻页的完整行程。
     *
     * 三轮下调在 1440px 上的过冲:ζ 0.72 → **55.3px** / 0.9 → 2.2px / 0.95 → 0.1px。
     * 55.3px 是肉眼明显的回弹;而它在落点 bug 修好之前是无害的 ——
     * 那时行程只有一百多 px,过冲只有 3.8px。
     * ⇒ 「弹性变强」不是有人调了参数,是**行程变长把一个隐性比例放大了**。
     *
     * ## 上界为什么收到 0.5px
     *
     * 第一版写的是 4px(那时 ζ=0.9、实测 2.2px,刚好能过)。用户接着要求
     * 「繼續降低」之后 4px 就成了**自动通过**的阈值 —— 一次真实下调反而测不出来。
     * 0.5px 钉的是「亚像素、等于没有」,ζ=0.93(0.51px)就会红。
     */
    @Test
    fun `提交的过冲严格为零`() {
        // ζ=1.0 的解是纯指数衰减,**永不超过 1**(峰值就是 1),所以过冲恒为 0。
        // 这条现在不是「小于 0.5px」而是**严格等于 0** —— 用户要的是「不要过衝」,
        // 不是「过衝小到看不见」。
        val z = SpringSpec.PAGE_COMMIT.dampingRatio
        assertEquals("提交必须是 ζ=1.0", 1f, z, 0f)
        val overshoot = if (z >= 1f) 0f
            else kotlin.math.exp(-Math.PI.toFloat() * z / kotlin.math.sqrt(1f - z * z))
        assertEquals("提交在 1440px 行程上的过冲必须是 0,实际 ${overshoot * FULL_PAGE}px", 0f, overshoot * FULL_PAGE, 0f)
    }

    /**
     * 停在哪一档:**不该为了「彻底去弹性」跳到 ζ=1.0**。
     *
     * ζ=1.0(临界阻尼)是所有不过冲曲线里**最慢**的一条 —— 为了零弹性接受
     * 更慢的收敛不划算。而 0.95 与 1.0 的过冲都已在 0.1px 以下,视觉上没区别,
     * 所以 ζ=1.0 买不到任何额外收益。
     *
     * 这条同时挡住另一个方向:ζ 再往上推(0.97+)过冲已经小于 0.01px,
     * 参数会进入**无法用肉眼验证**的区域 —— 后人看不懂就会随手改回去。
     */
    @Test
    fun `提交就是临界阻尼 不多不少`() {
        // 2026-10-04 用户「非線性動畫、到位不要过衝也不要回彈」——
        // 满足这两条的曲线**只有临界阻尼**:仍是缓出的非线性曲线,但单调逼近。
        // 调成 <1 会过冲,调成 >1 会爬行,都不是「到位就定」。
        assertEquals(1f, SpringSpec.PAGE_COMMIT.dampingRatio, 0f)
    }

    /**
     * 收回**不能**也用临界阻尼 —— 它是「放弃」,要比提交更干脆。
     *
     * 两支现在只差阻尼比:提交 1.0(单调到齐)、收回 1.9(过阻尼,到得更快)。
     */
    @Test
    fun `收回用过阻尼且比提交更干脆`() {
        assertTrue(
            "收回应是过阻尼(放弃要干脆),实际 ${SpringSpec.PAGE_ROLLBACK.dampingRatio}",
            SpringSpec.PAGE_ROLLBACK.dampingRatio > 1f,
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
        assertTrue(SpringSpec.PAGE_ROLLBACK.dampingRatio != 1f)
    }

    // ---------------- 拖动中途反向的镜像闩锁(2026-10-04 真机报「滑動還是過沖」)

    /**
     * 位移只有几 px 时**不许镜像** —— 那会让画面一帧跳将近整页。
     *
     * 手指在 `downX` 附近抖 5px:`pageSlide` 5 → 镜像 → `full - 5` = 1435。
     * 屏幕上读作「翻页反复回弹好几次」,而这发生在**拖动阶段**,
     * 弹簧还没起跑,所以调阻尼比完全没用。
     */
    @Test
    fun `位移没到提交距离时反向不镜像`() {
        assertFalse(
            "位移 5px、提交线 8px 时镜像就是跳一整页",
            allowPageMirror(pageSlide = 5f, commitPx = 8f, mirrored = false),
        )
    }

    /** 位移已过提交线 ⇒ 允许镜像(那是「改主意了」,镜像保住画面连续)。 */
    @Test
    fun `位移过了提交距离 允许镜像一次`() {
        assertTrue(allowPageMirror(pageSlide = 300f, commitPx = 8f, mirrored = false))
    }

    /**
     * 一次手势**只镜像一次**。
     *
     * 镜像后 `pageSlide` 变大,再反向就又满足「过了提交线」——
     * 没有闩锁就会在 `p` 与 `full - p` 之间来回,正是那个「反覆回弹」。
     */
    @Test
    fun `一次手势只镜像一次`() {
        assertFalse("镜像过之后不许再镜像", allowPageMirror(pageSlide = 300f, commitPx = 8f, mirrored = true))
        assertFalse("镜像过之后即使位移很大也不许再镜像", allowPageMirror(pageSlide = 1400f, commitPx = 8f, mirrored = true))
    }

    /** 边界:正好等于提交线算「没过」(位移刚到提交线,镜像的语义还不成立)。 */
    @Test
    fun `正好等于提交线不算过`() {
        assertFalse(allowPageMirror(pageSlide = 8f, commitPx = 8f, mirrored = false))
        assertTrue(allowPageMirror(pageSlide = 8.5f, commitPx = 8f, mirrored = false))
    }
}
