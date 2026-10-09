package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * spec → 引擎参数的**翻译层**测试。
 *
 * ## 为什么这一层必须单独有测试(2026-10-09 的那个 bug)
 *
 * 原来换算直接写在 `toOfficial()` 里,函数返回 `SpringForce`(Android 类型)⇒
 * **JVM 单测根本编不过它**,于是那一层没有任何测试。
 *
 * 结果那一行写的是:
 * ```kotlin
 * SpringForce(mass).apply { setStiffness(this@toOfficial.stiffness); setDampingRatio(dampingRatio) }
 * ```
 * 第二个 `dampingRatio` 没限定 ⇒ 解析到 receiver 自己的 `getDampingRatio()`
 * ⇒ androidx `SpringForce` 构造时把阻尼比硬编码成 **0.5** ⇒
 * **全项目的弹簧实际都跑 ζ=0.5,spec 写的 ζ 一个都没生效。**
 *
 * 现象是「响应时间对(刚度那行限定了)、但总有点过冲」,于是历史上把 ζ 从
 * 0.72 一路调到 1.0 都没用 —— 那一串 0.1px/2.2px 是**纯函数算的**,不是真机量的。
 *
 * 现在换算拆成纯函数 [forces],于是可以钉住;Android 那一半靠 `SpringChannel`
 * 起跑时打的 `engK`/`engZ`(从引擎回读)对账。
 */
class SpringForcesTest {
    /**
     * 翻译必须**原样**带上阻尼比 —— 尤其不能是 0.5。
     *
     * 0.5 是 androidx `SpringForce()` 构造时的硬编码默认值(`DAMPING_RATIO_MEDIUM_BOUNCY`),
     * 也就是上一个 bug 实际跑的值。这里逐个常量断言"不是那个默认值"。
     */
    @Test
    fun `阻尼比要原样送出去 不能退化成引擎默认值 0_5`() {
        val all = listOf(
            "DEFAULT" to SpringSpec.DEFAULT,
            "PAGE_COMMIT" to SpringSpec.PAGE_COMMIT,
            "PAGE_ROLLBACK" to SpringSpec.PAGE_ROLLBACK,
            "FLY_BACK" to SpringSpec.FLY_BACK,
            "RUBBER_BACK" to SpringSpec.RUBBER_BACK,
        )
        for ((name, spec) in all) {
            assertEquals(
                "$name 的阻尼比必须原样翻译",
                spec.dampingRatio,
                spec.forces().dampingRatio,
                0f,
            )
            assertNotEquals(
                "$name 退化成了 SpringForce 的默认值 0.5 —— 就是 2026-10-09 那个 bug",
                0.5f,
                spec.forces().dampingRatio,
            )
        }
    }

    /**
     * 提交那一支必须是临界阻尼(ζ=1.0),这是「不要过衝也不要回彈」唯一满足的曲线。
     *
     * 单独钉一条,因为它是用户诉求本身,而不是实现细节:
     * 引擎拿到的 ζ 若不是 1.0,整页 1360px 就会有 16% ≈ 221px 的过冲(2026-10-09 真机实测)。
     */
    @Test
    fun `提交这一支送到引擎的必须是 ζ 等于 1`() {
        assertEquals(1f, SpringSpec.PAGE_COMMIT.forces().dampingRatio, 0f)
    }

    /**
     * 刚度 = `m·ω₀²`,`ω₀ = 2π/response`。
     *
     * 这条不是新断言,是把 [SpringSpec.stiffness] 的定义再用一次 ——
     * 因为翻译层一旦改写(比如忘了乘 mass),这里是最先该响的地方。
     */
    @Test
    fun `刚度按 2π除以 response 的平方翻译`() {
        val spec = SpringSpec(response = 0.6f, dampingRatio = 1f)
        val omega = (2.0 * Math.PI / spec.response.toDouble()).toFloat()
        assertEquals(omega * omega, spec.forces().stiffness, 0.01f)
    }

    /** 三个量都必须是有限正数 —— NaN 会让引擎静默不动,而不是报错。 */
    @Test
    fun `翻译结果不许出 NaN 或无穷`() {
        for (spec in listOf(
            SpringSpec.DEFAULT,
            SpringSpec.PAGE_COMMIT,
            SpringSpec.PAGE_ROLLBACK,
            SpringSpec(response = 0.28f, dampingRatio = 0f),
            SpringSpec(response = 0.6f, dampingRatio = 2f),
        )) {
            val f = spec.forces()
            assertTrue("stiffness=${f.stiffness}", f.stiffness.isFinite() && f.stiffness > 0f)
            assertTrue("dampingRatio=${f.dampingRatio}", f.dampingRatio.isFinite() && f.dampingRatio >= 0f)
        }
    }
}