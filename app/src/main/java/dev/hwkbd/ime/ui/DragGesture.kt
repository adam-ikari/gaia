package dev.hwkbd.ime.ui

/**
 * 拖动手势的**共用换算**:死区 → 跟手 → 触发阈值 → 松手补完/回退。
 *
 * 2026-09-29 用户定案「候选词翻页、L5 翻页和飞字的手感对齐,都支持死区、跟手动画和触发阈值」——
 * 三处手势此前各写一套:候选行翻页是 24dp 抬手判据(不跟手)、L5 翻页是 8dp 死区 + 36dp 提交
 * (不跟手)、飞字是死区 = 阈值 1/4(跟手)。现在统一到同一份数学,三处只差**阈值取值**与**轴向**。
 *
 * 进度定义(三条轴都用它):
 * ```
 * p = (位移 - 死区) / (阈值 - 死区)      // 位移 = 沿手势正方向的累计距离
 * ```
 * - 位移 ≤ 死区 → `p = 0`:抖动不启动,视觉零变化;
 * - 死区 → 阈值 → `p` 从 0 线性涨到 1:**与手指 1:1**(斜率 `1/(阈值-死区)`),这就是"跟手";
 * - `p = 1` 即**触发线**:翻页类松手即提交并补完剩余动画,飞字类越过即由动画接管飞走;
 * - 松手时 `p < 1` → 反向动画退回 0(回退同样是动画,不是瞬移)。
 *
 * 死区一律取**阈值的 1/4**([DEAD_FRAC]),沿用飞字原有的约定,三处不再各拍一个数。
 */
internal object DragGesture {

    /** 死区占阈值的比例:比它小的位移视为抖动。 */
    const val DEAD_FRAC = 0.25f

    /**
     * 跟手进度:位移沿手势正方向累计时,返回 `0..1` 的进度。
     *
     * @param travel 位移(px,已取沿正方向的绝对值)
     * @param threshold 触发阈值(px,走完这一段就触发)
     */
    fun progress(travel: Float, threshold: Float): Float {
        if (threshold <= 0f) return if (travel > 0f) 1f else 0f
        val dead = threshold * DEAD_FRAC
        return ((travel - dead) / (threshold - dead)).coerceIn(0f, 1f)
    }

    /** 死区(px):小于它不启动拖动。 */
    fun dead(threshold: Float): Float = threshold * DEAD_FRAC

    /**
     * 跟手动画的**基准时长**(ms):走完剩余的 `(1-p)` 距离所需时间。
     * 实际动画时长按剩余距离等比缩放,所以"半路松手"补完动画和"一次划到底"时长一致——
     * 这是跟手手感的关键:动画速度不随松手早晚跳变。
     */
    const val SETTLE_MS = 260f

    /** 剩余距离 → 补完动画时长(ms),夹在 `[60, SETTLE_MS]` 避免尾巴上一闪而过。 */
    fun settleDuration(progress: Float): Long =
        ((1f - progress.coerceIn(0f, 1f)) * SETTLE_MS).toLong().coerceIn(60L, SETTLE_MS.toLong())
}
