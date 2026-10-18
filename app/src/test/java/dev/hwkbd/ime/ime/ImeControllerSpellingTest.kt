package dev.hwkbd.ime.ime

import dev.hwkbd.ime.strategy.SyllableSpelling
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 拼音显示串(自动分隔符)的拼装单测。
 *
 * 2026-10-12 用户「拼音显示需要自动加入分隔符,以当前候选词栏中央的最高优先级
 * 候选词为准」。拼装本身是纯函数([ImeController.displaySpelling]),所以边界
 * (部分覆盖、翻页后的偏移、撇号坐标映射)全部在这里钉住 —— 这些组合在真机上
 * 要换候选翻页才凑得出来,而单测每次构建都跑。
 *
 * 2026-10-14 用户再定:**每一段边界都补撇**。候选覆盖段与剩下的 raw 是两段,
 * 「倪」只覆盖 `ni` 时显示 `ni'hao`(ni 后面需要拆分),不是 `nihao`。
 */
class ImeControllerSpellingTest {

    private fun sp(text: String, start: Int, end: Int) = SyllableSpelling(text, start, end)

    @Test
    fun `full coverage - preedit replaces whole raw`() {
        assertEquals("ni'hao", ImeController.displaySpelling("nihao", sp("ni'hao", 0, 5)))
        assertEquals("xi'an", ImeController.displaySpelling("xian", sp("xi'an", 0, 4)))
        // 单音节整串:没有第二段,不补撇
        assertEquals("hao", ImeController.displaySpelling("hao", sp("hao", 0, 3)))
    }

    @Test
    fun `partial coverage - separator at the covered-tail seam`() {
        // 翻页后中央槽是「你」:只覆盖 "ni",剩下的 "hao" 是另一段 ⇒ 接缝补撇
        // (2026-10-14 用户定:「ni 后面需要拆分」,整串音节结构不丢)
        assertEquals("ni'hao", ImeController.displaySpelling("nihao", sp("ni", 0, 2)))
    }

    @Test
    fun `offset coverage - raw prefix kept`() {
        // 中央槽候选不吃开头(引擎段起点不在 0):前缀原样,接缝同样补撇
        assertEquals("ni'ha'o", ImeController.displaySpelling("nihao", sp("ha'o", 2, 5)))
    }

    /**
     * 引擎坐标**含撇**(2026-10-17 喂撇定案:喂串 == raw,直接下标):
     * 「你」在 `ni'hao` 里覆盖 `ni'` ⇒ [0,3),探针同族实测;尾撇归属覆盖段。
     */
    @Test
    fun `coordinates are direct indices including apostrophe`() {
        assertEquals("ni'hao", ImeController.displaySpelling("ni'hao", sp("ni", 0, 3)))
        assertEquals("ni'hao", ImeController.displaySpelling("ni'hao", sp("ni'hao", 0, 6)))
        // 覆盖段吃掉尾撇前的最后一字:前缀 "ni'ha" + 文本 "o",接缝撇照补
        assertEquals("ni'ha'o", ImeController.displaySpelling("ni'hao", sp("o", 5, 6)))
        // 中间撇随覆盖段一起吃(泥哈 = ni'ha' ⇒ [0,6)):
        // prefix 以撇结尾 ⇒ 文本前不再补缝
        assertEquals("ni'ha'o", ImeController.displaySpelling("ni'ha'o", sp("ni'ha", 0, 6)))
        // raw 以分节符收尾而覆盖端把它算进覆盖段([0,3)):撇随被替换的区间一起消失,
        // 显示串收尾就是文本本身 —— tail 为空,不新增任何接缝
        assertEquals("ni", ImeController.displaySpelling("ni'", sp("ni", 0, 3)))
        // 病态兜底(正常引擎把尾撇算进覆盖段,不会出现 end<=2 的这类切点):
        // end 落在撇前时,撇留在 tail —— displaySpelling 只补缺,不叠双撇
        assertEquals("ni'", ImeController.displaySpelling("ni'", sp("ni", 0, 2)))
    }

    @Test
    fun `empty raw - empty`() {
        assertEquals("", ImeController.displaySpelling("", sp("ni'hao", 0, 5)))
    }

    @Test
    fun `out-of-range span is clamped, not crashing`() {
        // end 超出 raw 长 ⇒ 夹成"全覆盖":整个输入都是这段候选 ⇒ 只剩 preedit,
        // 没有尾巴可接,也就不补撇(引擎真实值恒在界内,这条只保证夹取不炸)。
        assertEquals("ni", ImeController.displaySpelling("nihao", sp("ni", 0, 99)))
        // start 为负 ⇒ 夹回 0:前缀空,覆盖段与尾巴之间补撇
        assertEquals("ni'hao", ImeController.displaySpelling("nihao", sp("ni", -5, 2)))
        // start > end ⇒ 夹成空段 [3,3):preedit 插在"前缀"与"尾巴"之间,两个接缝都补。
        // 垃圾进垃圾出,这里只钉住"不抛异常、不静默吞字"—— 真实引擎不会给出这种区间。
        assertEquals("nih'ni'ao", ImeController.displaySpelling("nihao", sp("ni", 3, 2)))
    }
}
