package dev.hwkbd.ime.ui

/**
 * 候选词槽内截断的**纯函数**部分。
 *
 * ## 为什么截断
 *
 * 候选槽宽 = `CandidateBarView.width / 5`(Titan 2 上 288px),而候选词按
 * `17sp × scaledDensity` 画(400dpi 下约 51px/字)。6 字全字号 ≈306px
 * **已经超出槽宽** —— 旧代码只有「>6 字缩到 0.7 倍」一道缓解,缩完仍宽的
 * (≥8 字)会**画出槽界**,压到旁边的候选和分割线上(2026-10-12 用户报)。
 *
 * ## 为什么和最紧的邻居走不同的路
 *
 * L4/L5 键帽**不截断,放不下翻页**(2026-09-29 用户定案,键帽一把不裁);
 * 候选词是引擎给的、长短不可控,翻页治不了「一页里某个词太长」——所以
 * 候选行这一侧只能是**槽内截断,尾补「…」**。
 *
 * ## 为什么测量要用注入的 lambda
 *
 * `TextPaint.measureText` 是 Android 框架的,JVM 单测碰不到。把「量宽度」
 * 作为参数传进来,判据(裁几个字、什么时候连省略号都放不下)就是纯函数,
 * 可单测 —— 与 `SpringSpec.forces()` 同一个拆法(见 brain `unverified-as-fact`:
 * 参数对接错误编译得过、单测看不见,所以判据必须在能跑单测的那一层)。
 */
object CandidateWordFit {

    /** 截断标记。用单字符 U+2026,和 TextView 的 `ellipsize=end` 视觉一致。 */
    const val ELLIPSIS = "…"

    /**
     * 把 [text] 裁进 [maxW];装得下就原样返回,**一个字符都不动**。
     *
     * - 前缀宽度对长度单调(普通字体加字只加宽),所以裁几点用二分,
     *   而不是从尾到头一个个试;
     * - 连省略号都放不下([maxW] 小于一个「…」)时返回空串 —— 画不出来
     *   不如不画,硬画会画出槽;
     * - [measure] 量**当前字号**下的宽:调用方先把 `wordPaint.textSize`
     *   设好(含 0.7 倍缩小与中槽强调),再进来。
     */
    fun fit(text: String, maxW: Float, measure: (String) -> Float): String {
        if (maxW <= 0f) return ""
        if (measure(text) <= maxW) return text
        val ellW = measure(ELLIPSIS)
        if (ellW > maxW) return ""
        var lo = 0
        var hi = text.length
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (measure(text.substring(0, mid)) + ellW <= maxW) lo = mid else hi = mid - 1
        }
        // lo == 0:一个字都放不下,只剩省略号(它放得下,上面判过)
        return if (lo == 0) ELLIPSIS else text.substring(0, lo) + ELLIPSIS
    }
}
