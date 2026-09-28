package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.Candidate

/** 直接上屏的输入法:没有候选(英文等),所有查询返回空。 */
object DirectInputMethod : InputMethod {

    override val id = "direct"

    override fun query(raw: String, limit: Int): List<Candidate> = emptyList()
}
