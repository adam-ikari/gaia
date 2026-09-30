package dev.hwkbd.ime.ui

/**
 * 候选词变化入场动画的纯计算部分:**哪几槽该动、按什么顺序、动多大**。
 *
 * 2026-09-30 用户「候选词变化需要有动画」。视图里那部分靠 `ValueAnimator` 驱动,
 * JVM 单测跑不了,所以把决策与变换抽到这里 —— 参数与 `CandidateBarView` 的
 * companion 常量同源(两边要一起改)。
 */
internal object SwapAnim {

    /** 动画参数。 */
    const val LIFT = 7f // 起始上移量(px,约 dip(3))
    const val SCALE_FROM = 0.88f // 起始缩放
    const val ALPHA_FROM = 0.25f // 起始透明度
    const val BASE_MS = 200L // 基准时长
    const val STAGGER_MS = 45L // 错峰步长:离中心一格晚这么久

    /** 中槽 = 第一候选(空格键位)。 */
    const val CENTER_SLOT = 2

    /**
     * 哪些槽该进场:只在**新旧都非空且词不同**时算,按"离中心"排序(中心优先)。
     *
     * 空槽 ↔ 非空槽**不算** —— 那是"词从无到有 / 从有到无",不是"换了一个词"。
     * 首次渲染出候选时逐槽播入场会像卡了一下;候选清空时播更是莫名其妙。
     */
    fun changedSlots(old: List<String?>, new: List<String?>, center: Int = CENTER_SLOT): List<Int> {
        val out = ArrayList<Int>()
        for (i in new.indices) {
            val a = old.getOrNull(i)
            val b = new[i]
            if (a != null && b != null && a != b) out.add(i)
        }
        return out.sortedBy { kotlin.math.abs(it - center) }
    }

    /** 某槽的入场延迟(ms):离中心几格就晚几格。 */
    fun delayMs(slot: Int, center: Int = CENTER_SLOT): Long =
        (kotlin.math.abs(slot - center) * STAGGER_MS).toLong()

    /**
     * 总时长(ms):够**最远那槽**走完自己的延迟 + 基准时长。
     *
     * 注意这里传的是"离中心最远的那个槽的 index",不是距离本身 ——
     * 传距离会让 [delayMs] 再减一次 center,算出来的延迟永远是 0(写错过一次)。
     */
    fun totalMs(changed: List<Int>, center: Int = CENTER_SLOT): Long {
        val farthest = changed.maxByOrNull { kotlin.math.abs(it - center) } ?: return BASE_MS
        return BASE_MS + delayMs(farthest, center)
    }

    /**
     * 全局进度 → 某槽的局部进度(0..1)。
     *
     * 每槽自己跑 [BASE_MS]:延迟之后按 `BASE_MS` 走完,而不是"从自己的延迟一直跑到总时长末尾"。
     * 后者会让**中心槽跑得最久**(它没延迟,却摊到全程),错峰的观感就反了。
     *
     * @param global 动画整体进度(0..1)
     * @param slot 目标槽
     * @param total 总时长(ms),须由 [totalMs] 给出
     */
    fun localProgress(global: Float, slot: Int, total: Long, center: Int = CENTER_SLOT): Float {
        if (total <= 0L) return 1f
        val delayFrac = delayMs(slot, center).toFloat() / total
        val spanFrac = (BASE_MS.toFloat() / total).coerceAtMost(1f)
        return ((global - delayFrac) / spanFrac).coerceIn(0f, 1f)
    }

    /** 局部进度 → (上移 px, 缩放)。 */
    fun transform(p: Float): Pair<Float, Float> =
        (LIFT * (1f - p)) to (SCALE_FROM + (1f - SCALE_FROM) * p)

    /** 局部进度 → 透明度。 */
    fun alpha(p: Float): Float = ALPHA_FROM + (1f - ALPHA_FROM) * p
}
