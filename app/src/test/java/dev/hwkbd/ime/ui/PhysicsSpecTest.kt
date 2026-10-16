package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * 物理参数的断言 —— 2026-10-02 用户「使用物理引擎重构手势和动画」。
 *
 * 引擎本身是 `androidx.dynamicanimation`(Android 依赖,JVM 跑不了),所以本测试守住
 * **我们自己的那部分**:参数换算([SpringSpec])与落点判定([Landing])。
 * 这两个才是"手感对不对"的判据 —— 引擎只负责把它们积分出来。
 *
 * 钉住的都是**真实踩过的坑**,不是覆盖率:
 * - 阻尼比的换算写错 ⇒ 键帽/翻页回弹,读起来像 bug;
 * - 落点不夹在目标内 ⇒ 一次手势连翻两页,用户失去"翻到哪了"的把握;
 * - 速度方向与目标相反时仍算惯性 ⇒ 手已经往回拖了,松手页面还自己往前走。
 */
class PhysicsSpecTest {

    private val omegaOf: (Float) -> Float = { response -> 2f * Math.PI.toFloat() / response }

    // ------------------------------------------------------------ SpringSpec 换算

    @Test
    fun `刚度与无阻尼自然周期对得上`() {
        // k = m·ω₀²,ω₀ = 2π/response。这条错了整个"多久到"就不对
        val s = SpringSpec(response = 0.5f)
        assertEquals(s.mass * omegaOf(0.5f) * omegaOf(0.5f), s.stiffness, 1e-3f)
    }

    @Test
    fun `阻尼比 1 时恰好临界阻尼`() {
        // c = 2ζ√(km),ζ=1 ⇒ c = 2√(km)。这是"不过冲"的临界条件
        val s = SpringSpec(response = 0.34f)
        assertEquals(2f * sqrt(s.mass * s.stiffness), s.damping, 1e-3f)
    }

    @Test
    fun `response 越短刚度越大 也就是更快`() {
        // 这是"改 response 就改快慢"的前提。传反了会让调快变调慢
        val slow = SpringSpec(response = 0.6f)
        val fast = SpringSpec(response = 0.2f)
        assertTrue("更短的 response 应有更大刚度:${fast.stiffness} vs ${slow.stiffness}",
            fast.stiffness > slow.stiffness)
    }

    @Test
    fun `翻页欠阻尼 橡皮筋与键帽过阻尼 两者方向相反且各有理由`() {
        // 2026-10-02 用户「弹性阻尼太强」。原来三档全是 ζ≥1,而临界阻尼是**所有不过冲曲线里
        // 最慢的一条** —— 那不是参数没调好,是选错了类型。
        //
        // 现在两类动画要的东西本来就相反,不该用同一个值:
        // - **翻页**是一次**确认动作**,确认需要冲量才不像卡顿 ⇒ 欠阻尼,单次回弹;
        // - **橡皮筋**是"撞到头滑回来",欠阻尼会来回晃 ⇒ 过阻尼;
        // - **键帽**落位同理,弹一下很荒谬 ⇒ 过阻尼。
        assertTrue("翻页应欠阻尼,才有到位感", SpringSpec.DEFAULT.overshoots)
        assertFalse("橡皮筋应过阻尼,才不来回晃", SpringSpec.RUBBER_BACK.overshoots)
        assertFalse("键帽应过阻尼", SpringSpec.CAP_MOVE.overshoots)
        // 飞字两个方向相反:起飞是"确认",欠阻尼;放弃是"没选中",过阻尼
        assertTrue("飞字起飞是确认动作,应欠阻尼", SpringSpec.FLY_UP.overshoots)
        assertFalse("飞字放弃不该有到位感", SpringSpec.FLY_BACK.overshoots)
    }

    @Test
    fun `飞字起飞比翻页更快 因为它没有跟手阶段`() {
        // 翻页有"手指拖着走"的过程,收尾可以慢半拍;飞字是松手后就定了,
        // 剩下的只是把结果演出来 —— 慢半拍会让"我选中了"显得没发生。
        assertTrue(
            "飞字响应应短于翻页(${SpringSpec.FLY_UP.response}s vs ${SpringSpec.DEFAULT.response}s)",
            SpringSpec.FLY_UP.response < SpringSpec.DEFAULT.response,
        )
    }

    @Test
    fun `橡皮筋与键帽用弹簧时是过阻尼而不是欠阻尼`() {
        // 「撞到头」是"顶住了滑回来",不是弹簧床。欠阻尼会来回晃
        assertTrue("橡皮筋弹回应过阻尼", SpringSpec.RUBBER_BACK.dampingRatio > 1f)
        assertTrue("键帽落位应过阻尼", SpringSpec.CAP_MOVE.dampingRatio > 1f)
    }

    @Test
    fun `三档 response 都在能读的量级`() {
        // response 太长(>0.6s)读起来是"卡",太短(<0.1s)读不出来
        for ((name, s) in listOf(
            "DEFAULT" to SpringSpec.DEFAULT,
            "RUBBER_BACK" to SpringSpec.RUBBER_BACK,
            "CAP_MOVE" to SpringSpec.CAP_MOVE,
        )) {
            assertTrue("$name 的 response=${s.response}s 太长", s.response <= 0.6f)
            assertTrue("$name 的 response=${s.response}s 太短", s.response >= 0.1f)
        }
    }

    @Test
    fun `非法参数当场拒绝 而不是静默出一个奇怪的弹簧`() {
        for (bad in listOf(0f, -0.3f)) {
            var threw = false
            try {
                SpringSpec(response = bad)
            } catch (e: IllegalArgumentException) {
                threw = true
            }
            assertTrue("response=$bad 应被拒", threw)
        }
        var threwMass = false
        try {
            SpringSpec(response = 0.3f, mass = 0f)
        } catch (e: IllegalArgumentException) {
            threwMass = true
        }
        assertTrue("mass=0 应被拒", threwMass)
    }

    // ------------------------------------------------------------------ 落点判定

    @Test
    fun `慢抬手不滑行`() {
        // 速度不足 → 落点就是当前位置,不做惯性补完
        assertEquals(300f, Landing.project(300f, 1000f, v0 = 100f), 0.01f)
    }

    @Test
    fun `快抬手顺着目标方向滑行 但绝不越过目标`() {
        // v=3000px/s ⇒ 自由滑行 375px,但夹在目标内
        val landing = Landing.project(200f, 1000f, v0 = 3000f)
        assertEquals(575f, landing, 1f)
        assertTrue("不能越过目标", landing <= 1000f)
    }

    @Test
    fun `速度方向与目标相反时不产生惯性`() {
        // 用户已经往回拖了,松手不该还往前冲一段。这是"手已经停了它还在动"的另一种
        assertEquals(
            "往回甩不该往前滑",
            500f, Landing.project(500f, 1000f, v0 = -3000f), 0.01f,
        )
    }

    @Test
    fun `落点永远在起终点之间`() {
        // 穷举各种速度与起止组合:夹取一旦写漏,某组就会飞出区间
        val cases = listOf(
            Triple(0f, 1000f, 5000f),
            Triple(1000f, 0f, 5000f),
            Triple(200f, 1000f, -5000f),
            Triple(-300f, 900f, 5000f),
            Triple(500f, 500f, 5000f),
        )
        for ((from, target, v0) in cases) {
            val p = Landing.project(from, target, v0)
            val lo = minOf(from, target)
            val hi = maxOf(from, target)
            assertTrue("落点 $p 跑出 [$lo,$hi]", p in lo - 1e-3f..hi + 1e-3f)
        }
    }

    @Test
    fun `起止相同时落点不变`() {
        assertEquals(42f, Landing.project(42f, 42f, v0 = 3000f), 0f)
    }

    @Test
    fun `停止位移与速度平方成正比`() {
        // s = v²/(2a)。半速 ⇒ 四分之一距离
        assertEquals(Landing.stopDistancePx(3000f), Landing.stopDistancePx(1500f) * 4f, 1f)
        assertFalse("零速不该有滑行", Landing.stopDistancePx(0f) > 0f)
    }

    @Test
    fun `翻页过冲量必须小到读作"到位" 而不是"来回晃"`() {
        // 欠阻尼的过冲比例 = exp(-πζ/√(1-ζ²))。这个数字决定手感:
        // 一整页 1440px 上 3% ≈ 43px,读起来是"啪地到位";到 10%(≈144px)就开始像弹球了。
        val ζ = SpringSpec.DEFAULT.dampingRatio
        val overshoot = kotlin.math.exp(
            -Math.PI.toFloat() * ζ / kotlin.math.sqrt(1f - ζ * ζ),
        )
        assertTrue("过冲应明显小于 10%(实际 ${overshoot * 100}%),否则像弹球", overshoot < 0.10f)
        assertTrue("但也不能是 0 —— 完全没有冲量时翻页像卡顿(实际 ${overshoot * 100}%)", overshoot > 0.005f)
        // 换成像素说一遍:这条断言的意图是"人能感觉到",不是数学
        val pagePx = 1440f
        assertTrue(
            "一整页的过冲应在 100px 以内(实际 ${pagePx * overshoot}px)",
            pagePx * overshoot < 100f,
        )
    }

    @Test
    fun `欠阻尼只回弹一次就该停 不是弹簧床`() {
        // 判据是阻尼比落在"单次回弹"那一档(ζ ∈ 0.5..1),而不是精确值:
        // ζ<0.5 会来回好几下,ζ>1 完全不回弹。
        assertTrue(
            "翻页应当在单次回弹的区间(实际 ζ=${SpringSpec.DEFAULT.dampingRatio})",
            SpringSpec.DEFAULT.dampingRatio in 0.5f..1f,
        )
    }

    @Test
    fun `落点随速度变化是对的 被夹住时才是纯弹簧的活`() {
        // 补一句给后来人:2026-10-02 我在这里写过一条断言「慢甩与快甩的剩余距离应当相同」,
        // **那是错的**,而且错的方向掩盖了真问题。
        //
        // `Landing.project(316.8, 1440, 3000)` = 691.8 —— 惯性推出的落点**没到目标**,
        // 所以没有夹住,剩余距离自然随速度变(748 vs 1123)。**落点依赖速度是正确的**:
        // 那正是"甩得动"的物理。夹取只保证它不会冲出目标。
        val width = 1440f
        val from = width * 0.22f
        assertTrue(
            "快甩落得更远,这就是惯性",
            Landing.project(from, width, v0 = 3000f) > Landing.project(from, width, v0 = 100f),
        )
        // 但再快也不能冲出目标 —— 这是夹取唯一要保证的事
        assertEquals(
            "离谱的速度也不能越过目标",
            width, Landing.project(from, width, v0 = 100_000f), 0.01f,
        )
    }

    @Test
    fun `候选词替换要不过冲 但也别用最慢的那个解`() {
        // 候选栏 48dp 高,动画幅度只有 7px 上移 + 缩放 + 淡入 —— 这种量级本来不该过冲。
        // 但临界阻尼(ζ=1)是所有不过冲曲线里**最慢**的一条,会让"敲一个字母候选换一下"发黏。
        // 0.9 的过冲量 exp(-πζ/√(1-ζ²)) ≈ 0.15%,7px 上移上是 0.01px,肉眼为零。
        val ζ = SpringSpec.SWAP_IN.dampingRatio
        val overshoot = kotlin.math.exp(-Math.PI.toFloat() * ζ / kotlin.math.sqrt(1f - ζ * ζ))
        assertTrue("候选词入场不该过冲(实际 ${overshoot * 100}%)", overshoot < 0.005f)
        assertTrue("但响应要短,否则每次敲字母都黏(实际 ${SpringSpec.SWAP_IN.response}s)",
            SpringSpec.SWAP_IN.response < 0.25f)
    }

    @Test
    fun `低速区有一道门槛 不然轻碰一下就整页飞过去`() {
        // 门槛在 [Landing.project] 里(它判断"要不要用惯性"),**不在** stopDistancePx ——
        // 后者是纯平方函数,任何速度都给一个正数。写测试要对准真正做判断的那一层。
        val justUnder = Landing.MIN_VELOCITY - 1f
        assertEquals(
            "刚低于门槛不该滑行",
            0f, Landing.project(200f, 1000f, v0 = justUnder) - 200f, 1e-3f,
        )
        assertTrue(
            "刚高于门槛就该滑行",
            Landing.project(200f, 1000f, v0 = Landing.MIN_VELOCITY + 1f) > 200f,
        )
    }
}
