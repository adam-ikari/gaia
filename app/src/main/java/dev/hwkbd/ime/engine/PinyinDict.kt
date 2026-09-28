package dev.hwkbd.ime.engine

/**
 * 内置拼音词库:内存中的有序 TSV(拼音\t词\t词频),拼音可带音节分隔符 `'`(如 `xi'an`)。
 * 按**去撇拼音**字典序、同字母串内词频降序排列 → 前缀查询二分下界 + 顺序扫描;
 * 带 `'` 与不带 `'` 的同字母词条聚在同一区间,由**分节位约束**挑出该中的
 * (`xi'an` 只中西安不中先,`xian` 两者都中——见 [exactMatches])。
 */
class PinyinDict {

    private var py: Array<String> = emptyArray()
    private var wd: Array<String> = emptyArray()
    private var fq: IntArray = IntArray(0)

    /**
     * 首字母索引:各行按音节首字母拼串(`shen'me` → `sm`)排序的行下标,
     * 键 (首字母串 asc, 词频 desc)——同首字母段内高词频在前,[load] 时建一次。
     */
    private var initIdx: IntArray = IntArray(0)

    val size: Int get() = wd.size

    /** 行格式 `拼音\t词\t词频`;加载后强制按 (去撇拼音 asc, 词频 desc) 排序。 */
    fun load(lines: List<String>) {
        data class Row(val p: String, val w: String, val f: Int)
        val rows = ArrayList<Row>(lines.size)
        for (line in lines) {
            val i = line.indexOf('\t')
            if (i <= 0) continue
            val j = line.indexOf('\t', i + 1)
            if (j <= i) continue
            val f = line.substring(j + 1).trim().toIntOrNull() ?: continue
            val p = line.substring(0, i)
            val w = line.substring(i + 1, j)
            if (p.isNotEmpty() && w.isNotEmpty()) rows += Row(p, w, f)
        }
        rows.sortWith { a, b ->
            val c = strippedCompare(a.p, b.p)
            if (c != 0) c else b.f - a.f
        }
        py = Array(rows.size) { rows[it].p }
        wd = Array(rows.size) { rows[it].w }
        fq = IntArray(rows.size) { rows[it].f }
        buildInitialsIndex()
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (strippedCompare(py[mid], p) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 拼音(忽略 `'`)等于 p、且分节位约束成立的行下标(按序)。
     * 约束:输入的每个分节位(= 其前字母数)也必须是词条的分节位,**末尾那个除外**
     * (全串已输完,末尾分节只约束后续前缀)——于是 `xi'an` 只中 `xi'an` 词条(西安),
     * 不中 `xian`(先);输入 `xian` 不带分节则两者都中(歧义照旧)。
     */
    fun exactMatches(p: String): List<Int> {
        val res = ArrayList<Int>()
        val pc = cutsOf(p)
        val len = lettersLen(p)
        var i = lowerBound(p)
        while (i < size && strippedCompare(py[i], p) == 0) {
            if (cutSubset(pc, len, cutsOf(py[i]), dropTrailing = true)) res += i
            i++
        }
        return res
    }

    /**
     * 拼音(忽略 `'`)以 p 为前缀、去撇字母数严格更长、分节位约束成立的行下标(按序),最多 [cap] 条。
     * 前缀含末尾分节位也要词条有:`xi'` 只续 `xi'an…`,续不了 `xian…`。
     */
    fun prefixMatches(p: String, cap: Int): List<Int> {
        val res = ArrayList<Int>()
        val pc = cutsOf(p)
        val len = lettersLen(p)
        var i = lowerBound(p)
        while (i < size && strippedStartsWith(py[i], p) && res.size < cap) {
            if (lettersLen(py[i]) > len && cutSubset(pc, len, cutsOf(py[i]), dropTrailing = false)) {
                res += i
            }
            i++
        }
        return res
    }

    /** 拼音等于 p 且分节位达标的词条中词频最高者(区间按词频降序 → 首个通过者即最大)。 */
    fun exactTop(p: String): Pair<String, Int>? =
        exactMatches(p).firstOrNull()?.let { wd[it] to fq[it] }

    fun word(i: Int): String = wd[i]
    fun pinyin(i: Int): String = py[i]
    fun freq(i: Int): Int = fq[i]

    /** 建首字母索引:临时拼 (首字母串, 行号) 对排序,只留下行号数组,临时对随即可回收。 */
    private fun buildInitialsIndex() {
        val order = Array(size) { initialsOf(py[it]) to it }
        order.sortWith { a, b ->
            val c = a.first.compareTo(b.first)
            if (c != 0) c else fq[b.second] - fq[a.second]
        }
        initIdx = IntArray(size) { order[it].second }
    }

    /** 首字母串下界:首字母 >= q 的最小 [initIdx] 位置。 */
    private fun initLowerBound(q: String): Int {
        var lo = 0
        var hi = initIdx.size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (initialsOf(py[initIdx[mid]]).compareTo(q) < 0) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /**
     * 首字母串 == q 的行下标(按序;同首字母段在索引内按词频降序)。
     * 首字母 = 每个分节的首字母:`shen'me` → `sm`、`xi'an` → `xa`、单音节 `shi` → `s`。
     */
    fun initialExact(q: String): List<Int> {
        val res = ArrayList<Int>()
        var i = initLowerBound(q)
        while (i < initIdx.size && initialsOf(py[initIdx[i]]) == q) {
            res += initIdx[i]
            i++
        }
        return res
    }

    /**
     * 首字母串以 q 开头且**严格更长**的行下标(按序),最多 [cap] 条。
     * 先跳过 == q 的同段(那段归 [initialExact])——否则扫描额度被精确段耗光。
     */
    fun initialPrefix(q: String, cap: Int): List<Int> {
        val res = ArrayList<Int>()
        var i = initLowerBound(q)
        while (i < initIdx.size && initialsOf(py[initIdx[i]]) == q) i++
        while (i < initIdx.size && res.size < cap) {
            if (!initialsOf(py[initIdx[i]]).startsWith(q)) break
            res += initIdx[i]
            i++
        }
        return res
    }
}

/** 去撇字母数(`'` 不计)。 */
internal fun lettersLen(p: String): Int = p.length - p.count { it == '\'' }

/**
 * 音节首字母拼串:每个 `'` 分节取首字母。
 * `shen'me` → `sm`、`xi'an` → `xa`、单音节 `shi` → `s`(无分节 = 整个音节的首字母)。
 */
internal fun initialsOf(p: String): String {
    val sb = StringBuilder(p.length)
    var atStart = true
    for (c in p) {
        if (c == '\'') {
            atStart = true
        } else if (atStart) {
            sb.append(c)
            atStart = false
        }
    }
    return sb.toString()
}

/**
 * 忽略 `'` 的字典序比较:两边各自跳过 `'`,比剩余字母串。
 * [PinyinDict.load] 的排序与二分查找用同一把尺——带撇词条落在其去撇字母序的位置上。
 */
internal fun strippedCompare(a: String, b: String): Int {
    var i = 0
    var j = 0
    while (true) {
        while (i < a.length && a[i] == '\'') i++
        while (j < b.length && b[j] == '\'') j++
        if (i >= a.length || j >= b.length) break
        if (a[i] != b[j]) return if (a[i] < b[j]) -1 else 1
        i++
        j++
    }
    while (i < a.length && a[i] == '\'') i++
    while (j < b.length && b[j] == '\'') j++
    return (a.length - i).compareTo(b.length - j)
}

/** sub 去撇后是否以 pref 去撇后开头(比较时两边跳过 `'`)。 */
internal fun strippedStartsWith(sub: String, pref: String): Boolean {
    var i = 0
    var j = 0
    while (j < pref.length) {
        if (pref[j] == '\'') {
            j++
            continue
        }
        while (i < sub.length && sub[i] == '\'') i++
        if (i >= sub.length || sub[i] != pref[j]) return false
        i++
        j++
    }
    return true
}

/** 输入串里每个 `'` 的分节位(= 其前字母数)。 */
internal fun cutsOf(p: String): IntArray {
    var letters = 0
    val out = ArrayList<Int>()
    for (c in p) {
        if (c == '\'') out += letters else letters++
    }
    return out.toIntArray()
}

/**
 * 输入分节位 ⊆ 词条分节位。[dropTrailing]:全串匹配时跳过输入末尾那个分节位
 * (位置 = 输入字母数,词条不可能在该处有边界——"全串输完"本身就是边界)。
 */
private fun cutSubset(pc: IntArray, len: Int, kc: IntArray, dropTrailing: Boolean): Boolean {
    for (c in pc) {
        if (dropTrailing && c == len) continue
        if (kc.indexOf(c) < 0) return false
    }
    return true
}
