package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.Candidate

/**
 * 输入法策略:候选来源与用户词维护。新增输入法只需 [ImeRegistry.registerMethod] 注册,
 * 状态机调用的是接口,不再认识任何具体引擎。
 */
interface InputMethod {

    /** 唯一 id,[Language.methodId] 引用它。 */
    val id: String

    /** 按缓冲串取候选(上限 [limit])。 */
    fun query(raw: String, limit: Int): List<Candidate>

    /** 一词上屏后的词频反馈。 */
    fun onCommitted(word: String) {}

    /** 长按候选删除该词的用户词条/频次。 */
    fun removeUserWord(word: String) {}

    /** 词的用户使用频次(测试/调试用)。 */
    fun userCount(word: String): Int = 0
}
