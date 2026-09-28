package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.PinyinEngine

/**
 * 拼音输入法:把 [PinyinEngine] 包成策略。查询前只留小写字母——
 * 组词缓冲里的分隔符 `'` 是给用户看的分节标记,引擎只认字母(xi'an 按 xian 查)。
 */
class PinyinInputMethod(private val engine: PinyinEngine) : InputMethod {

    override val id = "pinyin"

    override fun query(raw: String, limit: Int): List<Candidate> =
        engine.query(raw.filter { it in 'a'..'z' }, limit)

    override fun onCommitted(word: String) = engine.onCommitted(word)

    override fun removeUserWord(word: String) {
        engine.user.removeWord(word)
    }

    override fun userCount(word: String): Int = engine.user.count(word)
}
