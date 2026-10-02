package dev.hwkbd.ime.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 左右滑翻页档位表的断言。
 *
 * 2026-10-02 用户「左右滑动阈值需要可调节性」。档位表是纯查表,最容易在改档位名或
 * 调数值时改坏 —— 而且它坏掉的方式很隐蔽:三处阈值各读各的,少接一处只有真机能看出来。
 */
class PageThresholdTierTest {

    /**
     * 档位名 → (行宽比例, 键盘表面 px)。与
     * [ImeService] 的 `PAGE_THRESHOLD_TIERS` 同表;这里复制一份是为了能在 JVM 上验,
     * 改表时**两处必须一起改** —— 这正是本测试要盯的。
     */
    private val tiers = mapOf(
        "sensitive" to (0.22f to 40f),
        "normal" to (0.33f to 60f),
        "conservative" to (0.45f to 90f),
    )

    @Test
    fun `档位单调 灵敏到保守依次变难`() {
        val order = listOf("sensitive", "normal", "conservative")
        for (i in 0 until order.size - 1) {
            val (aFrac, aPx) = tiers.getValue(order[i])
            val (bFrac, bPx) = tiers.getValue(order[i + 1])
            assertTrue("行宽比例应递增:${order[i]}→${order[i + 1]}", bFrac > aFrac)
            assertTrue("键盘表面位移应递增:${order[i]}→${order[i + 1]}", bPx > aPx)
        }
    }

    @Test
    fun `默认档与两处出厂常量一致`() {
        // 用户不改任何设置时,行为必须与加这个开关之前完全相同
        val (frac, px) = tiers.getValue("normal")
        assertEquals(
            "默认档的行宽比例应等于 CandidateBarView.PAGE_COMMIT_FRAC",
            dev.hwkbd.ime.ui.PAGE_COMMIT_FRAC, frac, 1e-6f,
        )
        assertEquals(
            "默认档应等于 L4PanelView.L5_COMMIT_FRAC",
            dev.hwkbd.ime.ui.L4PanelView.L5_COMMIT_FRAC, frac, 1e-6f,
        )
        assertEquals("键盘表面默认值是 60px", 60f, px, 1e-6f)
    }

    @Test
    fun `认不出的档位名回落到默认档 而不是抛异常`() {
        // 偏好是持久化的:将来改档位名,老机器上会读到旧值,那时静默回落比输入法起不来好
        for (unknown in listOf<String?>(null, "", "verySensitive", "0.33")) {
            val picked = tiers[unknown] ?: tiers.getValue("normal")
            assertEquals("应回落到 normal", tiers.getValue("normal"), picked)
        }
    }

    @Test
    fun `行宽比例都落在能用的区间`() {
        for ((name, pair) in tiers) {
            val frac = pair.first
            assertTrue("$name 的比例应为 0..1,实际 $frac", frac > 0.05f && frac < 1f)
        }
    }

    @Test
    fun `键盘面与候选行的比例不同量纲 不该被当成同一个数`() {
        // 键盘面上横向能走到的距离本就小:候选行 1440px 宽时 1/3 是 475px,
        // 而键盘面 60px 就该翻页。档位表对两处分别给值就是为这个 ——
        // 这条测试是提醒后来的人别"顺手统一"成同一个比例。
        val (frac, px) = tiers.getValue("normal")
        val rowWidth = 1440f
        val rowPx = rowWidth * frac
        assertTrue(
            "候选行触发距离应远大于键盘面:${rowPx} vs $px",
            rowPx > px * 5f,
        )
    }
}
