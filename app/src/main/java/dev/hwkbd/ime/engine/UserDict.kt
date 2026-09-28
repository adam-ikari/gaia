package dev.hwkbd.ime.engine

/**
 * 用户词库:两部分——
 * 1. counts:上屏词的使用频次(对内置词库候选排序加权);
 * 2. entries:导入/自造词条(拼音 → 词 → 词频),与内置词库一起参与查询。
 * 持久化为 filesDir/user_dict.json,读写都发生在非输入路径。
 */
class UserDict {

    private val counts = HashMap<String, Int>()
    private val entries = HashMap<String, HashMap<String, Int>>()

    @Volatile
    var dirty = false
        private set

    fun markClean() {
        dirty = false
    }

    val trackedWordCount: Int get() = counts.size
    val entryCount: Int get() = entries.values.sumOf { it.size }

    fun count(word: String): Int = counts[word] ?: 0

    fun bump(word: String) {
        counts[word] = count(word) + 1
        dirty = true
    }

    /**
     * 删除一个词的全部用户痕迹:使用频次记录 + 导入词条(可可"长按候选删词"同款)。
     * 返回是否确实删过东西(内置词库的词删频次后仍存在,只是不再被加权)。
     */
    fun removeWord(word: String): Boolean {
        val hadCount = counts.remove(word) != null
        var hadEntry = false
        val it = entries.values.iterator()
        while (it.hasNext()) {
            val ws = it.next()
            if (ws.remove(word) != null) hadEntry = true
            if (ws.isEmpty()) it.remove()
        }
        if (hadCount || hadEntry) dirty = true
        return hadCount || hadEntry
    }

    /** 新增导入词条;拼音只接受小写字母与分隔符 '。 */
    fun addEntry(pinyin: String, word: String, freq: Int): Boolean {
        if (pinyin.isEmpty() || word.isEmpty()) return false
        if (pinyin.any { it !in 'a'..'z' && it != '\'' }) return false
        if (freq < 0) return false
        entries.getOrPut(pinyin) { HashMap() }[word] = freq
        dirty = true
        return true
    }

    /**
     * 精确匹配的导入词条:去撇字母相等即中,**不带分节约束**——导入词条多为无分节的
     * 转换产物(边界信息缺失,严格约束会把 `xi'an` 挡在 `nihao` 词条外),内置词库才管分节。
     */
    fun exactEntries(pinyin: String): List<Pair<String, Int>> {
        val res = ArrayList<Pair<String, Int>>()
        for ((p, ws) in entries) {
            if (strippedCompare(p, pinyin) == 0) for ((w, f) in ws) res += w to f
        }
        return res
    }

    /** 同 [exactEntries] 的去撇等价(分节不约束)取词频最高者。 */
    fun exactTop(pinyin: String): Pair<String, Int>? {
        var best: Pair<String, Int>? = null
        for ((p, ws) in entries) {
            if (strippedCompare(p, pinyin) != 0) continue
            for ((w, f) in ws) if (best == null || f > best.second) best = w to f
        }
        return best
    }

    /** 返回 (词, 词频, 拼音) 三元组:去撇拼音以 [prefix] 开头且**严格更长**(与词库契约一致)。 */
    fun prefixEntries(prefix: String, cap: Int = 200): List<Triple<String, Int, String>> {
        val res = ArrayList<Triple<String, Int, String>>()
        for ((p, ws) in entries) {
            if (lettersLen(p) <= lettersLen(prefix)) continue
            if (!strippedStartsWith(p, prefix)) continue
            for ((w, f) in ws) {
                res += Triple(w, f, p)
                if (res.size >= cap) return res
            }
        }
        return res
    }

    fun clearAll() {
        counts.clear()
        entries.clear()
        dirty = true
    }

    /** 导入行:`词<TAB>拼音<TAB>词频` 或 `词<TAB>拼音`(词频默认 1)。无拼音的行被拒绝。 */
    fun importLine(line: String): Boolean {
        val parts = line.trim().split('\t')
        return when (parts.size) {
            3 -> parts[2].trim().toIntOrNull()?.let { addEntry(parts[1].trim(), parts[0].trim(), it) } ?: false
            2 -> addEntry(parts[1].trim(), parts[0].trim(), 1)
            else -> false
        }
    }

    fun exportLines(): List<String> =
        entries.flatMap { (p, ws) -> ws.map { (w, f) -> "$w\t$p\t$f" } }.sorted()

    fun toJson(): String = MiniJson.write(
        linkedMapOf(
            "v" to 1,
            "counts" to counts,
            "entries" to entries.flatMap { (p, ws) ->
                ws.map { (w, f) -> linkedMapOf("p" to p, "w" to w, "f" to f) }
            },
        )
    )

    /** 解析失败返回 false 且保持原状。 */
    fun loadJson(text: String): Boolean {
        return try {
            val root = MiniJson.parse(text) as? Map<*, *> ?: return false
            val newCounts = HashMap<String, Int>()
            (root["counts"] as? Map<*, *>)?.forEach { (k, v) ->
                val n = (v as? Long)?.toInt() ?: return false
                if (k is String && n >= 0) newCounts[k] = n
            }
            val newEntries = HashMap<String, HashMap<String, Int>>()
            (root["entries"] as? List<*>)?.forEach { e ->
                val m = e as? Map<*, *> ?: return false
                val p = m["p"] as? String ?: return false
                val w = m["w"] as? String ?: return false
                val f = (m["f"] as? Long)?.toInt() ?: return false
                if (p.isEmpty() || w.isEmpty() || f < 0) return false
                newEntries.getOrPut(p) { HashMap() }[w] = f
            }
            counts.clear(); counts.putAll(newCounts)
            entries.clear(); entries.putAll(newEntries)
            dirty = false
            true
        } catch (e: Exception) {
            false
        }
    }
}
