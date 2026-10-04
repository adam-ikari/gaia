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
         * 翻页补完/收回:**欠阻尼**([dampingRatio] 0.72)、0.28s 响应。
         *
         * ## 为什么从临界阻尼改过来(2026-10-02 用户「弹性阻尼太强」「翻页成功的动画反而不明显」)
         *
         * 原来是 `response = 0.34f` + ζ = **1.0**(临界)。临界阻尼是**所有不过冲曲线里最慢的一条** ——
         * 同样的行程,它比任何欠阻尼方案都更"贴着"目标地磨,读起来就是黏、粘手、弹性不足。
         * 而且它把初速的影响压到最小(ζ=1 时初速几乎立刻被阻尼吃掉),于是松手快慢一个样。
         *
         * 现在 ζ = 0.72:允许**一次**轻微过冲。翻页的过冲量约 `exp(-πζ/√(1-ζ²))` ≈ **3.4%**,
         * 一整页 1440px 也就 50px,读起来是"啪地到位",不是"来回晃" —— 这正是"翻页成功"那一下该有的存在感。
         *
         * **过冲是有意保留的**,不是为了好看:翻页是一次**确认动作**,确认需要一点冲量才不像卡顿。
         * 真正的风险是"来回晃两三次",那个 ζ < 0.5 才发生;0.72 只有一次回弹且立刻停。
         *
         * 响应 0.34 → 0.28:临界阻尼本来就慢,改欠阻尼后可以再收一点,手指离开到画面停更连贯。
         */
        val DEFAULT = SpringSpec(response = 0.28f, dampingRatio = 0.72f)

        /**
         * 翻页**提交**(滑到新页那一页走完):欠阻尼 0.95,响应 0.26s。
         *
         * ## 0.72 → 0.9 → 0.95 的三轮下调(2026-10-04 用户两次「降低翻頁動畫彈性」)
         *
         * 1440px 行程上的过冲:55.3px → 2.2px → **0.1px**。
         *
         * ## 为什么停在 0.95 而不是 1.0
         *
         * 这两档的**过冲都已经是 0.1px 以下,视觉上没有区别**。区别在速度:
         * ζ=1.0(临界阻尼)是所有不过冲曲线里**最慢**的一条,而 0.95 仍比它快。
         * 所以「彻底去弹性」在这条曲线上买不到任何额外收益,只会换来更慢的收敛。
         *
         * 反过来也别再往 0.97 以上走:过冲已经小于 0.01px(亚像素),再调只是把
         * 参数推到无法用肉眼验证的区域,一旦有人看不懂就会以为它在 1.0 附近而随手改回去。
         *
         * ## 0.72 那个值错在哪(2026-10-04 第一次「降低翻頁動畫彈性」时定位)
         *
         * **0.72 那个值是在「行程只有一百多 px」的前提下定的,前提后来变了。**
         * 过冲比例 `exp(-πζ/√(1-ζ²))` 与**行程无关**,但**过冲的绝对像素 = 比例 × 行程**:
         *
         * | ζ | 过冲% | 100px 行程 | 1440px 行程 |
         * | --- | --- | --- | --- |
         * | 0.72 | 3.84% | 3.8px(肉眼为零) | **55px**(明显回弹) |
         * | 0.90 | 0.15% | 0.2px | **2.2px**(肉眼接近零) |
         *
         * 落点那个 bug 修好之前,松手时 `pageSlide` 离落点只有一百多 px,
         * 所以 3.84% 是无害的 —— 当年的注释甚至写着「一整页 1440px 也就 50px,
         * 读起来是啪地到位」,那是在**行程从未达到整页**的前提下做的估算。
         * 修好之后行程真的是 1440px,同一个 3.84% 就变成 55px 的回弹。
         *
         * ⇒ **「弹性太强」不是新引入的参数,是修复把一个隐性参数暴露出来了。**
         *
         * 响应 0.26s 比 [DEFAULT] 略短:提交这一段常常是**长行程**
         * (松手时 `pageSlide` 可能才几十 px,要一次走完 1440px),短一点更利落。
         */
        val PAGE_COMMIT = SpringSpec(response = 0.26f, dampingRatio = 0.95f)

        /**
         * 翻页**收回**(滑回原页):过阻尼 1.9,响应 0.24s。
         *
         * 与 [PAGE_COMMIT] 方向相反 —— 与 [FLY_BACK](放弃)/[RUBBER_BACK](撞头)
         * 属于同一类语义:**没成功**。所以宁可靠近阻尼也别过冲。
         *
         * 比 [RUBBER_BACK](2.4)稍缓一点:橡皮筋是"撞墙",要硬;收回是"我自己滑回去",
         * 太硬会读成"被弹回来",像不是自己做的决定。
         */
        val PAGE_ROLLBACK = SpringSpec(response = 0.24f, dampingRatio = 1.9f)

        /**
         * 橡皮筋弹回:过阻尼([dampingRatio] 2.4)、0.22s。
         *
         * **这里与翻页相反,必须过阻尼**:越界回弹若欠阻尼会来回晃,而"撞到头"本来就该是
         * "顶住了又滑回来",不是弹簧床。响应略快(0.22s),撞墙的确认感要跟手。
         */
        val RUBBER_BACK = SpringSpec(response = 0.22f, dampingRatio = 2.4f)

        /**
         * 键帽进退场([L4PanelView]):过阻尼 1.6,响应 0.30s。
         *
         * 不过冲是硬要求(键帽弹一下就荒谬),但比 [RUBBER_BACK] 稍缓一点,读得出"它落了位"。
         */
        val CAP_MOVE = SpringSpec(response = 0.30f, dampingRatio = 1.6f)

        /**
         * 飞字**起飞**(上滑选字):欠阻尼 0.72,响应 0.22s。
         *
         * 与 [DEFAULT] 同档但更快 —— 飞字是**确认动作**(词要上屏),到位那一下必须干脆,
         * 慢半拍会让"我选中了"这件事显得没发生。响应 0.22s 比翻页的 0.28s 短,
         * 因为它没有"跟手"阶段:松手就已经定了,剩下的只是把结果演出来。
         */
        val FLY_UP = SpringSpec(response = 0.22f, dampingRatio = 0.72f)

        /**
         * 候选词替换入场:ζ = 0.9、响应 0.18s。
         *
         * 为什么不是整 1.0:候选栏只有 48dp 高,动画幅度是 7px 上移 + 缩放 + 淡入,
         * 幅度刻意很小 —— 这种量级**本来就不该有过冲**,但临界阻尼(ζ=1)是所有不过冲
         * 曲线里最慢的一条,会让"敲一个字母候选换一下"显得黏。0.9 的过冲量约
         * `exp(-πζ/√(1-ζ²))` ≈ **0.15%**,7px 上移上是 0.01px,肉眼为零,
         * 而响应比临界快了近三成。属于"既要不过冲、也不要最慢那个解"。
         */
        val SWAP_IN = SpringSpec(response = 0.18f, dampingRatio = 0.9f)

        /**
         * 飞字**放弃淡回**:过阻尼 1.8,响应 0.24s。
         *
         * 方向与 [FLY_UP] 相反 —— 放弃是"没选中",不该有到位感,更不该来回晃。
         * 用户看到的是"词滑回去了",所以宁可靠近阻尼也别过冲。
         */
        val FLY_BACK = SpringSpec(response = 0.24f, dampingRatio = 1.8f)
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
