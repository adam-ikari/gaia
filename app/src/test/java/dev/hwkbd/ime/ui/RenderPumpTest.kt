package dev.hwkbd.ime.ui

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 渲染合并器的时序单测(虚拟时间)。
 *
 * 钉的是 Stage 3 的那个收益:**一次按键事件里的多次渲染请求合成一次**。
 *
 * **为什么用 runTest 默认的调度器而不是 [kotlinx.coroutines.test.UnconfinedTestDispatcher]**:
 * 合并靠的是两件事——`Channel(CONFLATED)` 只留一个信号,加上 [RenderPump.run] 里那个 `yield`
 * 让渲染挂起到下一轮。`yield` 要能挂起,调度器就必须是**会排队**的那种:生产上是
 * `Dispatchers.Main.immediate`(yield 一定会 post 到下一轮主循环),测试上就是 runTest 默认的
 * `StandardTestDispatcher`。换成 unconfined 就等于"永不排队",`yield` 退化成空操作,
 * 合并行为在测试里**测不出来**(实测会退化成 3 次请求 3 次渲染)。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RenderPumpTest {

    /** 一个泵 + 它的渲染计数(泵是单例,请求与渲染必须走同一个通道才有意义)。 */
    private class Rig {
        var renders = 0
        val pump = RenderPump { renders++ }
    }

    private fun TestScope.startPump(): Rig {
        val rig = Rig()
        backgroundScope.launch { rig.pump.run() }
        return rig
    }

    /**
     * 走完一趟"收请求 → yield → 渲染":[RenderPump.run] 的 `yield` 把渲染挂起后**在同一个虚拟
     * 时刻**重新排队,所以要多冲几遍。[runTest] 的 `advanceUntilIdle()` 在这里不驱动
     * `backgroundScope` 的活(实测),`runCurrent()` 才按当前时刻把队列排干。
     */
    private fun TestScope.flush() {
        repeat(4) { runCurrent() }
    }

    @Test
    fun `同一轮里多次请求只渲染一次`() = runTest {
        val rig = startPump()
        // 模拟一次按键里的三次渲染请求(beginHold / 敲击落定 / refreshQuery 各一次)
        rig.pump.request()
        rig.pump.request()
        rig.pump.request()
        flush()
        assertEquals("一轮里的三次请求应当合成一次渲染", 1, rig.renders)
    }

    @Test
    fun `分开的轮次各渲染一次`() = runTest {
        val rig = startPump()
        rig.pump.request()
        flush()
        assertEquals(1, rig.renders)
        rig.pump.request()
        flush()
        assertEquals("第二次按键要再渲染一次", 2, rig.renders)
    }

    @Test
    fun `收集者已在跑时同轮连发多次也只渲染一次`() = runTest {
        val rig = startPump()
        rig.pump.request()
        flush()
        assertEquals(1, rig.renders)
        // **收集者已经在等**的时候连发两次(生产的常态):通道的直连交握会把第 1 个直接送到
        // 接收者手上,不经过缓冲区——没有 [RenderPump.run] 里那个 drain,这里会是 2 次渲染。
        // 这一条就是 drain 的回归测试。
        rig.pump.request()
        rig.pump.request()
        flush()
        assertEquals("同轮连发两次应当只渲染一次(合计 2)", 2, rig.renders)
        // 而且信号不会被合并掉:下一轮再来一次,仍然要渲染
        rig.pump.request()
        flush()
        assertEquals(3, rig.renders)
    }

    @Test
    fun `没有请求就不渲染`() = runTest {
        val rig = startPump()
        flush()
        assertEquals(0, rig.renders)
    }
}
