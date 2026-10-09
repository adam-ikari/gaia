package dev.hwkbd.ime.ui

import androidx.dynamicanimation.animation.FloatValueHolder
import androidx.dynamicanimation.animation.SpringAnimation
import androidx.dynamicanimation.animation.SpringForce

/**
 * 一条**弹簧通道**:把 [SpringSpec] 的物理参数交给官方引擎,并把"打断该怎么做"收在一处。
 *
 * 2026-10-02 用户「使用物理引擎重构手势和动画」「不要自己实现物理引擎和动画库」。
 * 引擎是 `androidx.dynamicanimation`(官方 `SpringAnimation`,与 Compose 的 spring 同源,
 * 依赖理由见 `app/build.gradle.kts`)。**本项目不实现积分器、不实现动画库**,
 * 只做两件官方不管的事:决定参数([SpringSpec])、决定落点([Landing])。
 *
 * ## 打断为什么不跳变 —— 本类存在的头号理由
 *
 * 旧代码是 `ValueAnimator` + 固定时长,每段动画都从**零初速**起步。所以:
 * - 松手那一瞬指尖速度被扔掉,画面速度从 `v` 突降到 0(「松手时顿一下」);
 * - 动画播到一半被新手势接管,新的动画器同样从静止起步(「打断后反向动画不自然」)。
 *
 * 官方 [SpringAnimation.animateToFinalPosition] **保留当前值与当前速度**改目标 ——
 * 这正是本类 [retarget] 薄薄一层的原因:调用方不可能"顺手用错一个"导致跳变。
 *
 * ## 为什么速度要自己记
 *
 * `DynamicAnimation` 1.1.0 **没有公开** `getCurrentVelocity()`(那个 API 要到 1.2.0 才有)。
 * 但 `addUpdateListener` 的每帧回调**带着速度参数**,所以在回调里存一份即可:
 * [currentVelocity] 给"手指接管正在飞的动画"用 —— 那是 [Landing] 的初速度,
 * 与松手时的速度同一个量,衔接得上。
 *
 * ## 当前值从哪读
 *
 * 用 [FloatValueHolder] 构造,引擎每帧把值写回 holder,所以 [currentValue] 始终是活的,
 * 不需要引擎再暴露一个 getter。
 */
internal class SpringChannel(
    from: Float,
    spec: SpringSpec,
    /** 每帧回调:`(当前值, 当前速度)`。 */
    private val onFrame: (Float, Float) -> Unit,
    /**
     * 结束时回调。**`cancelled = true` 表示是被 [settle]/[cancel] 打断,不是走完** ——
     * 两种情况必须分开:走完意味着状态该落定(清过渡、清残影),
     * 打断意味着新的一段马上接管,此刻**不能**收尾,否则会把新状态一起清掉。
     */
    private val onFinish: (cancelled: Boolean) -> Unit = {},
) {
    /** 当前这一段在用的物理参数。会被 [settle] 的同名参数改写(见那里)。 */
    private var spec: SpringSpec = spec


    private val holder = FloatValueHolder(from)

    /** 最近一帧的速度(px/s)。引擎不公开 getter,靠 update 回调存一份,见类注释。 */
    var currentVelocity = 0f
        private set

    val currentValue: Float get() = holder.value

    val isRunning: Boolean get() = animation.isRunning

    /** 最近一次 [settle] 的目标,给 `onFinish` 落定时用。 */
    var target = from
        private set

    /**
     * 交给引擎的那一份力。**单独存一个字段**,而不是每次 `toOfficial()` 现造 ——
     * 因为埋点要读**引擎手上那一份**(`getDampingRatio()`),而不是我们自己那份 spec:
     * 2026-10-09 查「报告 ζ=1.0 却实测 ζ≈0.5」时,唯一能区分
     * 「参数没送到」与「引擎有自己的约定」的量就是它。
     */
    internal var force: androidx.dynamicanimation.animation.SpringForce = spec.toOfficial()
        private set

    private val animation = SpringAnimation(holder).apply {
        setSpring(force)
        addUpdateListener { _, value, velocity ->
            currentVelocity = velocity
            onFrame(value, velocity)
        }
        addEndListener { _, cancelled, _, _ -> onFinish(cancelled) }
    }

    /** 逐帧埋点的行号,见 [TRACE_FRAMES]。 */
    private var frameNo = 0

    /**
     * 从 [from] 朝 [target] 起跑。
     *
     * **已经在跑就只改目标、保留值与速度**(`animateToFinalPosition` 的语义)——
     * 这正是打断不跳变的那一条。手指按住正在飞的页面、翻转页、反向收回,全部走这一个入口,
     * 调用方不需要(也不会)自己去判断"该不该 cancel 再重开"。
     *
     * ## 这里**故意没有**速度参数
     *
     * 松手时**一律从静止起步**:惯性已经在 [Landing] 的落点里算过了。
     * 这不是"不建议传",是**类型上不给传的机会** —— 见下面「惯性被算了两次」。
     */
    fun settle(from: Float, target: Float, spec: SpringSpec = this.spec) {
        this.target = target
        // 换 spec 只在**调用方显式给了不同的**时候做 —— 同一个通道上「提交」与「收回」
        // 走两支弹簧(2026-10-04 用户定),而打断时保留当前 spec 会更连贯,所以只在起跑时对齐。
        if (spec != this.spec) {
            force = spec.toOfficial()
            animation.setSpring(force)
            this.spec = spec
        }
        if (isRunning) {
            // **打断**:不断动画、不重设值与速度,只换目标 ⇒ 位置与速度都连续。
            // 这是速度**唯一该被保留**的场合:手指按住正在飞的页面时,画面此刻的物理速度
            // 就是弹簧的速度,丢掉它等于凭空刹一下。
            //
            // 埋点:打断**原来完全没有痕迹**。用户报「滑动没有打断动画」时,
            // 日志里既看不到打断发生过,也看不到它没发生 —— 只能靠猜。
            android.util.Log.i(
                "SpringChannel",
                "interrupt: $spec ζ=$spec.dampingRatio from=$from target=$target " +
                    "v=$currentVelocity engK=${force.stiffness} engZ=${force.dampingRatio}"
            )
            animation.animateToFinalPosition(target)
            return
        }
        // 松手起跑:速度由 [Landing] 计入**落点**了,这里必须从静止起步。
        //
        // ## 惯性被算了两次(2026-10-02 真机报「弹性太强 / 翻页成功的动画反而不明显」)
        //
        // 旧代码在松手时把 `v0` 同时给了弹簧初速,而落点 `Landing.project(from, target, v0)`
        // **已经**把这份惯性算进去了。于是同一份速度用了两遍:
        // 画面一松手就带着 `v0` 冲出去,而它要去的那个落点本来就是 `v0` 推出来的 ——
        // 于是冲过头、再被弹簧拉回来。读作"弹性太强"。
        //
        // 更要命的是**剩余距离本来就很小**:过提交线那一帧手指已经走完大半,
        // `pageSlide` 离落点可能只有几十像素,给一个几千 px/s 的初速等于瞬间到位 ——
        // 所以"翻页成功"那一下**看起来根本没动**,直接跳到新页。
        //
        // 修法就是**分工**:松手时速度只用来算落点,不给弹簧;打断时速度留给弹簧。
        // 这不是调参,是语义:[Landing] 答"落到哪",弹簧答"怎么过去",两者不重复计入。
        // 埋点:起跑(与上面的 interrupt 配对,两条一起看就知道动画是被接管还是被重开)
        //
        // **`engK`/`engZ` 是从引擎里回读出来的,不是我们的 spec 抄一遍。**
        // 2026-10-09:spec 一直写 ζ=1.0,而引擎实际在跑 0.5([toOfficial] 的那个 bug),
        // 靠的就是这两个数才看出来 —— 只打自己的 spec 的话两边永远"一致"。
        // 这一行留在代码里当护栏:哪天翻译层又错了,logcat 里立刻能对上账。
        android.util.Log.i(
            "SpringChannel",
            "start: ζ=$spec.dampingRatio from=$from target=$target v0=0 " +
                "engK=${force.stiffness} engZ=${force.dampingRatio}"
        )
        animation.cancel()
        holder.value = from
        currentVelocity = 0f
        animation.setStartVelocity(0f)
        animation.animateToFinalPosition(target)
        animation.start()
        onFrame(from, 0f) // 首帧立即落一次,否则 start() 到第一帧之间画面停在旧位置
    }

    /**
     * 硬置当前位置并清零速度:用于"换了个不相干的东西"(整页重切、键序重建)。
     *
     * 与 [settle] 的区别是**刻意跳变** —— 所以只在确实没有连续性可言时才用。
     */
    fun reset(value: Float) {
        android.util.Log.i("SpringChannel", "reset: value=$value（硬置,刻意跳变）")
        animation.cancel()
        holder.value = value
        currentVelocity = 0f
        target = value
    }

    fun cancel() = animation.cancel()
}

/**
 * 引擎要的两个数。**不含任何 Android 类型**,所以这一层可 JVM 单测 ——
 * 上一版把换算直接写在 [toOfficial] 里,于是 JVM 测不到,
 * 也就没人发现它其实一直是错的(见下面那个 bug)。
 */
internal data class SpringForces(val stiffness: Float, val dampingRatio: Float)

/**
 * [SpringSpec] → 引擎参数。**纯函数**,调试点只钉这一层。
 *
 * 官方要 `stiffness` + `dampingRatio`;本项目内部用 `response`(秒)+ `dampingRatio`,
 * 因为 stiffness 的量纲要人脑换算,调不出"大概多久到"。换算见 [SpringSpec]。
 */
internal fun SpringSpec.forces(): SpringForces = SpringForces(stiffness, dampingRatio)

/**
 * [SpringSpec] → 官方 [SpringForce]。**唯一的翻译层**。
 *
 * ## 2026-10-09:上一版写的是 `SpringForce(mass).apply { setDampingRatio(dampingRatio) }`
 *
 * 那个 `dampingRatio` **没写 `this@toOfficial.`**。`SpringForce` 是 Java 类,有
 * `getDampingRatio()`,Kotlin 把它合成成属性 —— 于是在 `apply` 的作用域里,
 * 这个裸名字**解析到 receiver 自己的 getter**,即刚 new 出来的 `SpringForce` 的默认值。
 *
 * androidx `dynamicanimation:1.1.0` 的 `SpringForce()` 把 `mDampingRatio` 硬编码成 **0.5**:
 *
 * ```
 * SpringForce();  0: invokespecial Object.<init>
 *                 15: ldc2_w  0.5d
 *                 18: putfield mDampingRatio
 * ```
 *
 * ⇒ **全项目的弹簧实际都在跑 ζ=0.5,spec 里写的 ζ 一个都没生效。**
 * 真机实测(整页 1360px 补完,请求 ζ=1.0):
 *
 * | 量 | 请求 | 引擎手上(`getDampingRatio()` 回读) | 实测轨迹反解 |
 * |---|---|---|---|
 * | stiffness | 109.66 (=ω₀²) | 109.66 ✓ | ω₀=10.58(响应 0.594s)✓ |
 * | dampingRatio | **1.0** | **0.5** | **ζ=0.50**,过冲 16.3% = 221px |
 *
 * 而 `setStiffness` 那行当初写的是 `this@toOfficial.stiffness`(限定了),
 * 所以刚度是对的 —— **一个对一个错,混在一起看就"像是响应正常只是有点过冲"**,
 * 于是历史上把 ζ 从 0.72 一路调到 1.0 都没用(那一串 0.1px/2.2px 的数字是纯函数算的,
 * 不是真机量的)。
 *
 * ## 修法不是加限定符,是**不用 apply**
 *
 * 加 `this@toOfficial.` 能修好这一处,但同样的错随时可能出现在下一个参数上 ——
 * 而且它**编译得过、单测看不见**。所以改成:先把两个数算进局部变量
 * (纯函数 [forces],有单测),再在一个**没有 receiver 作用域**的写法里逐个 setter。
 * 这样"名字解析到 receiver"这一类错**在结构上不可能发生**。
 */
internal fun SpringSpec.toOfficial(): SpringForce {
    val f = forces()
    val force = SpringForce(mass)
    force.setStiffness(f.stiffness)
    force.setDampingRatio(f.dampingRatio)
    return force
}
