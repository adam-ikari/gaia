package dev.hwkbd.ime.rime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「按实际选词消费」里唯一的那个纯函数。
 *
 * ## 为什么这里从「剥取」改成了「截断」
 *
 * 上一版有两个纯函数:[unconsumedFromPreview](从 `commit_text_preview` 里把刚选中的词
 * 减掉)与 [rawPrefixOf](把引擎坐标映回 `raw`)。**两个的模型都是错的**,而且错在
 * 真机上天天走的那条路上 —— 2026-10-05 的 CI 日志(真引擎,`nihao` 选第 4 个候选「你」):
 *
 * ```
 * consume: preview="你好" word="你"
 * query#18 from=选词后剩余 raw="n"
 * ```
 *
 * 「你」只覆盖 `ni`,吃掉 2 个字符,缓冲**应该是 `hao`**,却变成了 `n`。
 *
 * 两处错:
 * 1. `commit_text_preview` 里「没被吃掉的那部分」是**汉字**(那段当前高亮的候选),
 *    不是拼音。拿它的长度(1)当拼音长度,就把 5 个字符的缓冲截成了 1 个。
 * 2. 方向也反了 —— librime 的候选永远从当前段起点开始吃,也就是吃**前缀**,
 *    留的是**尾巴**。[rawPrefixOf] 截的却是前缀。
 *
 * 现在只剩一个函数 [rawAfterConsuming],输入是引擎给的**个数**,输出是剩下的 `raw`。
 * 没有减法、没有猜测,所以「单音节恰好对」的那种巧合也一并没了。
 */
class PinyinConsumptionTest {

    // ---------------- rawAfterConsuming

    /**
     * **这条就是 2026-10-05 那个真机 bug 的钉子。**
     *
     * `nihao` 选「你」(只覆盖 `ni`)⇒ 吃掉 2 个字符 ⇒ 剩 `hao`。
     * 旧实现给的是 `n`。
     */
    @Test
    fun `吃掉开头两个字符 剩下的是尾巴`() {
        assertEquals("hao", rawAfterConsuming("nihao", 2))
    }

    /** 吃掉全部 ⇒ 空串(上层据此清空)。空串是合法结果,不是「说不清」。 */
    @Test
    fun `全吃掉得空串`() {
        assertEquals("", rawAfterConsuming("nihao", 5))
    }

    /**
     * **方向**:截的是**前缀**,留的是**尾巴**。
     *
     * 这是最容易写反的一步 —— 上屏的词来自 `nihao` 的**开头**那一段,
     * 所以被吃掉的一定在开头。反过来截会得到 `nih`,缓冲从此与用户敲的拼音不符。
     */
    @Test
    fun `截的是前缀不是后缀`() {
        assertEquals("ao", rawAfterConsuming("nihao", 3))
        assertEquals("o", rawAfterConsuming("nihao", 4))
    }

    /** `consumed <= 0`:引擎说不清(整串被自动提交掉时就是这个值)⇒ 原样返回。 */
    @Test
    fun `consumed 非正数时原样返回`() {
        assertEquals("nihao", rawAfterConsuming("nihao", 0))
        assertEquals("nihao", rawAfterConsuming("nihao", -2))
    }

    /**
     * `consumed` 超过 raw 里**非撇字符**的个数 ⇒ 空串(`ni'hao` 走 6 个同样全清)。
     *
     * 宁可全清,也不能留半个词:留 `"ni"` 那样用户会以为按键没生效。
     */
    @Test
    fun `consumed 超过长度时给空串`() {
        assertEquals("", rawAfterConsuming("nihao", 99))
        assertEquals("", rawAfterConsuming("ni", 5))
        assertEquals("", rawAfterConsuming("ni'hao", 6)) // 只有 5 个非撇字符,6 吃不到
    }

    // ---------------- 分节符的坐标桥

    /**
     * [consumed] 数的是**非撇字符**(喂引擎时 `'` 被跳过,坐标就去撇;
     * 详见 [rawAfterConsuming] 的口径)。`ni'hao` 吃 2 ⇒ 第 2 个非撇字符
     * 是 `i`,切点紧跟其后 —— 正好压在分节符上 ⇒ 撇一起丢。
     */
    @Test
    fun `切点正好落在分节符上时那个分节符一起丢`() {
        // 「那个分节符标着上一个音节到此为止」——它属于**被吃掉**的那一段。
        // 留着会得到 "'hao",下一次 query 拿到的是以分节符开头的串。
        assertEquals("hao", rawAfterConsuming("ni'hao", 2))
    }

    /**
     * **这条钉的是坐标系本身(2026-10-17 梳理定案,2026-10-06 那版口径被推翻)。**
     *
     * `zhong'guo` 选「中古」(覆盖 `zhong` 5 个母音档位的非撇字符)⇒
     * `consumed=5`,正解 `"guo"`。按旧口径(把 consumed 当**含撇下标**切),
     * 同一笔账切在 `'` 前一位 —— 恰好被「切点落撇丢撇」的兜底救场,
     * 所以 5 那一格两种口径同值;**真正的分家在 6/7/8**:
     * 每多留正常字符一格,旧口径都把切点借撇偏移一位、留下多一段残余
     * (6 得 `"guo"` 而正确是 `"uo"`,7 得 `"uo"` 而正确是 `"o"`,8 得 `"o"`
     * 而**正确是 `""` —— 旧口径在这里把已吃干净的事说成「还剩一个 o」)。
     */
    @Test
    fun `坐标数非撇字符 撇不占位`() {
        assertEquals("guo", rawAfterConsuming("zhong'guo", 5)) // 切点落在撇上,撇一起丢
        assertEquals("uo", rawAfterConsuming("zhong'guo", 6))
        assertEquals("o", rawAfterConsuming("zhong'guo", 7))
        assertEquals("", rawAfterConsuming("zhong'guo", 8))   // 8 个非撇字符齐了
    }

    /**
     * **这条钉的是 2026-10-05 那个真机 bug 的姊妹篇:跨撇后切点不偏。**
     *
     * `ni'si'ji`(用户长按 L 插了两个撇)选一个覆盖 `nisi` 的两字候选
     * ⇒ 非撇字符吃了 4 个 ⇒ 正解 `"ji"`。按下标切得 `"i'ji"` ——
     * 剩余串从撇后半位开始,下一轮整串全错。
     *
     * 下方注释里旧口径的账(2026-10-06 版):它引探针 `run.sh "ni'ha'o"`
     * 选「你」consumed=3 佐证「撇算在坐标里」。**那探针把撇喂进了引擎**
     *(`probe.cc` 的 `simulate_key_sequence` 原样喂;luna_pinyin 原生认撇),
     * 给的是含撇坐标系的值 —— 生产跳撇,这两族不可混引。生产里
     * 同一个「你」报的是 `consumed=2`(非撇个数为 2),坐标族对上了,
     * 探针的证词不再成立。(2026-10-10 看过的 raw 断言见 E2E `consume-partial`:
     * 它用 `nihao` 无撇,两种语义同值,做不了这个裁判。)
     */
    @Test
    fun `坐标数非撇字符 跨撇不切错位`() {
        // 下标(non-apostrophe 计数):n=1 i=2 | s=3 i=4 | j=5 i=6 加上两个撇
        assertEquals("ji", rawAfterConsuming("ni'si'ji", 4))   // 跨一个撇
        assertEquals("si'ji", rawAfterConsuming("ni'si'ji", 2)) // 只吃 ni
        // 探针同名场景的生产族口径:你 覆盖 ni ⇒ 2 个非撇字符
        assertEquals("ha'o", rawAfterConsuming("ni'ha'o", 2))
    }
    /** 最后一个非撇字符吃掉后,切的正好是换段撇本身 ⇒ 空串 —— 与「全吃掉」同值。 */
    @Test
    fun `全吃掉得空串 撇不占位`() {
        assertEquals("", rawAfterConsuming("nihao", 5))
    }

    /**
     * 切点落在撇上 ⇒ 那个撇跟着丢;再往后的撇要**保留**(它标着「这里是两段」)。
     *
     * 口径(2026-10-17 梳理):`ni'ha'o` 里「吃掉 `niha`」对应非撇字符 **4**,
     * 切点落在 `a` 后的撇上 ⇒ 撇丢,剩 `"o"`。2026-10-06 那版把它说成
     * 「下标 5」,依据是探针的含撇坐标(已推翻,见上一条测试的说明)。
     * 生产族里「你」覆盖 `ni` ⇒ consumed=2 ⇒ 剩 `"ha'o"`,后面的撇原样保留。
     */
    @Test
    fun `切点落在撇上撇一起丢 后面的撇保留`() {
        assertEquals("o", rawAfterConsuming("ni'ha'o", 4))
        assertEquals("ha'o", rawAfterConsuming("ni'ha'o", 2))
    }

    /** raw 以分节符结尾时,吃掉前面全部音节 ⇒ 空串。 */
    @Test
    fun `raw 以分节符结尾且全吃掉`() {
        assertEquals("", rawAfterConsuming("ni'", 2))
    }

    /** 连续分节符:`PinyinInputMethod.normalize` 会把它们并成一个,这里也不该留半个。 */
    @Test
    fun `切点后的连续分节符一起丢`() {
        assertEquals("hao", rawAfterConsuming("ni''hao", 2))
    }

    /** 空 raw 无从截断,给空串。 */
    @Test
    fun `空 raw 给空串`() {
        assertEquals("", rawAfterConsuming("", 2))
    }
}