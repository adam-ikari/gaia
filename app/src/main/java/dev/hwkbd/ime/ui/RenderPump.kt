package dev.hwkbd.ime.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * 渲染请求的**合并器**:一轮里的多次请求合成一次渲染。
 *
 * **为什么需要**:一次按键事件里状态机会请求多次渲染(`beginHold` 一次、敲击落定再一次、
 * 组词刷新再一次),每次都是一整趟 `renderStatus` + 候选栏 + L4/L5 + 状态行两态切换。
 * 中间那几帧是**没人看得到的中间态**——既是白费的活,也是一类闪烁的来源
 * (画出去又改回来,用户只看到闪)。合并掉,一次按键只渲染一次**终态**。
 *
 * **为什么是 Flow 而不是 `Handler.post` + 一个 dirty 标志**:两者效果一样,但这里用
 * `Channel(CONFLATED)` + `yield()` 把"合并"这件事交给协程的结构化并发表达:
 * - [CONFLATED] 保证**不丢信号**——一轮里 N 次请求至少留下一次(渲染不能被"优化"掉);
 * - [run] 循环里那个 [yield] 是**合并的关键**:收到请求后先挂起,让同一轮里的其它请求
 *   汇进 conflated 通道,再统一渲染一次。没有它,收请求时协程就在当前栈上直接跑完
 *   (主线程 + `Dispatchers.Main.immediate` 会内联恢复),**合并等于没做**。
 *
 * **只承载视觉渲染**:组词区往 `InputConnection` 的写入**不在这里**(见 `ImeService.syncComposing`),
 * 那一步延后会让 `onUpdateSelection` 看到对不上的组词区而把组词丢掉。
 *
 * 生命周期:[ImeService] 在 `onCreate` 起 [run](在它自己的 `CoroutineScope` 里),
 * `onDestroy` 取消作用域,循环随之结束。
 */
class RenderPump(private val render: () -> Unit) {

    /** conflated:一轮里的多次请求合并成一次;缓冲 1 就够,满了丢最旧的(反正是"再画一次")。 */
    private val requests = Channel<Unit>(Channel.CONFLATED)

    /** 请求一次渲染(任何线程安全:conflated 通道的 [trySend] 不阻塞)。 */
    fun request() {
        requests.trySend(Unit)
    }

    /** 挂起收集:每收到一轮就跑一次 [render]。取消作用域即结束。 */
    suspend fun run() {
        for (ignored in requests) {
            // 合并的**两半**,少一半都不成立(2026-09-30 写测试时才发现,见下):
            //
            // ① [yield]:收到请求后先挂起,让同一轮里的其它请求汇进来。少了它,主线程 +
            //    `Main.immediate` 会当场内联跑完渲染,**合并等于没做**。
            //
            // ② **drain**:光有 [CONFLATED] 也不够 —— 通道的 `trySend` 遇到**正在等着的接收者**
            //    会**直接交到它手上**(不经过缓冲区)。于是同一轮里的第 1 个请求走直连、
            //    后面几个才进缓冲区,结果是"一轮 N 次请求 → 2 次渲染":比不合并还难预测。
            //    所以渲染前把缓冲区里到达的信号一次吃光,一轮就只渲染一次。
            yield()
            var more = true
            while (more) more = requests.tryReceive().isSuccess
            render()
        }
    }

    /** 挂到作用域上跑(等价于 `scope.launch { run() }`,留个名字是为了调用处读起来是意图)。 */
    fun startIn(scope: CoroutineScope) {
        scope.launch { run() }
    }
}
