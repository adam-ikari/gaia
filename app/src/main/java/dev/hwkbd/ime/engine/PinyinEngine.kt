package dev.hwkbd.ime.engine

/** 候选词条。EXACT=整串精确命中,PREFIX=词的拼音以输入为前缀,SEGMENT=分段组词拼接。 */
enum class CandidateKind { EXACT, PREFIX, SEGMENT }

data class Candidate(val word: String, val pinyin: String, val kind: CandidateKind, val score: Long)

/**
 * 拼音解码:整串精确 → 前缀(用户还在输入) → DP 分段组词,三路合并,
 * 用户使用频次大权重加权。所有查询全内存,无 IO。
 */
class PinyinEngine(val dict: PinyinDict, val user: UserDict) {

    fun onCommitted(word: String) {
        user.bump(word)
    }

    fun query(raw: String, limit: Int): List<Candidate> {
        if (raw.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Candidate>()

        fun add(word: String, pinyin: String, kind: CandidateKind, base: Long) {
            if (word.isEmpty()) return
            val score = base + user.count(word) * USER_BOOST
            val prev = out[word]
            if (prev == null || score > prev.score) {
                out[word] = Candidate(word, pinyin, kind, score)
            }
        }

        // 1. 整串精确命中
        dict.exactIndices(raw)?.forEach { i ->
            add(dict.word(i), raw, CandidateKind.EXACT, dict.freq(i).toLong())
        }
        for ((w, f) in user.exactEntries(raw)) add(w, raw, CandidateKind.EXACT, f.toLong())

        // 2. 前缀命中:词的拼音比已输入的更长,拼音越接近输入长度越靠前
        for (i in dict.prefixIndices(raw, PREFIX_SCAN_CAP)) {
            val p = dict.pinyin(i)
            if (p.length > raw.length) {
                add(dict.word(i), p, CandidateKind.PREFIX, dict.freq(i) - 1000L * (p.length - raw.length))
            }
        }
        for ((w, f, p) in user.prefixEntries(raw)) {
            if (p.length > raw.length) add(w, p, CandidateKind.PREFIX, f - 1000L * (p.length - raw.length))
        }

        // 3. DP 分段组词:把整串拼音切成若干段,每段取词频最高的精确命中
        segmentBest(raw)?.let { (word, score) -> add(word, raw, CandidateKind.SEGMENT, score) }

        return out.values
            .sortedWith(compareByDescending<Candidate> { it.score }.thenBy { it.kind.ordinal })
            .take(limit)
    }

    private class Node(val sum: Long, val cnt: Int, val parent: Int, val word: String)

    private fun avg(n: Node): Long = if (n.cnt == 0) 0 else n.sum / n.cnt

    /** 整串按最优分段覆盖:最大化段均词频,段数越少越好。无解返回 null。 */
    private fun segmentBest(raw: String): Pair<String, Long>? {
        val n = raw.length
        if (n < 3) return null
        val best = arrayOfNulls<Node>(n + 1)
        best[0] = Node(0, 0, -1, "")
        for (i in 1..n) {
            for (j in maxOf(0, i - MAX_WORD_PINYIN) until i) {
                val bj = best[j] ?: continue
                val seg = raw.substring(j, i)
                val top = bestSegWord(seg) ?: continue
                val cand = Node(bj.sum + top.second, bj.cnt + 1, j, top.first)
                val cur = best[i]
                if (cur == null || avg(cand) > avg(cur) || (avg(cand) == avg(cur) && cand.cnt < cur.cnt)) {
                    best[i] = cand
                }
            }
        }
        val end = best[n] ?: return null
        if (end.cnt < 2) return null // 单段 = 精确命中,第 1、2 路已覆盖
        val sb = StringBuilder()
        var node: Node = end
        while (node.parent >= 0) {
            sb.insert(0, node.word)
            node = best[node.parent] ?: return null
        }
        return sb.toString() to (avg(end) - 100L * end.cnt)
    }

    private fun bestSegWord(seg: String): Pair<String, Long>? {
        val d = dict.exactTop(seg)?.let { it.first to it.second.toLong() }
        val u = user.exactTop(seg)?.let { it.first to it.second.toLong() }
        return when {
            d != null && u != null -> if (d.second >= u.second) d else u
            else -> d ?: u
        }
    }

    companion object {
        /** 一次上屏对词频的加权:大于 jieba 词库的最大词频,保证"最近用过"排最前。 */
        const val USER_BOOST = 1_000_000L
        private const val PREFIX_SCAN_CAP = 400
        private const val MAX_WORD_PINYIN = 24
    }
}
