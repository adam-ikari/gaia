package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.PinyinEngine

/**
 * 拼音输入法:把 [PinyinEngine] 包成策略。组词缓冲里的分隔符 `'` **原样交给引擎**——
 * 词库键带音节分隔符,引擎按"去撇字母相等 + 分节位约束"匹配:`xi'an` 只中西安这类
 * 带分节的词条、不中 `xian`(先);不带分节的 `xian` 歧义照旧(先、西安都在列)。
 */
class PinyinInputMethod(private val engine: PinyinEngine) : InputMethod {

    override val id = "pinyin"

    override fun query(raw: String, limit: Int): List<Candidate> =
        engine.query(normalize(raw), limit)

    override fun onCommitted(word: String) = engine.onCommitted(word)

    override fun removeUserWord(word: String) {
        engine.user.removeWord(word)
    }

    override fun userCount(word: String): Int = engine.user.count(word)

    companion object {
        /**
         * 查询前归一:滤掉字母与 `'` 以外的字符、连续 `'` 并成一个、丢掉开头的 `'`
         * (开头没有前一个音节,分节位无意义)。**末尾 `'` 保留**——它表示"本音节到此为止":
         * 后续前缀必须在该位置有边界(`xi'` 续不出 `xian…`),全串候选不受它约束
         * (`xi'` 仍中"xi 已输完"的词)。
         */
        internal fun normalize(raw: String): String =
            raw.filter { it in 'a'..'z' || it == '\'' }
                .replace(Regex("'+"), "'")
                .trimStart('\'')
    }
}
