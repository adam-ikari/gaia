package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
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
    fun `默认档都不过冲`() {
        // 翻页/橡皮筋/键帽过冲读起来都像 bug,默认一律 ≥ 临界阻尼
        for ((name, s) in listOf(
            "DEFAULT" to SpringSpec.DEFAULT,
            "RUBBER_BACK" to SpringSpec.RUBBER_BACK,
            "CAP_MOVE" to SpringSpec.CAP_MOVE,
        )) {
            assertTrue("$name 不该欠阻尼(ζ=${s.dampingRatio})", !s.overshoots)
        }
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
