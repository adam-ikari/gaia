package dev.hwkbd.ime.ui

/**
 * 拖动手势的**共用换算**:死区 → 跟手 → 触发阈值 → 松手补完/回退。
 *
 * 2026-09-29 用户定案「候选词翻页、L5 翻页和飞字的手感对齐,都支持死区、跟手动画和触发阈值」——
 * 三处手势此前各写一套:候选行翻页是 24dp 抬手判据(不跟手)、L5 翻页是 8dp 死区 + 36dp 提交
 * (不跟手)、飞字是死区 = 阈值 1/4(跟手)。现在统一到同一份数学,三处只差**阈值取值**与**轴向**。
 *
 * ## 关键区分:绘制位移 ≠ 进度(2026-09-30 真机报障修)
 *
 * 这份数学只产出**进度**,不产出位移。绘制必须用 `slide(travel)`,不要拿进度乘行宽:
 * 进度是"离提交线还有多远",位移是"画面该挪多少"。混用的后果是**放大**——
 * 候选行翻页曾把进度乘整行宽(阈值是行宽 1/3),手指走 1/3 画面走满整行,放大 3 倍,
 * 于是"跟手"变成"手指在前 8% 行宽里画面完全不动,之后画面比手指快 3 倍"。
 * 真机报障是「翻页不跟手」+「翻页动画太短」——后者其实是零动画:
 * 画面在手指松开前就到了位,松手时 `from == target`,直接返回。
 *
 * **跟手的定义:画面位移 = 手指位移**,中间不做任何归一化。
 *
 * ## 死区里也要有响应
 *
 * 死区取固定的 [DEAD_DP](16dp)是为了防误触,但"手指在动、屏幕完全不动"这段静默本身就被感知成卡顿。
 * 所以死区内给 [DEAD_SLIP] 权重的最小响应。
 * 所以死区内给 [DEAD_SLIP] 权重的最小响应:不启动拖动,但画面跟着走一点。
 *
 * ## 动画一律非线性
 *
 * 2026-09-30 用户定案「所有动画都使用非线性」。三处补完动画此前都用 `ValueAnimator` 的
 * 默认插值(= 线性),匀速推看着像机器在动;现在统一走 [EASE_OUT](缓出曲线,快进慢停)。
 * 缓动属于**时间**维度,由动画插值器负责,不要在绘制函数里手乘 `s²` —— 那是把时间曲线
 * 混进空间计算,飞字的抬升与淡出都受牵连。
 */
internal object DragGesture {

    /**
     * **手势死区**(dp):**常量值**,横滑与上滑共用,**不随触发阈值变化**
     * (2026-10-02 用户「死区大小不随着触发阈值变化」「左右滑动和上下滑动的死区都是固定大小」
     * 「死区不使用比例计算 采用固定值」「死区改为常量值」)。
     *
     * 此前两处都取 `触发线 × 1/4`,于是死区跟着阈值放大:
     * - 横滑:标准档 59dp / 保守档 81dp —— **手指先走 59dp 屏幕纹丝不动**,
     *   这就是"翻页不跟手"的直接来源(不是没实现,是前面空了一段那么长);
     * - 上滑:12.5 / 20 / 30dp。
     * 阈值本来就有三档,死区再跟着放大三档,于是「灵敏」和「保守」差的不只是触发点,
     * 还有一段完全空白的起手距离 —— 调阈值时手感会连带乱掉。
     *
     * 16dp ≈ 一次普通触摸 slop 的两倍:越过它画面就 1:1 跟手,一直到触发线连续。
     * 横滑的触发线是 158/238/324dp,上滑是 50/80/120dp,16dp 对两者都在合理区间。
     *
     * **不给比例、不给默认值**:`progress`/`slide` 的 `dead` 是**必填参数**,
     * 类型上就不存在"忘了给、按比例算"这条路。死区不单独设开关 ——
     * 它是防误触的量,不是手感偏好,调它的人只会调触发阈值。
     */
    const val DEAD_DP = 16f

    /**
     * 死区内的**最小响应比例**:手指在死区里移动时,画面跟着走这么比例。
     *
     * 取 1/3 而不是 0:完全不给响应的话,那段静默会被感知成"没跟手";
     * 给一点就足以表明"我收到了",又不至于把防误触的死区架空。
     */
    const val DEAD_SLIP = 0.33f

    /**
     * 跟手进度:位移沿手势正方向累计时,返回 `0..1` 的进度。**只用于判定提交与否。**
     *
     * @param travel 位移(px,已取沿正方向的绝对值)
     * @param threshold 触发阈值(px,走完这一段就触发)
     * @param dead 死区(px),由调用方按 [DEAD_DP] × density 给;**必填**,免得退回比例
     */
    fun progress(travel: Float, threshold: Float, dead: Float): Float {
        if (threshold <= 0f) return if (travel > 0f) 1f else 0f
        return ((travel - dead) / (threshold - dead)).coerceIn(0f, 1f)
    }


    /**
     * 没有速度样本时的**兜底速度**(px/s):按"匀速补完这段距离、耗时 [SETTLE_MS]"反推。
     *
     * 用于非手势触发的补完(例如点箭头翻页、外部翻页、`onPageIndexChanged` 的整页切换):
     * 那时确实没有手指速度,给 0 会让 [Fling.stopDurationMs] 一律取下限 90ms —— 整页切换
     * 会显得像被弹射出去。按距离反推至少保证"走多远花多久"的比例是对的。
     *
     * @param from 起始位移(px)
     * @param target 目标位移(px)
     */
    fun fallbackVelocity(from: Float, target: Float): Float =
        if (target == from) 0f else kotlin.math.abs(target - from) / (SETTLE_MS / 1000f)

    /**
     * **跟手绘制位移**(px):画面该跟着手指挪多少。**绘制只用这个,不要用 [progress]。**
     *
     * 死区内按 [DEAD_SLIP] 打折(仍有响应),出了死区后 1:1 跟手,超过 [limit] 则夹住。
     *
     * @param travel 位移(px,已取沿手势正方向的绝对值)
     * @param threshold 触发阈值(px)
     * @param limit 位移上限(px)。翻页传行宽——一整页就是画面该走的全部距离。
     * @param dead 死区(px),同 [progress]:[DEAD_DP] × density,**必填**
     */
    fun slide(travel: Float, threshold: Float, limit: Float, dead: Float): Float {
        val mag = if (travel <= dead) travel * DEAD_SLIP else travel
        return mag.coerceIn(0f, limit)
    }

    /**
     * 缓出曲线(`PathInterpolator(0.2, 0, 0, 1)`):起步快、收尾慢。
     *
     * 2026-09-30 用户定案「所有动画都使用非线性」——默认线性匀速看着像机器在推。
     * 所有补完/收回动画都用它,包括飞字的放弃淡回。
     */
    val EASE_OUT: android.view.animation.Interpolator = android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)

    /**
     * 跟手动画的**基准时长**(ms):走完剩余的 `(1-p)` 距离所需时间。
     * 实际动画时长按剩余距离等比缩放,所以"半路松手"补完动画和"一次划到底"时长一致——
     * 这是跟手手感的关键:动画速度不随松手早晚跳变。
     *
     * 260 → 340(2026-09-30):真机报「翻页动画太短」。此前 260ms 配线性插值,匀速推一整页太快。
     */
    const val SETTLE_MS = 340f

    /** 剩余距离 → 补完动画时长(ms),夹在 `[90, SETTLE_MS]` 避免尾巴上一闪而过。 */
    fun settleDuration(progress: Float): Long =
        ((1f - progress.coerceIn(0f, 1f)) * SETTLE_MS).toLong().coerceIn(90L, SETTLE_MS.toLong())
}
