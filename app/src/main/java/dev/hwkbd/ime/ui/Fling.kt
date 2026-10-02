package dev.hwkbd.ime.ui

/**
 * 匀减速插值器 + 指尖速度跟踪。
 *
 * ## 这文件为什么还在(2026-10-02)
 *
 * 2026-10-02 用户「使用物理引擎重构手势和动画」之后,**积分与打断已经全部交给**
 * `androidx.dynamicanimation`(官方 `SpringAnimation`),由 [SpringChannel] 驱动。
 * 本文件只剩两样东西,而且它们都有明确的"还没迁完"标记:
 *
 * - [DECELERATE]:一个纯插值器,**只给还没迁到弹簧的两个动画**用 ——
 *   飞字起飞([CandidateBarView.flyAnim])与候选词替换([CandidateBarView.swapAnim])。
 *   那两处的时长仍是固定的,所以还需要一条时间曲线;迁完之后本文件只剩 [SpeedTracker]。
 * - [SpeedTracker]:指尖速度(喂给 [Landing] 与弹簧初速)。
 *
 * ## 落点/时长/位移的物理已经搬走了(2026-10-02)
 *
 * 原来这里有 `projectLanding` / `stopDistancePx` / `stopDurationMs` / `DECEL` /
 * `MIN_VELOCITY` —— 与新加的 [Landing] **是同一个模型的两份实现**。两份并存的代价
 * 已经付过一次:改档位口径时 [CandidateBarView.pageCommitPx] 减了一次死区、
 * 而这里的抬手兜底忘了减,两处量纲对不上(2026-10-02 用户报「翻页触发不了」)。
 * 现在统一由 [Landing] 持有,本文件不再有任何落点判定。
 */
internal object Fling {

    /**
     * 减速曲线 = 匀减速的归一化位移(`2p − p²`)。
     *
     * Android 的 `DecelerateInterpolator` 是 `1 − (1−p)^factor` 这一族,
     * **factor 必须显式给 2.0 才是 `2p − p²`** —— 默认 factor 是 1.0,那个值算出来是恒等
     * 函数(等于线性),不是减速。别用默认构造器(2026-09-30 单测实测拦下)。
     *
     * 手写而不直接用 `DecelerateInterpolator(2.0f)`:后者的 factor 语义在不同 Android
     * 版本上并不一致(factor=2 是否正好是 `2p − p²` 没有文档保证)。
     *
     * **它是一条时间曲线,只该用于"按固定时长播完"的那种动画。** 一旦换成弹簧,
     * 时间维度的插值就没有意义了 —— 空间与时间由同一个积分决定。
     */
    val DECELERATE: android.view.animation.Interpolator =
        object : android.view.animation.Interpolator {
            override fun getInterpolation(t: Float): Float {
                val p = t.coerceIn(0f, 1f)
                return 2f * p - p * p // 匀减速的归一化位移
            }
        }
}

/**
 * 纯 Kotlin 的指尖速度跟踪(不用 `android.view.VelocityTracker`,那玩意测不出 JVM 单测)。
 *
 * ## 为什么只看最近两点,而不是窗口内平均
 *
 * 惯性要的是**松手那一刻**的速度。实际手势常出现"快甩一段 → 手指停住 → 再抬手",
 * 按窗口平均算会把整段的快速度带进来,判成快甩,于是松手瞬间页面又自己滑出去半屏 ——
 * 正是"手已经停了它还在动"的怪异感(2026-09-30 用户报「翻页不跟手」时一并发现)。
 * 所以速度 = **最后一次样本与前一次的差分**,跟手指瞬时脱钩。
 *
 * 代价是抗噪差:手指几乎不动时,微小抖动会被差分放大。两个最小判据挡住它 ——
 * 位移小于 [minMovePx] 视为"已停"给 0,时间差小于 8ms 也给 0。
 * 这比做平滑更符合直觉:"停住"本来就该让惯性立刻归零。
 *
 * 消费方:[Landing.project] 的 `v0`、以及弹簧的初速度 —— 两者是同一个量。
 */
internal class SpeedTracker(private val minMovePx: Float = 2f) {

    private var prevT = -1L
    private var prevPos = 0f
    private var velocity = 0f
    private var primed = false

    /**
     * 喂一个样本。
     *
     * @param timeMs 单调递增的时间戳(用 `event.eventTime`)
     * @param pos 该时刻的**原始**坐标。必须是原始值,不能是扣过死区或进度化过的位移 ——
     *   本类自己按相邻两点差分算速度,喂错了算出来的就错(2026-09-30 真机改出来后
     *   顺手补的注释:曾经把"扣死区后的 travel"喂进来,速度整体偏小,甩不动)。
     */
    fun add(timeMs: Long, pos: Float) {
        if (prevT >= 0L) {
            val dt = timeMs - prevT
            val dx = pos - prevPos
            velocity = when {
                dt <= 0L -> 0f // 时间倒流/同刻:不给速度
                dt < 8L -> 0f // 采样太密,差分噪声压过信号
                kotlin.math.abs(dx) < minMovePx -> 0f // 手指停住了
                else -> dx / dt * 1000f
            }
        }
        prevT = timeMs
        prevPos = pos
        primed = true
    }

    /**
     * 当前速度(px/s)。只有首样本时给 0。
     *
     * **读一次就作废**:真实松手只有那一瞬的速度。第二次读(先判落点、再判时长)必须拿到 0 ——
     * 否则第二次读到的是"抬手之后又过了多久"的平均速度,越晚读越慢,动画时长会跟着抖。
     * 需要读两次就先存局部变量。
     */
    fun velocity(): Float {
        if (!primed) return 0f
        val v = velocity
        velocity = 0f
        return v
    }

    fun reset() {
        prevT = -1L
        prevPos = 0f
        velocity = 0f
        primed = false
    }
}
