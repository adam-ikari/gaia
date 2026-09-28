package dev.hwkbd.ime.engine

/** 候选词条。EXACT=整串精确命中,PREFIX=词的拼音以输入为前缀,SEGMENT=分段组词拼接。 */
enum class CandidateKind { EXACT, PREFIX, SEGMENT, INIT_EXACT, INIT_PREFIX }

data class Candidate(val word: String, val pinyin: String, val kind: CandidateKind, val score: Long)

/**
 * 拼音解码:整串精确 → 前缀(用户还在输入) → DP 分段组词,三路都空再走**首字母退路**
 * (`sm` → 什么),多路合并,用户使用频次大权重加权。所有查询全内存,无 IO。
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
            // 档位加分保证 精确>前缀>组词 的整体次序(字典词频最高约 70 万,
            // 档位差需盖过它);用户词频加权仍可跨档把高频词顶上去。
            val score = base + KIND_BONUS[kind.ordinal] + user.count(word) * USER_BOOST
            val prev = out[word]
            if (prev == null || score > prev.score) {
                out[word] = Candidate(word, pinyin, kind, score)
            }
        }

        // 1. 整串精确命中(去撇字母相等 + 输入分节位 ⊆ 词条分节位,末尾分节不约束)
        for (i in dict.exactMatches(raw)) {
            add(dict.word(i), raw, CandidateKind.EXACT, dict.freq(i).toLong())
        }
        for ((w, f) in user.exactEntries(raw)) add(w, raw, CandidateKind.EXACT, f.toLong())

        // 2. 前缀命中:词的拼音比已输入的更长(去撇字母数)、分节位约束成立;纯按词频排序
        for (i in dict.prefixMatches(raw, PREFIX_SCAN_CAP)) {
            add(dict.word(i), dict.pinyin(i), CandidateKind.PREFIX, dict.freq(i).toLong())
        }
        for ((w, f, p) in user.prefixEntries(raw)) add(w, p, CandidateKind.PREFIX, f.toLong())

        // 3. DP 分段组词:把整串拼音切成若干段,每段取词频最高的精确命中
        segmentBest(raw)?.let { (word, score) -> add(word, raw, CandidateKind.SEGMENT, score) }

        // 4. 首字母退路:三路都没中(如 `sm` 不是任何拼音串)才按音节首字母查
        //    (`sm` → 什么/生命);单字母不走——与全拼前缀同集,第 2 路已覆盖。
        val q = raw.filter { it != '\'' }
        if (out.isEmpty() && q.length >= 2) {
            for (i in dict.initialExact(q)) {
                add(dict.word(i), dict.pinyin(i), CandidateKind.INIT_EXACT, dict.freq(i).toLong())
            }
            for (i in dict.initialPrefix(q, INITIAL_PREFIX_CAP)) {
                add(dict.word(i), dict.pinyin(i), CandidateKind.INIT_PREFIX, dict.freq(i).toLong())
            }
        }

        // 分数降序(分数里已含档位加分:EXACT 400 万 / PREFIX 250 万 / SEGMENT 0,首字母退路在其后)
        return out.values
            .sortedWith(compareByDescending<Candidate> { it.score })
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

        /**
         * 档位加分:EXACT > PREFIX > SEGMENT,档差需盖过字典最大词频(约 73 万);
         * INIT_* 是全拼三路都空时的**退路**(只在空集里排序,档内精确 > 前缀即可)。
         */
        private val KIND_BONUS = longArrayOf(4_000_000L, 2_500_000L, 0L, 1_500_000L, 800_000L)

        private const val PREFIX_SCAN_CAP = 400

        /** 首字母前缀扫描上限:退路只在全拼为空时触发,同前缀区间量级几千,留足余量不扫爆。 */
        private const val INITIAL_PREFIX_CAP = 2_000
        private const val MAX_WORD_PINYIN = 24
    }
}
