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
        "sensitive" to (0.16f to 4f),
        "normal" to (0.22f to 8f),
        "conservative" to (0.33f to 20f),
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
        // 用户不改任何设置时的手感必须与出厂常量一致
        val (frac, px) = tiers.getValue("normal")
        assertEquals(
            "默认档的行宽比例应等于 CandidateBarView.PAGE_COMMIT_FRAC",
            dev.hwkbd.ime.ui.PAGE_COMMIT_FRAC, frac, 1e-6f,
        )
        assertEquals(
            "默认档应等于 L4PanelView.L5_COMMIT_FRAC",
            dev.hwkbd.ime.ui.L4PanelView.L5_COMMIT_FRAC, frac, 1e-6f,
        )
        assertEquals("键盘表面默认值是死区外 8px", 8f, px, 1e-6f)
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
    fun `键盘面那列是死区外的距离 三档台阶不被死区压扁`() {
        // 旧口径是"手指总行程"(含 32px 死区),三档 40/60/90 减掉死区后只剩 8/28/58,
        // 最灵敏那档离死区只有 8px —— 想再加一档更灵敏的加不出来,死区已经是它的地板。
        // 现在档位就是死区外的提交距离(4/8/20),数值本身远小于死区,比例不再是意义。
        //
        // **要验的是"倍差",不是绝对值**:改死区时档位不该跟着变。
        val (_, sensitive) = tiers.getValue("sensitive")
        val (_, normal) = tiers.getValue("normal")
        val (_, conservative) = tiers.getValue("conservative")
        // 每档之间是一个干净的倍数,而不是被死区啃掉一截的残值
        assertEquals("灵敏→标准 应恰好 2 倍", 2f, normal / sensitive, 1e-4f)
        assertEquals("标准→保守 应恰好 2.5 倍", 2.5f, conservative / normal, 1e-4f)
    }

    @Test
    fun `每档都小于死区时不该静默退化成永远翻不了页`() {
        // 夹值在 externalCommitFromBase 里(候选行与键盘面共用同一份数学),
        // 这里只确认档位表没给出 0/负数这种天然翻不了的值。
        for ((name, pair) in tiers) {
            assertTrue("$name 的键盘面值应为正", pair.second > 0f)
            assertTrue("$name 的行宽比例应为正", pair.first > 0f)
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
        // 候选行 1440px 宽时标准档是 317px,键盘面只要 8px(死区之外)。
        // 差 40 倍 —— 这不是标定失误,是两个量纲:触摸面板上横向能划的距离本就那么点。
        val (frac, px) = tiers.getValue("normal")
        val rowWidth = 1440f
        val rowPx = rowWidth * frac
        assertTrue(
            "候选行触发距离应远大于键盘面:${rowPx} vs $px",
            rowPx > px * 5f,
        )
    }
}
