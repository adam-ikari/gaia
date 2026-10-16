package dev.hwkbd.ime.ime

/**
 * 按键与滑动之间的**门**:手指按在键上时、以及刚敲完键的一小段里,键盘滑动一律不接。
 *
 * ## 判据(2026-10-16 用户定案)
 *
 * 「按键 down 的时候忽略手势,up 之后 300ms 以内也忽略,并且可配置」。
 *
 * | 时刻 | 手势 |
 * |---|---|
 * | 键 DOWN、还没 UP | 不接(按住期间) |
 * | 键 UP 起 [blockMs] 内 | 不接 |
 * | 其余 | 接 |
 *
 * 所以窗口是**两段的并集**:一段是被按住的那段(长度由手指决定),一段是抬手后的固定长度。
 * 旧版只有后者,而且从 **DOWN** 起算 —— 按住不放时窗口早就过期了,而那段恰恰是
 * 「手指压在键上、表面还在报坐标」的时候。
 *
 * ## 为什么非要连着「按住那段」
 *
 * 这套输入法的物理键盘表面被固件映射成屏幕触摸:一次按键既产生 KeyEvent,也产生一条触点流。
 * 压键那一下手指会滚一下,表面就报出可观的位移 —— 距离阈值(死区 + 提交线)只能挡住其中一部分。
 * 而「这一刻手在键上」是**物理事实**,不需要拿距离去猜。
 *
 * ## 「所有的」= 三个滑动入口全挡
 *
 * | 入口 | 位置 |
 * |---|---|
 * | 键盘表面(TOUCHPAD 源)的上下/左右滑 | `ImeService.handleFlyMotion` |
 * | 候选行横滑/上滑 | [dev.hwkbd.ime.ui.CandidateBarView] |
 * | L5 行横滑 | [dev.hwkbd.ime.ui.L4PanelView](tailMode) |
 * | L6 面板横滑 | [dev.hwkbd.ime.ui.L6PanelView] |
 *
 * **点按与长按一律不挡。** 「敲一个键、紧接着点候选词上屏」是最常见的手速,
 * 一起挡掉比不挡更糟。所以三个 View 里只有滑动分支查 [swipeBlocked]。
 *
 * ## 一次收窄又收回,记在这里免得再来第三遍
 *
 * 中间收窄过一次(只剩键盘表面那一处),起因是真机报「左右滑动没有跟手动画」——
 * 那个报障成立,但**归因错了**:跟手动画没了不是范围问题,是那三个入口当时**都**在挡,
 * 一挡就整条手势不起拖动。如今「所有入口都挡」是用户的明确选择,代价是:
 * **静默窗内起手的候选行横滑,跟手动画不会出现**(整条手势被吞,不画位移)。
 * 想让这段窗口内也有跟手动画,只能改成"跟手照画、抬手不提交",那读起来是延迟,
 * 比一开始就不响应更糟 —— 见下节。
 *
 * ## 判定在手势起手那一下,整条手势记住结论
 *
 * 挡掉的手势是**整条都不跟手**(不起拖动、不画位移),而不是「跟手跟完了再弹回去」。
 * 后者读起来就是延迟 —— 画面跟着手指走、到了阈值却不翻页,用户会当成卡了再划一次,
 * 比一开始就不响应更糟。所以调用方要在 ACTION_DOWN 问一次并把结论记到手势结束。
 *
 * ## 「无论是否消费」:连透传的键也起窗
 *
 * 透传出去的键(音量、Tab、导航…)我们没消费,候选词也没因为它重排 —— 按「后果」讲
 * 本该不记。用户要的是无论消费与否都忽略滑动 —— 那次按键仍然是「用户在打字机上打字」
 * 这个动作的证据,那一刻他就以为自己在连续输入。代价只是滑动多哑一段;不记的代价是
 * 规则出现例外、以后每加一个透传键都要重新判断一次。所以照办,不设例外。
 *
 * ## 可配置,但只配「抬手后」那一段
 *
 * [blockMs] 是 `var`,由 `ImeService.applyPrefs` 从设置页读(见 `PREF_KEY_GATE_MS`)。
 * **按住那段不受它管**:那是物理判据,不是手感偏好 —— 把它调成 0 只关掉抬手后那段,
 * 「手按在键上时不动画面」这条仍然成立。
 *
 * ## 配对的容错
 *
 * - **自动重复的 DOWN 不重复计数**:[onKeyDown] 按 keyCode 记集合,同一个键的 repeat 只算一个。
 *   记数的话一次抬手消不掉,手势会一直哑到安全阀。
 * - **漏掉 UP 靠 [HELD_STALE_MS] 兜底**:漏抬常见于事件流被系统吞掉。窗口被收起那条走
 *   `onWindowHidden` → [reset]。超过阀值就当那次抬手丢了。
 * - 输入法窗口隐藏时 [reset]。
 *
 * ## 为什么单独成类
 *
 * 和 [ModifierGestures] 同一个理由:它是纯时间判定,不依赖 Android,时钟可注入,JVM 单测直接跑。
 *
 * @param now 时刻来源(单测传虚拟时钟)
 * @param blockMs 抬手后的静默窗长度(ms),可运行时改
 */
class KeyEventGate(
    private val now: () -> Long = System::currentTimeMillis,
    var blockMs: Long = DEFAULT_BLOCK_MS,
) {

    /**
     * 静默窗的到期时刻;null = 还没敲过键。
     *
     * **用 null 而不是 0 哨兵**:0 是合法时刻(单测的虚拟时钟就从 0 起步),
     * 拿 0 当"没有"会让第一次按键被算成"已经过期"。
     */
    private var blockUntil: Long? = null

    /**
     * 正被按住(收到 DOWN、还没收到 UP)的键码。
     *
     * **按码记而不是记个数**:自动重复会一路送 DOWN,记数的话一次抬手消不掉,
     * 手势会哑到 [HELD_STALE_MS] 才解开。
     */
    private val heldCodes = HashSet<Int>()

    /** 最后一次键事件的时刻:给 [HELD_STALE_MS] 的安全阀算「多久没动静了」。null = 还没敲过。 */
    private var lastKeyAt: Long? = null

    /** 记一次按键按下。同一个键的自动重复只算一次(见 [heldCodes])。 */
    fun onKeyDown(code: Int, at: Long = now()) {
        heldCodes.add(code)
        lastKeyAt = at
    }

    /**
     * 记一次按键抬起:**抬手后的静默窗从这里起算**。
     *
     * 没有配对 DOWN 的 UP 也照样起窗 —— 那次按键是发生过的(换输入框/丢事件时会出现)。
     */
    fun onKeyUp(code: Int, at: Long = now()) {
        heldCodes.remove(code)
        lastKeyAt = at
        blockUntil = at + blockMs
    }

    /**
     * 记一次**无法配对**的按键事件:屏上键帽(L4/L5/L6)的点按、以及只有一次 up 语义的输入。
     *
     * 点按没有「按住」这个概念 —— 它本身就是抬手那一刻结算的,所以只起窗、不进 [heldCodes]。
     */
    fun onKeyEvent(at: Long = now()) {
        blockUntil = at + blockMs
        lastKeyAt = at
    }

    /**
     * 此刻起手的手势要不要被挡掉。**只看此刻**,不改变状态(调用方在 DOWN 问一次并记住结论)。
     *
     * 边界是**左闭右开**:`at == blockUntil` 放行 —— 「窗口内」不含窗口末尾那一刻。
     */
    fun swipeBlocked(at: Long = now()): Boolean {
        if (heldCodes.isNotEmpty()) {
            val last = lastKeyAt
            // 漏抬兜底:太久没有任何键事件 = 那次 UP 丢了,不能把滑动永久哑掉(见 HELD_STALE_MS)
            if (last != null && at - last > HELD_STALE_MS) return false
            return true
        }
        val until = blockUntil ?: return false
        return at < until
    }

    /** 清空(输入法隐藏时调,与 [ModifierGestures.resetAll] 同步)。 */
    fun reset() {
        blockUntil = null
        heldCodes.clear()
        lastKeyAt = null
    }

    companion object {
        /**
         * 抬手后静默窗的**默认**长度(ms)。设置页可选档位见 `ImeService.PREF_KEY_GATE_MS`。
         *
         * **由用户直接给出**,不从别的常量推导 —— 它要同时满足两个互相拉扯的约束:
         * 挡住「按键之后那一下滑动」(候选词正在换,滑下去命中的是还没稳定的那一页),
         * 又不能长到把「敲完键、紧接着划一下翻页」这个正常节奏挡掉。
         *
         * 200 → **300**(2026-10-04 用户「按键事件 300 毫秒內不要響應鍵盤划動的事件」)。
         * 调大的同时把**振铃的另一个候选机制**压下去了:按键后候选正在换,
         * 200ms 内划一下可能命中还没稳定的那一页,而它与下一次按键的时序叠加
         * 就会来回翻 —— 见 [[spring-damping-direction]] 里「反复回弹」那条。
         *
         * 附近的时间常量各管各的,不要拿它们互相校准:[ModifierGestures.DEFAULT_TAP_WINDOW_MS]
         * 400 是敲击连击窗口,[ModifierGestures.DEFAULT_HOLD_MS] 300 是长按判定。
         * (本值现在也恰好是 300,与长按判定同值,但两者语义无关、也不要互相校准。)
         */
        const val DEFAULT_BLOCK_MS = 300L

        /**
         * 「按住」态的安全阀(ms):距**最后一次键事件**超过这么久,就当那次 UP 丢了。
         *
         * **它只在事件流断掉时生效**。正常按住碰不到它:字母键按住会一路自动重复(真机上约
         * 500ms 后开始,之后每几十毫秒一次 DOWN),每次都刷新计时;修饰键虽然不重复,
         * 但也没有「按住修饰键再划键盘面」这个操作。
         *
         * 取 5s 这么长,是因为它要同时顾两头:
         * - 太短会**违反用户那条判据**(按住键期间一律不接手势) —— 手指压在键上超过阀值、
         *   而系统又没把自动重复送进来时,手势就会在手还按着的时候重新接上;
         * - 太长则一旦真的漏抬,滑动会哑很久。
         *
         * 漏抬还有一道更常见的出口:窗口被收起时 [reset](`ImeService.onWindowHidden`)。
         */
        const val HELD_STALE_MS = 5_000L
    }
}
