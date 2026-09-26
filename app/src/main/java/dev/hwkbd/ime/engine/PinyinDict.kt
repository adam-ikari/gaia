package dev.hwkbd.ime.engine

/**
 * 内置拼音词库:内存中的有序 TSV(拼音\t词\t词频)。
 * 按拼音字典序、同拼音内词频降序排列 → 前缀查询只需二分下界 + 顺序扫描。
 */
class PinyinDict {

    private var py: Array<String> = emptyArray()
    private var wd: Array<String> = emptyArray()
    private var fq: IntArray = IntArray(0)

    val size: Int get() = wd.size

    /** 行格式 `拼音\t词\t词频`;加载后强制按 (拼音 asc, 词频 desc) 排序。 */
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
        rows.sortWith(compareBy({ it.p }, { -it.f }))
        py = Array(rows.size) { rows[it].p }
        wd = Array(rows.size) { rows[it].w }
        fq = IntArray(rows.size) { rows[it].f }
    }

    private fun lowerBound(p: String): Int {
        var lo = 0
        var hi = size
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (py[mid] < p) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** 拼音完全等于 p 的行区间(空区间返回 null)。 */
    fun exactIndices(p: String): IntRange? {
        val lo = lowerBound(p)
        if (lo >= size || py[lo] != p) return null
        var hi = lo + 1
        while (hi < size && py[hi] == p) hi++
        return lo until hi
    }

    /** 拼音以 p 为前缀的行下标(按拼音序),最多 [cap] 条。 */
    fun prefixIndices(p: String, cap: Int): List<Int> {
        val res = ArrayList<Int>()
        var i = lowerBound(p)
        while (i < size && py[i].startsWith(p) && res.size < cap) {
            res += i
            i++
        }
        return res
    }

    /** 拼音等于 p 的词条中词频最高者。同拼音按词频降序 → 区间首行即最大。 */
    fun exactTop(p: String): Pair<String, Int>? =
        exactIndices(p)?.let { r -> wd[r.first] to fq[r.first] }

    fun word(i: Int): String = wd[i]
    fun pinyin(i: Int): String = py[i]
    fun freq(i: Int): Int = fq[i]
}
