package dev.hwkbd.ime.engine

/**
 * 候选词条。
 *
 * ## 为什么从 PinyinEngine 里独立出来(2026-10-03)
 *
 * 原来这两个类型定义在 `PinyinEngine.kt` 的文件头上,于是删掉那个引擎时
 * **顺带把数据类也删了**,`ImeState`/`ImeController` 一片红。它们是被
 * [dev.hwkbd.ime.strategy.InputMethod] 与上层状态机引用的**契约类型**,
 * 生命周期应当跟接口走,而不是跟某个实现走。
 *
 * ## kind 与 score 在 Rime 后端下退化为常量
 *
 * 换引擎后 [dev.hwkbd.ime.rime.RimeEngine] 会把 kind 填 [CandidateKind.EXACT]、
 * score 填 0:librime 的 C API 只给候选的 `text`,不暴露分类与分数,
 * 而打分已在 Rime 内部完成。
 *
 * 这**不影响显示** —— 全项目搜过,`CandidateKind.` 在这里之外零引用,
 * 它只用于 `PinyinEngine` 内部的排序(档位加分 + 用户词频加权)。
 * 字段保留是为了不把上层十几处 `Candidate(...)` 构造改一遍,以及将来接
 * 别的引擎(比如一个会给分的引擎)时不用再改上层。
 */
enum class CandidateKind {
    /** 整串精确命中。 */
    EXACT,

    /** 词的拼音以已输入为前缀(用户还在输入)。 */
    PREFIX,

    /** 分段组词拼接。 */
    SEGMENT,

    /** 首字母退路(精确 / 前缀)。 */
    INIT_EXACT,

    /** 首字母退路(前缀)。 */
    INIT_PREFIX,
}

data class Candidate(
    val word: String,
    val pinyin: String,
    val kind: CandidateKind,
    val score: Long,
)
