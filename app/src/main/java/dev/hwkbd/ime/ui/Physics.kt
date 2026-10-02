package dev.hwkbd.ime.ui

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 物理参数:把"手感语言"翻译成引擎要的数字,并把**落点判定**留在纯函数里。
 *
 * ## 为什么需要这一层
 *
 * 真正的积分交给 `androidx.dynamicanimation`(官方 `SpringAnimation` / `FrictionAnimation`,
 * 与 Compose 的 spring 同源,见 `app/build.gradle.kts` 的理由)。但官方类是 Android 依赖,
 * **JVM 单测跑不了** —— 本项目的动画手感全靠单测钉着(翻页、橡皮筋、飞字都有回归测试)。
 *
 * 所以拆成两半:
 * - **纯这一半**:参数的物理含义、落点该在哪、速度该不该丢 —— 这些是"手感对不对"的判据,
 *   全部可 JVM 单测;
 * - **Android 那半**([SpringDriver] / [FrictionDriver]):只负责把纯参数喂给官方引擎并逐帧回调。
 *
 * 换句话说:**能被测的都留在纯函数里,不能测的只做搬运。**
 *
 * ## 参数为什么用 (response, dampingRatio) 而不是 (stiffness, damping)
 *
 * `stiffness`/`damping` 的量纲要人脑换算,调不出"大概多久到"。`response` 是**无阻尼自然
 * 周期(秒)**,直觉上就是"多久到附近";`dampingRatio` 1.0 = 临界阻尼。
 * 默认一律**临界阻尼**:翻页/橡皮筋/键帽过冲读起来都像 bug,宁可慢一点也不回弹。
 */
internal data class SpringSpec(
    /** 无阻尼自然周期(秒):大致"多久到附近"。0.34 ≈ 原先 340ms 补完动画的手感。 */
    val response: Float,
    /** 阻尼比:1 = 临界(不过冲、最快到齐);< 1 回弹过冲;> 1 拖沓。 */
    val dampingRatio: Float = 1f,
    val mass: Float = 1f,
) {
    init {
        require(response > 0f) { "response 必须为正,现在是 $response" }
        require(mass > 0f) { "mass 必须为正,现在是 $mass" }
        require(dampingRatio >= 0f) { "dampingRatio 不能为负,现在是 $dampingRatio" }
    }

    private val omega: Float get() = 2f * Math.PI.toFloat() / response

    /** 刚度 `k = m·ω₀²` —— [SpringSpec] → `SpringAnimation.stiffness`。 */
    val stiffness: Float get() = mass * omega * omega

    /** 阻尼 `c = 2ζ·√(km)` —— [SpringSpec] → `SpringAnimation.damping`。 */
    val damping: Float get() = 2f * dampingRatio * sqrt(mass * stiffness)

    /** 该不该过冲(用于单测断言与调用方注释,不影响积分)。 */
    val overshoots: Boolean get() = dampingRatio < 1f

    companion object {
        /**
         * 翻页补完/收回:**临界阻尼**、0.34s 响应。
         *
         * 响应取 0.34s 是为了**手感不变**——原先 [DragGesture.SETTLE_MS] 就是 340ms,
         * 换引擎不该顺带换快慢,否则真机分不清"修好了"还是"变成另一个动画了"。
         */
        val DEFAULT = SpringSpec(response = 0.34f)

        /**
         * 橡皮筋弹回:**过阻尼**([dampingRatio] 2.4),不回弹。
         *
         * 越界回弹若用欠阻尼会来回晃,而"撞到头"本来就该是"顶住了又滑回来",不是弹簧床。
         * 响应略快(0.22s),撞墙的确认感要跟手。
         */
        val RUBBER_BACK = SpringSpec(response = 0.22f, dampingRatio = 2.4f)

        /**
         * 键帽进退场([L4PanelView]):过阻尼 1.6,响应 0.30s。
         *
         * 不过冲是硬要求(键帽弹一下就荒谬),但比 [RUBBER_BACK] 稍缓一点,读得出"它落了位"。
         */
        val CAP_MOVE = SpringSpec(response = 0.30f, dampingRatio = 1.6f)
    }
}

/**
 * 落点判定:松手那一刻该收敛到哪。**纯函数**,这是惯性手感唯一需要自己定的事。
 *
 * ## 模型:匀减速 + 库仑摩擦
 *
 * 手指以初速 `v₀` 离开,受恒定阻尼 `a`(px/s²)减速:
 * ```
 * v(t) = v₀ − a·t        s(t) = v₀·t − ½·a·t²        t_stop = v₀/a        s_stop = v₀²/(2a)
 * ```
 * 这与 `FrictionAnimation` 用的摩擦模型同源(官方那套是 2D 且带边界反弹,一维情形下等价)。
 *
 * ## 为什么不越过 target
 *
 * 惯性再大也只把这一页补完,不连翻两页。刻意的:连翻只在"明确快速连划两次"的场景下
 * 由两次手势各翻一页表达,单次手势翻两页会让用户失去"翻到哪了"的把握。
 */
internal object Landing {
    /** 减速度大小(px/s²):≈ 12000 —— 对应"快速划过一行约 250ms 停"。 */
    const val DECEL = 12000f

    /** 速度低于它就视为"已经停了",不再做惯性补完。 */
    const val MIN_VELOCITY = 120f

    /** 时长下限/上限(ms):下限防闪,上限防一次猛甩卡住界面。**只给还没迁到弹簧的动画用**。 */
    const val MIN_MS = 90L
    const val MAX_MS = 520L

    /** 从初速出发的总位移(px):`s = v²/(2a)`。 */
    fun stopDistancePx(v0: Float): Float = abs(v0) * abs(v0) / (2f * DECEL)

    /**
     * 从初速出发的停止时长(ms):`t = v0 / a`。
     *
     * **只给还没迁到弹簧的动画用**(飞字、候选词替换)。翻页 / 橡皮筋 / L5 横滑 / 键帽进退场
     * 都已走 [SpringChannel],时长由弹簧按行程与初速自己算 —— 固定时长正是
     * 「松手顿一下」「打断跳变」的来源,别再往回加新的固定时长动画。
     */
    fun stopDurationMs(v0: Float): Long =
        (abs(v0) / DECEL * 1000f).toLong().coerceIn(MIN_MS, MAX_MS)

    /**
     * 松手时该落到哪:当前位移 + 惯性还能滑的那一段,夹在 `[from, target]` 之间。
     *
     * @param from 当前位移(px)
     * @param target 目标位移(px)
     * @param v0 松手瞬间速度(px/s,带符号;取绝对值判大小,**符号须与 `target - from` 同向**才生效)
     */
    fun project(from: Float, target: Float, v0: Float): Float {
        val remaining = target - from
        if (remaining == 0f) return target
        val coasting = v0 * remaining > 0f // 速度方向要朝着目标,否则不算惯性
        val glide = if (coasting && abs(v0) >= MIN_VELOCITY) stopDistancePx(v0) else 0f
        val landing = from + if (remaining > 0f) glide else -glide
        return landing.coerceIn(
            minOf(from, target),
            maxOf(from, target),
        )
    }
}

/**
 * [Landing] 是**唯一还需要自己定的物理量**:松手那一刻该收敛到哪。
 * 其余(积分、变 dt 稳定、打断保速)全交给 `androidx.dynamicanimation`,见 [SpringChannel]。
 */
