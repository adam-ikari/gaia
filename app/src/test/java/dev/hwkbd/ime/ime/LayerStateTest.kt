package dev.hwkbd.ime.ime

import dev.hwkbd.ime.keymap.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [LayerState] 的语义(2026-09-30 整条梳理 + 当天补的"借用")。
 *
 * 钉的是三件事:
 * 1. **顶层只有一种状态** —— 不是三个可空字段,并存由类型自己承载(临时层把"恢复目标"收在内部);
 * 2. **[LayerState.base] 剥掉临时层、只剩持续态**([L1] 或 [Locked])—— 只读判定
 *    (锁没锁着)要看得穿 [Hold] 与 [OneShot];数字类那个"字段给的 L2 锁"在按住 Alt 时仍读得到,
 *    "借 Sym 期间"也仍读得到锁着的 Alt;
 * 3. **借用的恢复目标**:有锁回锁,没锁回初始(用户定案「返回上一个锁定状态,没有就返回初始状态」)。
 */
class LayerStateTest {

    @Test
    fun `层号与来源`() {
        assertEquals(Layer.L1, LayerState.L1.layer)
        assertEquals(Layer.L2, LayerState.OneShot(Layer.L2, LayerState.L1).layer)
        assertEquals(Layer.L3, LayerState.Locked(Layer.L3).layer)
        assertEquals(Layer.L2, LayerState.Hold(Layer.L2, LayerState.L1).layer)
        assertEquals("L1 不用包成 OneShot", LayerState.L1, LayerState.of(Layer.L1, locked = false))
        assertEquals(LayerState.Locked(Layer.L2), LayerState.of(Layer.L2, locked = true))
        assertEquals(LayerState.OneShot(Layer.L2, LayerState.L1), LayerState.of(Layer.L2, locked = false))
    }

    @Test
    fun `base 剥掉临时层只剩持续态`() {
        assertEquals("非临时时 base 就是它自己", LayerState.Locked(Layer.L2), LayerState.Locked(Layer.L2).base)
        assertEquals(
            "按住中",
            LayerState.Locked(Layer.L3),
            LayerState.Hold(Layer.L2, LayerState.Locked(Layer.L3)).base,
        )
        // 借用的底下是锁 ⇒ base 是那个锁(这就是"锁着 Alt 借 Sym 期间仍读得到锁")
        assertEquals(
            LayerState.Locked(Layer.L2),
            LayerState.OneShot(Layer.L3, LayerState.Locked(Layer.L2)).base,
        )
        // 叠两层也一路剥到底
        assertEquals(
            LayerState.Locked(Layer.L2),
            LayerState.Hold(Layer.L2, LayerState.OneShot(Layer.L3, LayerState.Locked(Layer.L2))).base,
        )
        assertEquals(LayerState.L1, LayerState.OneShot(Layer.L2, LayerState.L1).base)
    }

    @Test
    fun `borrow 锁定留下 旧借用丢掉`() {
        // 用户定案:「临时借用返回上一个锁定状态 没有锁定状态返回初始状态」
        assertEquals(
            LayerState.OneShot(Layer.L3, LayerState.Locked(Layer.L2)),
            LayerState.borrow(Layer.L3, LayerState.Locked(Layer.L2)),
        )
        assertEquals(
            LayerState.OneShot(Layer.L2, LayerState.L1),
            LayerState.borrow(Layer.L2, LayerState.L1),
        )
        // 旧的借用已经过期(有效期只有"下一个键")⇒ 丢掉,但底下的锁留下
        val stale = LayerState.borrow(Layer.L2, LayerState.OneShot(Layer.L2, LayerState.L1))
        assertEquals(LayerState.OneShot(Layer.L2, LayerState.L1), stale)
        val overBorrow = LayerState.borrow(Layer.L3, stale)
        assertEquals("锁留下、旧借用丢掉", LayerState.OneShot(Layer.L3, LayerState.L1), overBorrow)
    }

    @Test
    fun `lockedLayer 与 oneShotLayer 看得穿临时层`() {
        val s = ImeState()
        s.layerState = LayerState.Locked(Layer.L2)
        assertEquals(Layer.L2, s.lockedLayer)
        assertNull(s.oneShotLayer)
        s.layerState = LayerState.OneShot(Layer.L3, LayerState.L1)
        assertNull(s.lockedLayer)
        assertEquals(Layer.L3, s.oneShotLayer)
        // 借 Sym 期间:可见层是 L3,**锁着的 L2 仍然算数**(恢复目标就是它)
        s.layerState = LayerState.OneShot(Layer.L3, LayerState.Locked(Layer.L2))
        assertEquals("借用期间可见层是借的那层", Layer.L3, s.effectiveLayer)
        assertEquals("底下的锁没丢", Layer.L2, s.lockedLayer)
        assertEquals(Layer.L3, s.oneShotLayer)
        // 按住数字类那个 L2 锁:可见层是 Hold,但"锁没锁着"仍读得到(数字类的守卫靠它)
        s.layerState = LayerState.Hold(Layer.L2, LayerState.Locked(Layer.L2))
        assertEquals(Layer.L2, s.effectiveLayer)
        assertEquals(Layer.L2, s.lockedLayer)
        // 按住时底下是 L1 ⇒ 没锁
        s.layerState = LayerState.Hold(Layer.L2, LayerState.L1)
        assertNull(s.lockedLayer)
        assertNull(s.oneShotLayer)
        // Hold 是透明的:按住 Alt 期间底下那个借用还在
        s.layerState = LayerState.Hold(Layer.L2, LayerState.OneShot(Layer.L3, LayerState.Locked(Layer.L2)))
        assertEquals(Layer.L3, s.oneShotLayer)
        assertEquals(Layer.L2, s.lockedLayer)
    }
}
