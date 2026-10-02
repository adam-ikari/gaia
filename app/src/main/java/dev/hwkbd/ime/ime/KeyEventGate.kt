package dev.hwkbd.ime.ime

/**
 * 按键之后的**滑动静默窗**:刚敲过键的 [blockMs] 内不接滑动手势。
 *
 * 2026-10-02 用户定案:「按键事件 200 毫秒内不要响应键盘滑动事件」,
 * 随后补定「**无论是否消费**,后续 200 毫秒的键盘滑动事件都忽略」。
 *
 * ## 只挡滑动,点按照常
 *
 * 这是这个类存在的全部理由。**「敲一个键、紧接着点候选词上屏」是最常见的手速** ——
 * 一起挡掉比不挡更糟。所以 [swipeBlocked] 只喂给**滑动手势**的起手判定,点按路径不看它。
 *
 * ## 「无论是否消费」:连透传的键也起窗
 *
 * 透传出去的键(音量、Tab、导航…)我们没消费,候选词也没因为它重排 —— 按「后果」讲
 * 本该不记。但用户要的是**无论是否消费都忽略滑动**,理由是那次按键仍然是"用户在打字机上打字"
 * 这个动作的证据,那一刻他就以为自己在连续输入。代价只是滑动多哑 200ms,不记的代价是
 * 规则出现例外、以后每加一个透传键都要重新判断一次。所以照办,不设例外。
 *
 * ## 判定在手势起手那一下,整条手势记住结论
 *
 * 挡掉的手势是**整条都不跟手**(不起拖动、不画位移),而不是"跟手跟完了再弹回去"。
 * 后者读起来就是延迟 —— 画面跟着手指走、到了阈值却不翻页,用户会当成卡了再划一次,
 * 比一开始就不响应更糟。所以调用方要在 ACTION_DOWN 问一次并把结论记到手势结束。
 *
 * ## 为什么单独成类
 *
 * 和 [ModifierGestures] 同一个理由:它是纯时间判定,不依赖 Android,时钟可注入,JVM 单测直接跑。
 * 散进三个 View 各自记一个 `lastKeyAt` 的话,改一次窗口长度要动三处,漏一处就出现
 * 「候选栏挡了、L5 没挡」这种按区域不一致的静默窗 —— 那种不一致比没有更费劲排查。
 *
 * @param now 时刻来源(单测传虚拟时钟)
 * @param blockMs 静默窗长度
 */
class KeyEventGate(
    private val now: () -> Long = System::currentTimeMillis,
    val blockMs: Long = DEFAULT_BLOCK_MS,
) {

    /**
     * 静默窗的到期时刻;null = 还没敲过键。
     *
     * **用 null 而不是 0 哨兵**:0 是合法时刻(单测的虚拟时钟就从 0 起步),
     * 拿 0 当"没有"会让第一次按键被算成"已经过期"。
     */
    private var blockUntil: Long? = null

    /** 记一次按键事件:从这里起 [blockMs] 内起手的滑动一律不接。 */
    fun onKeyEvent(at: Long = now()) {
        blockUntil = at + blockMs
    }

    /**
     * 此刻起手的手势要不要被挡掉。
     *
     * 边界是**左闭右开**:`at == blockUntil` 放行 —— 「280 毫秒内」不含第 280ms 那一刻。
     */
    fun swipeBlocked(at: Long = now()): Boolean {
        val until = blockUntil ?: return false
        return at < until
    }

    /** 清空(输入法隐藏时调,与 [ModifierGestures.resetAll] 同步)。 */
    fun reset() {
        blockUntil = null
    }

    companion object {
        /**
         * 静默窗(ms)。
         *
         * **200 由用户直接给出**,不从别的常量推导 —— 它要同时满足两个互相拉扯的约束:
         * 挡住「按键之后那一下滑动」(候选词正在换,滑下去命中的是还没稳定的那一页),
         * 又不能长到把「敲完键、紧接着划一下翻页」这个正常节奏挡掉。
         * 附近的时间常量各管各的,不要拿它们互相校准:[ModifierGestures.DEFAULT_TAP_WINDOW_MS]
         * 400 是敲击连击窗口,[ModifierGestures.DEFAULT_HOLD_MS] 300 是长按判定。
         */
        const val DEFAULT_BLOCK_MS = 200L
    }
}
