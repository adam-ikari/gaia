package dev.hwkbd.ime.ui

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 惯性:松手后的减速运动(飞字起飞、翻页补完共用)。
 *
 * 2026-09-30 用户「使飞字和候选词翻页更符合实际物理效果 计算惯性质量和加速度」——
 * 在此之前松手后的动画是"固定时长 + 缓动曲线",速度与手指快慢**无关**:轻轻一推和
 * 猛地一甩补完动画一样快,像被机器推走而不是被甩出去。惯性模型要解决的就是这一条:
 * **松手瞬间的指尖速度决定之后飞多远、飞多久。**
 *
 * ## 模型:匀减速 + 库仑摩擦
 *
 * 物体以初速 `v₀` 出发、受恒定阻尼减速,加速度大小 `a`(px/s²):
 * ```
 * v(t) = v₀ − a·t                 速度线性衰减到 0
 * s(t) = v₀·t − ½·a·t²            位移
 * t_stop = v₀ / a                 停下的时刻
 * s_stop = v₀² / (2a)             总共飞多远
 * ```
 *
 * 归一化到 `p = t / t_stop`,位移占全程的比例恰好是
 * ```
 * s(t)/s_stop = 2p − p²
 * ```
 * 这正是 **DecelerateInterpolator** 的曲线。也就是说:物理模型和 Android 自带的减速插值器
 * **完全等价**,不需要自己写 `PathInterpolator`,动画时长给 `t_stop`、位移给 `s_stop` 即可。
 * 手乘曲线(如飞字完成段此前的 `s²`)会把时间曲线混进空间计算,时长/抬升/淡出三处各乘一遍,
 * 改一个牵动三个 —— 那是错的做法。
 *
 * ## 加速度 [DECEL] 为什么是这个量级
 *
 * 阻尼取"手指快速划过一行时,约 250ms 停下"来反推:`a ≈ v₀ / 0.25s`。
 * Titan 2 上一次快速横划约 3000px/s ⇒ a ≈ 12000 px/s²,与 [DECEL] 同量级。
 * 换算成物理语言:这是"物块在桌面上滑"的摩擦量级,不是弹簧回弹(回弹是减速到 0 后反向,
 * 那是另一回事,滑动的语义里没有它)。
 *
 * 手指慢甩时 `t_stop` 会很小(可能不足一帧),所以时长与位移都夹在合理区间,避免
 * "轻轻一碰就整页飞过去"或"几乎不动"。
 */
internal object Fling {

    /**
     * 减速度大小(px/s²):越大停得越快。≈ 12000 —— 对应"快速划过一行约 250ms 停"。
     */
    const val DECEL = 12000f

    /** 速度低于它就视为"已经停了",不再做惯性补完。 */
    const val MIN_VELOCITY = 120f

    /** 时长下限/上限(ms):下限防闪,上限防一次猛甩卡住界面。 */
    const val MIN_MS = 90L
    const val MAX_MS = 520L

    /**
     * 从初速 `v0` 出发的停止时长(ms):`t = v0 / a`。
     *
     * @param v0 初速(px/s,取绝对值)
     */
    fun stopDurationMs(v0: Float): Long =
        (abs(v0) / DECEL * 1000f).toLong().coerceIn(MIN_MS, MAX_MS)

    /**
     * 从初速 `v0` 出发的总位移(px):`s = v² / (2a)`。
     *
     * @param v0 初速(px/s,取绝对值)
     */
    fun stopDistancePx(v0: Float): Float = (abs(v0) * abs(v0) / (2f * DECEL))

    /**
     * 松手时该落到哪:当前位移加上惯性还能滑的那一段,夹在 `[from, target]`。
     *
     * **不越过 target** —— 惯性再大也只把这一页补完,不连翻两页。这条约束是刻意的:
     * 连翻只在"明确快速连划两次"的场景下由两次手势各翻一页表达,单次手势翻两页会让
     * 用户失去"翻到哪了"的把握。
     *
     * @param from 当前位移(px)
     * @param target 目标位移(px)
     * @param v0 松手瞬间速度(px/s,带符号;取绝对值判大小,符号须与 target-from 同向才生效)
     */
    fun projectLanding(from: Float, target: Float, v0: Float): Float {
        val remaining = target - from
        if (remaining == 0f) return target
        val coasting = v0 * remaining > 0f // 速度方向要朝着目标,否则不算惯性
        val glide = if (coasting && abs(v0) >= MIN_VELOCITY) stopDistancePx(v0) else 0f
        return (from + if (remaining > 0f) glide else -glide).coerceIn(min(from, target), max(from, target))
    }

    /**
     * 减速曲线 = 匀减速的归一化位移(`2p − p²`)。
     *
     * Android 的 [android.view.animation.DecelerateInterpolator] 就是 `1 − (1−p)^factor`
     * 这一族,**factor 必须显式给 2.0 才是 `2p − p²`** —— 默认 factor 是 1.0,那个值
     * 算出来就是恒等函数(等于线性),不是减速。别用默认构造器(2026-09-30 单测实测拦下)。
     */
    val DECELERATE: android.view.animation.Interpolator =
        object : android.view.animation.Interpolator {
            // 手写而不是 DecelerateInterpolator(2.0f):后者的 factor 语义在不同 Android
            // 版本上并不一致(factor=1 等价恒等、factor=2 是否正好是 2p−p² 没有文档保证),
            // 单测实测默认构造器返回的恒等曲线。物理模型是权威,插值器必须服从它。
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
     *   本类自己按相邻两点差分算速度,喂错了算出来的就错(这是 2026-09-30 真机改出来后
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
