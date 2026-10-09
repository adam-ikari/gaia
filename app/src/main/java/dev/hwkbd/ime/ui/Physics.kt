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
         * 翻页**提交**(滑到新页那一页走完):**临界阻尼 ζ=1.0**。
         *
         * 时长**不写死** —— 见 [PageTiming]:翻页的剩余行程能从 100px 到 1440px,
         * 固定时长必然要么「短行程黏住」要么「长行程飞过去」。
         *
         * ## 2026-10-09:下面那段「0.72 → 0.9 → 0.95」的调参史**全部作废**
         *
         * 那一串数字(1440px 上过冲 55.3px → 2.2px → 0.1px)是
         * **用 spec 里的 ζ 在纯函数里算出来的**,从来没在真机上量过。
         * 而 `toOfficial()` 里那个没写全限定的 `dampingRatio` 解析到了
         * `SpringForce` 自己的 getter —— 引擎实际跑的一直是它构造时硬编码的 **0.5**。
         *
         * ⇒ **spec 写的 ζ 从来没生效过,不管它是 0.72 还是 0.95。**
         * 真机实测(整页 1360px 补完,spec 写 ζ=1.0):
         *
         * | 量 | spec 写的 | 引擎手上(`getDampingRatio()` 回读) | 轨迹反解 |
         * |---|---|---|---|
         * | stiffness | 109.66 | 109.66 ✓ | ω₀=10.58(响应 0.594s)✓ |
         * | dampingRatio | 1.0 | **0.5** | **ζ=0.50,过冲 16.3% = 221px** |
         *
         * 修好之后同一场景 `ring:` 一条都没有(零穿越 = 零过冲),
         * `SpringChannel` 起跑行的 `engZ=1.0`,`page-interrupt` 场景转绿。
         *
         * **教训比结论重要**:那一整轮调参是在**调一个没接上的参数**。
         * 判据用的是「spec 自己声称的值」,而不是「引擎真正收到的值」——
         * 这类量相邻、看着一致、永远对不上账。详见 `SpringForcesTest`。
         *
         * ## ζ 1.0 而不是 0.95(2026-10-04 用户「不要过衝 也不要回彈」)
         *
         * 用户明确要求「非線性動畫、到位不要过衝也不要回彈」——
         * 满足这两条的曲线**只有临界阻尼**:它仍是缓出的非线性曲线(不是匀速直线),
         * 但单调逼近、永不穿越目标。`(A+Bt)e^(-ωt)` 只有一个零点 ⇒ 过冲恒等于 0。
         *
         * 代价要说清:ζ=1.0 是所有不过冲曲线里**最慢**的一条。
         * 而时长已由 [PageTiming] 按行程给(整页最多 600ms),所以这个「慢」
         * 落在可接受区间内。
         *
         * 响应 0.26s 比 [DEFAULT] 略短:提交这一段常常是**长行程**
         * (松手时 `pageSlide` 可能才几十 px,要一次走完 1440px),短一点更利落。
         */
        val PAGE_COMMIT = SpringSpec(response = 0.45f, dampingRatio = 1f)

        /**
         * 翻页**收回**(滑回原页):过阻尼 1.9,响应 0.24s。
         *
         * 与 [PAGE_COMMIT] 方向相反 —— 与 [FLY_BACK](放弃)/[RUBBER_BACK](撞头)
         * 属于同一类语义:**没成功**。所以宁可靠近阻尼也别过冲。
         *
         * 比 [RUBBER_BACK](2.4)稍缓一点:橡皮筋是"撞墙",要硬;收回是"我自己滑回去",
         * 太硬会读成"被弹回来",像不是自己做的决定。
         */
        val PAGE_ROLLBACK = SpringSpec(response = 0.45f, dampingRatio = 1.9f)

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
 * 翻页动画的**时长按行程给**,不写死(2026-10-04 用户「一直都很快」)。
 *
 * ## 为什么不能是固定时长
 *
 * 翻页的剩余行程从 ~100px 到 1440px(整页),差十几倍。固定时长必然二选一:
 * 短行程黏住、长行程飞过去。而用户抱怨的是「快」—— 那是**速度**问题,
 * 所以要对齐的是**速度**,不是时长。
 *
 * ## 量出来的对照(这才是根因)
 *
 * | 动画 | 位移 | 时长 | 平均速度 |
 * |---|---|---|---|
 * | 飞字起飞 | ~52px | 0.22s | ~236 px/s |
 * | 键帽进退场 | 几十 px | 0.30s | ~150 px/s |
 * | 翻页(修复前) | 最多 1440px | 0.26s | **~5540 px/s** |
 *
 * **翻页是全项目位移最大的动画,速度却是飞字的 20 倍。**
 * 当初那个 0.26s 是按小动作的时长给的 —— 所以用户说「一直都很快」是准确的,
 * 不是最近某次改坏的。
 *
 * ## 现在的做法
 *
 * `response = clamp(行程 / [PAGE_TARGET_SPEED], [PAGE_MIN_RESPONSE], [PAGE_MAX_RESPONSE])`
 *
 * 夹上下限的原因:行程很小时长会趋近 0(闪一下就没了,看着像跳变);
 * 行程很大时长会无上限(用户等太久)。两端都在实测里出现过 ——
 * 埋点见过 `from=1376` 之外的 `from=0`(整页)与 `from=585`。
 */
internal object PageTiming {

    /**
     * **一个公式,两个输入**:剩余行程与抬手速度。
     *
     * ```
     * response = clamp( max(行程/目标速度, 距离/速度) , [PAGE_MIN_RESPONSE], [PAGE_MAX_RESPONSE] )
     *                          └─ 慢:至少走这么久 └┘  └─ 快:至少花这么久 ┘
     * ```
     *
     * ## 为什么必须有速度那一项(2026-10-08 真机实测)
     *
     * 原来只有 `行程 / [PAGE_TARGET_SPEED]` 一项,而 ζ=1.0(临界阻尼)实测仍超调:
     *
     * | from→target | rem  | entryV | response | 首过超调 |
     * |-------------|------|--------|----------|----------|
     * | 540→1360    | 820  | 0      | 530ms    | —        |
     * | **1004→1360** | **355** | **6174** | **280ms** | **14px** |
     * | 607→1360    | 752  | 3627   | 466ms    | —        |
     *
     * `ring: 第1次 value=1373.97 target=1360` ⇒ **14px**。
     *
     * **临界阻尼的解是单调的,永不穿越目标** —— 所以超调不是 ζ 的问题,
     * 是**给的时间不够**:355px 以 6174px/s 飞来,只剩 355px 要走,
     * 若 response 给 280ms,平均速度只有 1268px/s,而进场的 6174px/s 需要时间被阻尼吃掉。
     * 时间不足 ⇒ 位置先冲过头再被拉回来。
     *
     * ⇒ 第二项 `|entryV| / PAGE_DECEL_SPEED` 就是**「把这份速度吃掉要多久」**。
     * 两项取大:慢手势看距离,快手势看速度。
     *
     * ## 为什么不让速度直接进阻尼比
     *
     * 那会把「快抬手」变成「欠阻尼」⇒ 重新引入过冲,而过冲是用户明确不要的
     * (见 [SpringSpec.PAGE_COMMIT] 里 0.72→0.9→0.95→1.0 那三轮下调的记录)。
     * 给时间是唯一能同时满足「跟手」与「不过冲」的做法。
     */
    const val PAGE_TARGET_SPEED = 1500f

    /**
     * 速度衰减率(px/s²):把进场速度吃到 0 需要 `|entryV| / 本值` 秒。
     *
     * 取 6000 的依据:真机实测 `entryV` 上限在 6000 上下
     * (`entryV=6174` / `entryV=-4876` / `entryV=3627` / `entryV=3022`),
     * 所以最坏情况 `6174/6000 ≈ 1.03s` —— 会被 [PAGE_MAX_RESPONSE] 夹到 600ms,
     * 那一档本来就该短(剩余行程小),所以不影响正确性。
     */
    const val PAGE_DECEL_SPEED = 6000f

    /** 时长下限:行程太小时防「闪一下」。 */
    const val PAGE_MIN_RESPONSE = 0.28f

    /** 时长上限:整页行程时防等太久。 */
    const val PAGE_MAX_RESPONSE = 0.60f

    /**
     * 剩余行程 + 进场速度 → response(秒)。
     *
     * `entryV` 取**绝对值**:方向不影响「要花多久吃掉它」。
     */
    fun response(remainingPx: Float, entrySpeed: Float = 0f): Float {
        val byDistance = kotlin.math.abs(remainingPx) / PAGE_TARGET_SPEED
        val bySpeed = kotlin.math.abs(entrySpeed) / PAGE_DECEL_SPEED
        return maxOf(byDistance, bySpeed)
            .coerceIn(PAGE_MIN_RESPONSE, PAGE_MAX_RESPONSE)
    }

    /** 同上,但按 [spec] 的阻尼比语义换算:回滚与提交共用一个公式。 */
    fun withResponse(spec: SpringSpec, remainingPx: Float, entrySpeed: Float = 0f): SpringSpec =
        spec.copy(response = response(remainingPx, entrySpeed))
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
