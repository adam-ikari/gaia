package dev.hwkbd.ime.ime

import dev.hwkbd.ime.keymap.Layer

/**
 * 修饰键(Alt / Sym / Shift)的手势判定:**敲击计数、按住时长、解锁吸收窗**。
 *
 * **为什么单独成类**:这三样全是"时间",原先散在 [ImeController] 的七八个字段里
 * (`altTracker`/`symTracker`/`shiftTracker`、`holdAt`/`shiftHeldAt`、`suppressNextTap`/`suppressUntil`),
 * 谁改一处时间常量要顺着七八个地方找。收进一个纯 Kotlin 对象后不依赖 Android,时序可 JVM 单测。
 *
 * **它只管判定,不管显示**(2026-09-30):曾经还有一条 `settled` 流(敲击 debounce)驱动"窗口闭合
 * 时补画状态项",那是配着"敲击窗口内不画暂定态"那条规则存在的;那条规则因为纯副作用被删,
 * 流与它的收集器一起删掉了。**状态行现在是状态的纯函数**(见 `StatusRow`),不再需要时间输入。
 */
class ModifierGestures(
    private val now: () -> Long = System::currentTimeMillis,
    /** 长按阈值:按住超过它就不算"快速独立敲击"(松开时恢复按下前的状态)。 */
    val holdMs: Long = DEFAULT_HOLD_MS,
    /** 敲击窗口(双击判定):窗口内的敲击算连击,窗口外的重新从 1 开始。 */
    val tapWindowMs: Long = DEFAULT_TAP_WINDOW_MS,
) {

    /**
     * 三个修饰键。**`Layer` ⇄ 修饰键的映射只在这里一份**([of]):状态机按层取修饰键
     * (`Layer.L2` = Alt、`Layer.L3` = Sym、Shift 不对应层),别在状态机里再写一张对照表。
     */
    enum class Mod {
        ALT, SYM, SHIFT;

        companion object {
            /** 层 → 修饰键。`Layer.L1` 没有修饰键,调用处不会传它,落到 [ALT] 只是为了不留 null 分支。 */
            fun of(layer: Layer): Mod = when (layer) {
                Layer.L2 -> ALT
                Layer.L3 -> SYM
                Layer.L1 -> ALT
            }
        }
    }

    // ---- 敲击计数:每键一份(窗口外的敲击从 1 重新开始)--------------------------
    // **用 null 表示"还没有过"而不是 0 哨兵**:0 是合法时刻(单测的虚拟时钟就从 0 起步),
    // 拿 0 当"没有"会让第一次敲击被算成窗口内的第二击。
    private val tapCount = HashMap<Mod, Int>()
    private val tapLast = HashMap<Mod, Long>()

    // ---- 按住时长:down 记时刻,up 问是不是"快速独立敲击" -------------------------
    // null = 没按下过;判据是 "按住时长 < holdMs",所以"没按下"也当长按处理(返回 false)
    private val downAt = HashMap<Mod, Long>()

    // ---- 解锁/取消后的吸收窗:双击解除不该被紧随的连击重新武装 --------------------
    private val suppressUntil = HashMap<Mod, Long>()

    // ---- 敲击 ----------------------------------------------------------------

    /**
     * 记一次敲击,返回窗口内的连续次数(1 = 单击、2 = 双击)。调用方**同步**用这个返回值
     * 改状态——下一个键要立刻吃到一次性层,不能等窗口。
     */
    fun tap(mod: Mod): Int {
        val t = now()
        val last = tapLast[mod]
        val n = if (last != null && t - last <= tapWindowMs) (tapCount[mod] ?: 0) + 1 else 1
        tapCount[mod] = n
        tapLast[mod] = t
        return n
    }

    /** 当前窗口内已积累的次数(不记录新敲击)。 */
    fun peek(mod: Mod): Int = tapCount[mod] ?: 0

    /** 清空某一键的连击计数(解锁等状态跃迁后吸收紧随的连击用)。 */
    fun resetTaps(mod: Mod) {
        tapCount.remove(mod)
        tapLast.remove(mod)
    }

    // ---- 按住时长 -------------------------------------------------------------

    /** 按下:记时刻([up] 用它判"快速独立敲击")。 */
    fun down(mod: Mod, at: Long = now()) {
        downAt[mod] = at
    }

    /** 松开:返回 true = 这一次是"快速独立敲击"(按住不足 [holdMs])。没按下过就当长按。 */
    fun up(mod: Mod, at: Long = now()): Boolean {
        val from = downAt[mod] ?: return false
        return at - from < holdMs
    }

    /** 记下这次按下的时刻,让 [up] 判不出"快速"(长按收尾要恢复按下前的快照)。 */
    fun markLongPress(mod: Mod, at: Long = now()) {
        downAt[mod] = at - holdMs
    }

    // ---- 解锁吸收窗 ------------------------------------------------------------

    /** 进入吸收窗:紧随其后的同键连击被吞掉(双击解除要保持解除,不重新武装)。 */
    fun suppress(mod: Mod, ms: Long = SUPPRESS_MS, at: Long = now()) {
        suppressUntil[mod] = at + ms
    }

    /**
     * 问一次并清掉:命中本键的吸收窗返回 true(这一下要吞掉),否则返回 false。
     * 无论命中与否都清空——窗口只对**紧接着的那一下**有效。
     */
    fun consumeSuppressed(mod: Mod, at: Long = now()): Boolean {
        val until = suppressUntil.remove(mod) ?: return false
        return at < until
    }

    // ---- 全清 ------------------------------------------------------------------

    /** 全部计时归零(输入法隐藏时调,见 [ImeController.resetLayers])。 */
    fun resetAll() {
        tapCount.clear()
        tapLast.clear()
        downAt.clear()
        suppressUntil.clear()
    }

    companion object {
        /** 长按阈值(ms):`ImeController.HOLD_MS` 的家搬到这里,状态机按 [ModifierGestures.holdMs] 取。 */
        const val DEFAULT_HOLD_MS = 300L

        /**
         * 敲击窗口(ms):**两击之间**的判定窗口(不是按键时长——那是 [DEFAULT_HOLD_MS])。
         *
         * 2026-09-30 用户定案「双击时间窗口再长一点」:300 → 400。300ms 时"想双击却没锁上"
         * 的手感偏紧,400 是双击的常见区间([DEFAULT_HOLD_MS] 仍是 300:长按判定不变,
         * 两次敲击各自的按压时长照旧要短于它)。
         *
         * 窗口变长的唯一代价:两击之间停 300~400ms 也会算双击(锁定)。用户觉得宁可这样。
         * 另有一处相关但不冲突:[SUPPRESS_MS] 的解锁吸收窗仍比它短(250),只挡"手抖紧跟着再按"。
         */
        const val DEFAULT_TAP_WINDOW_MS = 400L

        /** 解锁/取消后的连击吸收窗(ms):比敲击窗口短,只挡"手抖地紧接着又按一下"。 */
        const val SUPPRESS_MS = 250L
    }
}
