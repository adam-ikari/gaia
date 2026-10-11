package dev.hwkbd.ime.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 「候选行通栏」的构建期钉桩。
 *
 * ## 为什么要钉布局文件而不是只跑一次
 *
 * 上滑分区有两个 owner:候选行内起手按 `CandidateBarView.cellWidth = width / 5` 分,
 * 键盘面起手按 `屏宽 / 5` 分(`ImeService.FlyDetector.zoneOf`)。两者**只在布局上相等** ——
 * 编译期没有任何联系,所以谁把 `candidate_row` 的 `paddingStart/End` 加回来,
 * 「手指落在边界窄带选中邻词」这个 bug 就**无声**复发(词心照样命中,常规用例测不到)。
 *
 * 已有的三道闸都需要有人真的跑起来:运行时的一次性 warn log(要有人读 logcat)、
 * E2E 的实测宽断言(只有真机能跑,本机没有 `/dev/kvm`)。这一道跑在
 * `:app:testDebugUnitTest` 里,每次构建都执行,是唯一不需要设备的。
 *
 * 2026-10-10 定案「改布局而不是改数学」(见 ime_root.xml 与 brain `fly-zone-basis`)。
 */
class CandidateRowFullWidthTest {

    private fun layout(): File {
        // 单测的工作目录不保证是模块根:本地是 app/,CI 上可能从仓库根起。
        val rel = listOf("src/main/res/layout/ime_root.xml", "app/src/main/res/layout/ime_root.xml")
        val f = rel.map(::File).firstOrNull { it.isFile }
        assertNotNull("找不到 ime_root.xml(试过 $rel),路径假设已失效", f)
        return f!!
    }

    private fun element(id: String): Element {
        val doc = DocumentBuilderFactory.newInstance().apply {
            // 命名空间感知会让 getAttribute("android:xxx") 取不到值,这里按前缀名比。
            isNamespaceAware = false
        }.newDocumentBuilder().parse(layout())
        val all = doc.getElementsByTagName("LinearLayout")
        for (i in 0 until all.length) {
            val e = all.item(i) as Element
            if (e.getAttribute("android:id") == "@+id/$id") return e
        }
        error("ime_root.xml 里没有 @+id/$id")
    }

    @Test
    fun `candidate row has no horizontal padding - it is full width`() {
        val row = element("candidate_row")
        // 只查横向:竖向内边距不参与分区(通栏要管的是 x 基准)。
        for (a in listOf("android:paddingStart", "android:paddingEnd", "android:paddingLeft", "android:paddingRight", "android:padding"))
            assertFalse("candidate_row 又加回了 $a,两套五等分会错开", row.hasAttribute(a))
        assertEquals("candidate_row 必须 match_parent 才通栏", "match_parent", row.getAttribute("android:layout_width"))
    }

    @Test
    fun `candidate bar fills the row so its measured width equals the screen width`() {
        // 通栏 = 行通栏 **且** 栏铺满行。只断言前者不够:栏若被缩进,cellWidth 照样不等于屏宽/5。
        val bars = layout().readText().let {
            Regex("<dev\\.hwkbd\\.ime\\.ui\\.CandidateBarView[\\s\\S]*?/>").find(it)
        }
        assertNotNull("ime_root.xml 里没有 CandidateBarView 声明", bars)
        val body = bars!!.value
        assertTrue("候选栏要铺满整行(0dp + weight=1),否则实测宽 != 屏宽",
            Regex("""android:layout_width="0dp"""").containsMatchIn(body) &&
                Regex("""android:layout_weight="1"""").containsMatchIn(body))
    }

    @Test
    fun `status row keeps its safe edge - only the candidate row is full width`() {
        // 状态行的 16dp 是躲 Titan 2 底部物理圆角的,与分区无关。它若被一起删掉,
        // 这次改动就顺带改坏了观感;反过来它也提醒读的人:两行左端**不再**垂直对齐。
        val row = element("status_row")
        assertEquals("@dimen/bar_safe_edge", row.getAttribute("android:paddingStart"))
        assertEquals("@dimen/bar_safe_edge", row.getAttribute("android:paddingEnd"))
    }
}
