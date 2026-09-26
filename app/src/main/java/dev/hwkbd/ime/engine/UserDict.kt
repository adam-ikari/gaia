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

    /** 新增导入词条;拼音只接受小写字母与分隔符 '。 */
    fun addEntry(pinyin: String, word: String, freq: Int): Boolean {
        if (pinyin.isEmpty() || word.isEmpty()) return false
        if (pinyin.any { it !in 'a'..'z' && it != '\'' }) return false
        if (freq < 0) return false
        entries.getOrPut(pinyin) { HashMap() }[word] = freq
        dirty = true
        return true
    }

    fun exactEntries(pinyin: String): List<Pair<String, Int>> =
        entries[pinyin]?.map { it.key to it.value } ?: emptyList()

    fun exactTop(pinyin: String): Pair<String, Int>? =
        entries[pinyin]?.maxByOrNull { it.value }?.let { it.key to it.value }

    /** 返回 (词, 词频, 拼音) 三元组,拼音以 [prefix] 开头。 */
    fun prefixEntries(prefix: String, cap: Int = 200): List<Triple<String, Int, String>> {
        val res = ArrayList<Triple<String, Int, String>>()
        for ((p, ws) in entries) {
            if (!p.startsWith(prefix)) continue
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
