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
     * [consumed] 是**喂给引擎那串里的下标**,而那串**含** `'`
     * (`PinyinInputMethod.normalize` 只合并连续的、去掉开头的,不剥)。
     * 所以 `ni'hao` 吃 2 ⇒ 切点落在下标 2 —— 那正好是一个分节符。
     *
     * 原文这里写着「喂给 librime 之前 `'` 被剥掉了」,那句是假的,
     * 详见 [rawAfterConsuming] 的说明与下面的 `zhong'guo` 那条。
     */
    @Test
    fun `切点正好落在分节符上时那个分节符一起丢`() {
        // 「那个分节符标着上一个音节到此为止」——它属于**被吃掉**的那一段。
        // 留着会得到 "'hao",下一次 query 拿到的是以分节符开头的串。
        assertEquals("hao", rawAfterConsuming("ni'hao", 2))
    }

    /**
     * **这条钉的是 2026-10-06 修掉的那个真 bug:坐标口径错了。**
     *
     * 探针实测(`tools/rimeprobe/run.sh "zhong'guo"`,9 字符含 1 个撇):
     *
     * ```
     * 选「中古」→ consumed=8,引擎剩下的是 "o"（preedit="中古o"）
     * ```
     *
     * `8` 是**下标**。旧实现数的是**非撇字符**,8 个非撇字符正好把那 8 个
     * 字母数完 ⇒ 返回 `""`,**把 `o` 弄丢**。
     *
     * 两种语义只在 raw 里没有撇时相同,所以单测里那几条(`nihao`、`ni'hao`)
     * 全都同值 —— 这个 bug 在单测里**结构性不可见**,只有加上撇才现形。
     * 项目自己的 E2E 场景断言的 `after === raw_before.slice(consumed)`
     * 本来就是下标语义:断言一直对,只有实现错。
     */
    @Test
    fun `坐标是下标 撇算在里面`() {
        // 下标:0=z 1=h 2=o 3=n 4=g 5=' 6=g 7=u 8=o
        assertEquals("o", rawAfterConsuming("zhong'guo", 8))
        assertEquals("uo", rawAfterConsuming("zhong'guo", 7))
        assertEquals("guo", rawAfterConsuming("zhong'guo", 6))
        assertEquals("guo", rawAfterConsuming("zhong'guo", 5)) // 切点落在撇上,撇一起丢
        // 无撇时两种语义相同 —— 旧实现在这些用例上「看起来一直是对的」
        assertEquals("hao", rawAfterConsuming("nihao", 2))
        assertEquals("", rawAfterConsuming("nihao", 5))
    }

    /**
     * 切点落在撇上 ⇒ 那个撇跟着丢;再往后的撇要**保留**(它标着「这里是两段」)。
     *
     * 2026-10-06 改过:原文是 `rawAfterConsuming("ni'ha'o", 4)` == `"o"`,
     * 建立在「consumed 数的是非撇字符」那个**已被探针否证**的前提上。
     * 引擎实测(`run.sh "ni'ha'o"` 选「你」)= **consumed=3**,而它自己剩下的
     * 输入是 `ha'o` —— 正是 `"ni'ha'o".slice(3)`。撇算在坐标里。
     * 于是「吃掉 ni'ha」对应下标 **5**,不是 4。
     */
    @Test
    fun `切点落在撇上撇一起丢 后面的撇保留`() {
        assertEquals("o", rawAfterConsuming("ni'ha'o", 5))
        // 引擎实测那条:consumed=3 ⇒ 剩 ha'o,撇在切点之前所以原样留下
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