package dev.hwkbd.ime.ui

/**
 * 越界橡皮筋(拉伸变形)的数学。
 *
 * 2026-09-30 用户「翻页到翻到头要有拉伸变形动画」:此前首/末页往回拖**整条拖动都不接管**,
 * 手指在动、屏幕纹丝不动 —— 那不是"到头了"的反馈,是"卡了"。
 *
 * 抽成纯函数是因为 View 里的私有计算测不到:阻尼公式是这轮唯一需要用单测钉死的东西
 * (见 `RubberBandTest`),放进 View 就只能靠真机试。
 */
internal object Rubber {

    /**
     * 橡皮筋显示位移(px):`raw / (1 + raw/scale)`。
     *
     * - `raw` 是手指超出边界的原始位移;
     * - `scale` 是"阻尼长度",拉得越远增幅越小(越拉越沉);
     * - 结果**渐近于 scale**,天然封顶 —— 用它而不用 `raw × 固定折扣`,
     *   是因为固定折扣下拉很远会把键整个拉出屏幕,而用户明确要求"动画完全在自己区域内"。
     */
    fun shift(raw: Float, scale: Float, limit: Float): Float {
        if (raw <= 0f || scale <= 0f) return 0f
        val damped = raw / (1f + raw / scale)
        return if (damped < limit) damped else limit
    }

    /** 形变比例的封顶:横向最多压缩 8%,再多槽位就挤到认不出了。 */
    const val MAX_SQUASH = 0.08f

    /** 横向压缩量 = shift / 行宽,封顶 [MAX_SQUASH]。 */
    fun squash(shift: Float, viewW: Float): Float =
        (if (viewW <= 0f) 0f else shift / viewW).coerceIn(0f, MAX_SQUASH)
}
