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
     * `consumed` 超过 raw 的长度 ⇒ 空串。
     *
     * 宁可全清,也不能留半个词:留 `"ni"` 那样用户会以为按键没生效。
     */
    @Test
    fun `consumed 超过长度时给空串`() {
        assertEquals("", rawAfterConsuming("nihao", 99))
        assertEquals("", rawAfterConsuming("ni", 5))
    }

    // ---------------- 分节符的坐标桥

    /**
     * [consumed] 是**引擎坐标**:喂给 librime 之前 `'` 被剥掉了。
     * 所以 `ni'hao` 吃 2 个字符,切点落在下标 2 —— 那正好是一个分节符。
     */
    @Test
    fun `切点正好落在分节符上时那个分节符一起丢`() {
        // 「那个分节符标着上一个音节到此为止」——它属于**被吃掉**的那一段。
        // 留着会得到 "'hao",下一次 query 拿到的是以分节符开头的串。
        assertEquals("hao", rawAfterConsuming("ni'hao", 2))
    }

    /** 中间的分节符要**保留** —— 它标着「这里是两段」,下一轮 query 靠它定界。 */
    @Test
    fun `中间的分节符保留`() {
        // "ni'ha'o" 吃掉 ni'ha 四个字符 ⇒ 只剩 o,前面那个分节符已被吃掉。
        assertEquals("o", rawAfterConsuming("ni'ha'o", 4))
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