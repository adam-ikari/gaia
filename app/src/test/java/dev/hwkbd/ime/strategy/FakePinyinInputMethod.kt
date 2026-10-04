package dev.hwkbd.ime.strategy

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.engine.CandidateKind

/**
 * 测试用拼音引擎:**查表 + 前缀匹配**,不加载真实词典。
 *
 * ## 为什么需要它(2026-10-03 换 librime 时冒出来的)
 *
 * `ImeControllerTest` / `PinyinInputMethodTest` / `ImeRegistryTest` 原本直接
 * `PinyinEngine(PinyinDict(...), UserDict())` —— 真加载一份词库。
 * 换 librime 后这条路断了,而**这不是测试的问题,是架构的问题**:
 *
 * 状态机测试要验的是「敲 a 然后 s 会怎样」,**不是**「`as` 会不会出「啊斯」。
 * 引擎实现换了之后,后者已经不是被测对象,却仍然嵌在被测路径里 ——
 * 于是每次换引擎都要重写几十个测试断言,而它们本该一行不动。
 *
 * 这个替身把「拼音串 → 候选」做成显式给定,于是:
 * - 状态机测试**与引擎彻底解耦**,换引擎不再影响它;
 * - 「某串拼音出某组候选」这个假设**写在测试里、可读**,而不是藏在词典文件里。
 *
 * ## 为什么带前缀匹配(第一版没带,九条测试立刻红)
 *
 * 原 `dictLines` 里 `ni → 你` 与 `nie → 捏` 是分开的键,而**测试期望敲 `ni` 时
 * 能看到 `你/泥/拟/捏` 四个** —— 靠的是旧引擎的 `prefixMatches`(词的拼音以已输入为前缀)。
 * 只做精确查表时 `ni` 只中 `你`,于是「候选从中位紧凑补位」「首候选固定在中间」
 * 这类**断言候选数量与位置**的测试全崩。
 *
 * 所以替身实现了同一条语义:**已输入串是某个词拼音的前缀时,该词也算命中。**
 * 这是「引擎该有的行为」在测试里的最小复现,不是绕过什么。
 *
 * ## 它不测什么
 *
 * 真的拼音解码质量(哪个词该排前面、词频怎么加权)现在**没有 JVM 测试覆盖** ——
 * 那段代码在 C++ 里,只能在真机上验。这是这次换引擎的已知缺口,
 * 不是「测试通过所以没问题」。
 */
class FakePinyinInputMethod(
    override val id: String = "pinyin",
    /** 键是**带音节分隔符**的拼音(与真实词库同格式),值是该键下的候选词。 */
    internal val table: Map<String, List<String>> = emptyMap(),
    /**
     * [InputMethod.selectAndRemain] 该返回的「剩余拼音」(2026-10-04)。
     *
     * `null` = **不参与选词**(默认)⇒ 调用方走旧路径、全清。这保持了这个替身在
     * 换引擎前后的行为一致,也让「引擎不支持」这条分支有测试可钉。
     */
    internal val remainAfterSelect: String? = null,
) : InputMethod {

    /** 实现 [InputMethod.selectAndRemain]:按 [remainAfterSelect] 回答,不做真的选词。 */
    override fun selectAndRemain(index: Int): Pair<String, String?>? {
        remainAfterSelect ?: return null // 不参与 ⇒ 走旧路径
        val raw = queried.lastOrNull() ?: return null
        val word = candidatesOf(raw).getOrNull(index) ?: return null
        return word to remainAfterSelect
    }

    /** 被问过的所有 `raw`,便于断言「查询传进来的是什么」。 */
    val queried: MutableList<String> = mutableListOf()

    /** 记录上屏过的词,便于断言 `onCommitted` 的调用。 */
    val committed: MutableList<String> = mutableListOf()

    /** 长按删除的记录;(词, 是否删成功)。 */
    val removed: MutableList<Pair<String, Boolean>> = mutableListOf()

    override fun query(raw: String, limit: Int): List<Candidate> {
        queried += raw
        return candidatesOf(raw).take(limit).mapIndexed { i, w ->
            // score 倒序:让「表里靠前的候选」在依赖 score 排序的地方也靠前
            Candidate(word = w, pinyin = raw, kind = CandidateKind.EXACT, score = -(i.toLong()))
        }
    }

    /**
     * 查表 + 前缀。
     *
     * 精确命中排在前面(与旧引擎「整串精确 → 前缀」的次序一致),前缀按表的顺序追加,
     * 并**去重** —— 一个词可能既精确命中又是别键的前缀。
     */
    private fun candidatesOf(raw: String): List<String> {
        if (raw.isEmpty()) return emptyList()
        val out = LinkedHashSet<String>()
        // **去撇字母相等**:旧引擎的匹配键是"把 `'` 全部去掉之后"的串,
        // 所以 `ni'hao`、`nihao` 是同一个键。这不是可有可无的细节 ——
        // 用户敲 `nihao`(不带分节)必须能中 `ni'hao` 的词,否则「你好」永远出不来。
        //
        // 我第一版只 trimEnd(尾部)分节,于是 `nihao` 前缀匹配不到 `ni'hao`,
        // 直接导致「中文输入字母进缓冲空格上屏首选」等四条测试红。
        val q = raw.filter { it != '\'' }
        if (q.isEmpty()) return emptyList()
        for ((key, words) in table) {
            val k = key.filter { it != '\'' }
            // **两种命中都要过分节位检查**。
            //
            // 我第一版让 `k == q`(精确)直接收下、只给前缀加分节位检查,结果
            // `xi'an` 会把去撇后同形的 `xian→先` 也带出来 —— 而这条测试要的正是
            // 「内部分节符让 xi'an 只中西安、不中 xian 的先」。
            //
            // 症结在于:去撇之后 `xi'an` 与 `xian` 的字母串完全一样,光看去撇形式
            // 区分不出「用户真的敲了 xian」还是「敲的是 xi'an」——**分节符的位置才是
            // 唯一线索**,所以检查必须拿带撇的原串 `key` 比,不能用去撇的 `k`。
            when {
                k == q && boundaryOk(raw, key) -> out += words
                k.length > q.length && k.startsWith(q) && prefixBoundaryOk(raw, key) -> out += words
            }
        }
        return out.toList()
    }

    /**
     * 分节位约束:`raw` 与词拼音 `key` 的**已输入部分**之间,必须有音节边界。
     *
     * ## 为什么需要(2026-10-03)
     *
     * 用户敲 `ni` 再按 `v`(= 分节符)后缓冲是 `ni'`。此时:
     * - 全串已输完的 **`ni`** 照中(「你」);
     * - 续前缀只接受**`ni` 之后有音节边界**的词(「你好」来自 `ni'hao`);
     * - 而 `nian` / `niang` / `niao` / `nie` 这些**没有边界**的必须被挡掉 ——
     *   否则分节符没起任何作用,和没按一样。
     *
     * 旧引擎靠词库键的分节位实现(词库键是 `ni'hao` 这种带 `'` 的格式),
     * 替身用同一个办法。
     *
     * 规则:**输入里出现分节符的位置,词拼音必须在同一位置有分节符;**
     * 而且反过来也成立 —— 已输入的最后一个音节之后,词的下一个音节必须从分节处开始。
     * 第二条才是挡掉 `nian` 的关键:
     * `ni'` 的长度(去撇后)是 2,`nian` 的第 2 个音节从下标 2 开始且**前面有分节位**,
     * 而 `ni'` 明确说了「ni 音节到此为止」,所以 `nian` 的 `a` 不能紧接在 `ni` 后面。
     *
     * `raw` 末尾的 `'` 表示「本音节到此为止」,**不**约束全串命中,
     * 只约束后续前缀 —— 所以末尾分节要先剥掉再判前半段。
     */
    private fun prefixBoundaryOk(raw: String, key: String): Boolean {
        val q = raw.filter { it != '\'' }
        if (key.length <= q.length) return boundaryOk(raw, key)
        // 前缀命中(词比输入长):输入末尾的分节符必须落在词的音节边界上。
        // `ni'` 对 `nian`(q=ni,key 下一个字符是 a)⇒ 挡掉;`ni'` 对 `ni'hao`(是 ')⇒ 放行。
        //
        // **只在「词比输入长」时才判末尾分节**:`ni'`(q=ni)对 `nian`(len 4 > 2)会挡,
        // 但对 `ni`(len 2 == 2)是精确命中,末尾分节不约束它 —— 见下面的 when 分支,
        // 它先于本函数被匹配上了。
        // 前缀命中要额外满足:末尾分节后,词的下一个音节必须在分节处开始。
        // `ni'` 对 `nian`(q=ni,key 下一个字符是 a)⇒ 挡;
        // `ni'` 对 `ni'hao`(下一个字符是 ')⇒ 放行。
        if (raw.lastOrNull() == '\'' && key.getOrNull(q.length) != '\'') return false
        return boundaryOk(raw, key)
    }

    private fun boundaryOk(raw: String, key: String): Boolean {
        // **末尾分节把「已输完的这个音节」锁死**:它之后的字符必须落在词的
        // 音节边界上,读作 `nian/niang/niao/nie` 的候选全被挡掉。
        // `ni'` → 只中全串已完的 `ni`(你)与 `ni` 之后**有边界**的词(`ni'hao` → 你好)。
        //
        // 语义来源是 [ImeControllerTest] 那条「末尾分节符立起边界」的断言;
        // 而 [ImeRegistryTest] 那条只验「末尾分节不改变已输完部分」——
        // 它的 `before` 就是 `ni` 的结果,替身在 `ni'` 下同样保留 `你` 与前缀,
        // 两边对「已输完」一致,对「后续前缀」以这条更严格的边界为准。
        val r = raw.trimEnd('\'')
        if (r.isEmpty()) return true
        for (i in r.indices) {
            if (r[i] == '\'' && (i >= key.length || key[i] != '\'')) return false
        }
        // 输入末尾有分节符 ⇒ 词的「下一个音节」必须在分节处开始。
        // `ni'` 对 `nian`(q=ni,后面直接是 a)⇒ 挡掉;`ni'` 对 `ni'hao`(q=ni,后面是 ')⇒ 放行。
        //
        // **只在前缀命中时才判这条**。精确命中(`k == q`)已经走到前一个 when 分支,
        // 这里的 `key.length > q.length` 之所以还不够,是因为带分节的键去撇后
        // 可能**恰好等长**(`xi'an` 去撇是 `xian`,与 `xi'an` 的去撇同长)——
        // 那种情况词已经整个输完了,不该再要求「下一个音节」。第一版没区分
        // 精确/前缀,结果 `xi'an` 精确匹配被自己的末尾分节规则挡掉,
        // 症状是「明明表里有西安却查不出来」。
        return true
    }

    override fun onCommitted(word: String) {
        committed += word
    }

    override val supportsWordRemoval: Boolean get() = false

    override fun removeUserWord(word: String): Boolean {
        removed += word to false
        return false
    }

    override fun userCount(word: String): Int = 0

    companion object {
        /**
         * 状态机测试用的词典 —— 逐条对应原来那份 `dictLines`。
         *
         * 保留原有的**词频序**(`nian` 900 高于 `niao` 100),因为有几条测试
         * 断言的是「排序后的结果」而不是集合。
         */
        fun common(): FakePinyinInputMethod = FakePinyinInputMethod(
            table = mapOf(
                // 多音节键带分节符,镜像真实词库格式(ni'hao ≠ nihao,分节位参与匹配)
                "ni" to listOf("你"),
                "hao" to listOf("好"),
                "ni'hao" to listOf("你好"),
                "shi'jie" to listOf("世界"),
                // `xian` 排在 `xi'an` **之前**:候选次序按表的顺序,原词典里
                // `xian→先` 的词频(5000)高于 `xi'an→西安`(3000),而
                // 「不带分隔符歧义照旧:先 词频高排前」这条测试依赖这个次序。
                "xian" to listOf("先"),
                "xi'an" to listOf("西安"),
                "zhong'guo" to listOf("中国"),
                "nian" to listOf("年"),
                "niang" to listOf("娘"),
                "niao" to listOf("鸟"),
                "nie" to listOf("捏"),
            )
        )
    }
}

/**
 * 复制一份并设定 [FakePinyinInputMethod] 的「选后剩余拼音」(给「拼音消费」那几条测试用)。
 *
 * 之所以用「复制」而不是给 `common()` 加参数:`common()` 被大量测试共用,
 * 往签名上加参数会让那些调用点全部变成具名参数 —— 平白噪音。
 *
 * 放在**文件顶层**而不是 companion 里:companion 里的成员扩展函数必须写
 * `FakePinyinInputMethod.withRemainAfterSelect(...)` 才能调,当普通函数用会
 * 「Unresolved reference」(踩过一次)。
 */
fun FakePinyinInputMethod.withRemainAfterSelect(v: String?): FakePinyinInputMethod =
    FakePinyinInputMethod(id = id, table = table, remainAfterSelect = v)
