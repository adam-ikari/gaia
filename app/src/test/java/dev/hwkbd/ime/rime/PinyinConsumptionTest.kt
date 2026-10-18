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
     * `consumed` 超过 raw 的长度(含撇的下标也按 raw.length 夹)⇒ 空串。
     *
     * 宁可全清,也不能留半个词:留 `"ni"` 那样用户会以为按键没生效。
     */
    @Test
    fun `consumed 超过长度时给空串`() {
        assertEquals("", rawAfterConsuming("nihao", 99))
        assertEquals("", rawAfterConsuming("ni", 5))
        assertEquals("", rawAfterConsuming("ni'hao", 6)) // 含撇长度 = 6
    }

    // ---------------- 分节符的坐标桥

    /**
     * [consumed] 是**含撇下标**(喂撇定案:引擎覆盖段把撇算进门,
     * `ni'hao` 的「你」end=3,探针实测)。所以 `ni'hao` 吃 2 ⇒ 切点落在下标 2 ——
     * 那正好是一个分节符,这个用例钉的是「切点落撇丢撇」的兜底
     * (正常引擎 consume 不会指到撇上,见 [rawAfterConsuming] 的病态输入说明)。
     */
    @Test
    fun `切点正好落在分节符上时那个分节符一起丢`() {
        // 留着会得到 "'hao",下一次 query 拿到的是以分节符开头的串。
        assertEquals("hao", rawAfterConsuming("ni'hao", 2))
    }

    /**
     * **这条钉 2026-10-17 喂撇定案后的引擎 consumption 账**(探针,同族):
     *
     * ```
     * run.sh "zhong'guo" 选「中古」(index 8)→ consumed=8,剩 "o"
     * run.sh "ni'hao"    选「你」  (index 4)→ consumed=3,剩 "hao"
     * ```
     *
     * 覆盖段把撇算进end(你 in `ni'hao` = [0,3))、两音节间的撇也在段中
     * (中古 in `zhong'guo` = [0,8))。
     * 2026-10-17 上半场那版「数非撇字符」的预期(4/2)是跳撇时代的账,
     * 前提已被推翻;本测试恢复 2026-10-06 时代的值,但口径的证明今天才补严:
     * **不是**「坐标天生含撇」,而是「喂撇了,喂串 == raw,坐标才含撇」。
     */
    @Test
    fun `坐标含撇 覆盖段把撇一起算`() {
        // 探针真实锚点:"zhong'guo" 选「中古」= consumed=8 ⇒ 剩 "o"。
        // 尾撇属于覆盖段(参「你」end=3),所以 中=zhong' ⇒ 6、中古=zhong'gu ⇒ 8。
        assertEquals("guo", rawAfterConsuming("zhong'guo", 6))
        assertEquals("o", rawAfterConsuming("zhong'guo", 8))
        // 切点恰好压撇 / 压在半节字母上的兜底:丢撇、方向不变
        assertEquals("guo", rawAfterConsuming("zhong'guo", 5)) // 切点落在撇上,撇一起丢
        assertEquals("uo", rawAfterConsuming("zhong'guo", 7))
        // 全吃干净 ⇒ 空(9 = "zhong'guo" 的含撇长度)
        assertEquals("", rawAfterConsuming("zhong'guo", 9))
    }

    /**
     * **跨撇消费:引擎覆盖端点越过撇,剩余串回到字母起点。**
     *
     * 探针同族推算(`ni'si'ji` = n i ' s i ' j i):
     * 「吃掉 ni'si」⇒ end=6(含撇)⇒ 剩 `"ji"`;「只吃 ni」⇒ end=3 ⇒ 剩 `"si'ji"`。
     * 2026-10-17 喂撇前的错误预期是 eaten=4/2(去撇计数)——
     * 那条 bug(`ni'si'ji` 吃 4 切出 `"i'ji"`)在喂撇后**不存在**了:引擎自己把撇算进球门。
     */
    @Test
    fun `跨撇消费 覆盖端点越过撇`() {
        assertEquals("ji", rawAfterConsuming("ni'si'ji", 6))
        assertEquals("si'ji", rawAfterConsuming("ni'si'ji", 3))
        // 探针实测的原证:「你」覆盖 ni' ⇒ 3 ⇒ 剩 ha'o
        assertEquals("ha'o", rawAfterConsuming("ni'ha'o", 3))
    }

    /**
     * 切点落在撇上 ⇒ 那个撇跟着丢;再往后的撇要**保留**(它标着「这里是两段」)。
     *
     * 口径(2026-10-17 喂撇定案):`ni'ha'o` 里「吃掉 `ni'ha'`」对应含撇下标,
     * 必须数上两个撇 ⇒ 下标 6,不是 4/5。生产族里「你」覆盖 `ni'` ⇒ consumed=3
     * ⇒ 剩 `"ha'o"`,后面的撇原样保留(探针同族实测)。
     * (中间 2026-10-17 上半场还试过「4」的「非撇计数」口径,一并废除。)
     */
    @Test
    fun `切点落在撇上撇一起丢 后面的撇保留`() {
        assertEquals("o", rawAfterConsuming("ni'ha'o", 6))
        assertEquals("ha'o", rawAfterConsuming("ni'ha'o", 3))
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