package dev.hwkbd.ime.ime

import dev.hwkbd.ime.keymap.KeyOut

/**
 * L5 面板的**内容模型**:通用容器 + 一份出厂数据。
 *
 * ## 它是什么(2026-10-19 合并定案)
 *
 * 2026-10-15 定案时,条上方的浮层虚拟键盘叫 L6,状态栏上方那条 L4 溢出行叫 L5。
 * 2026-10-19 用户「L6 和 L5 合并,都作为 L5」:两块合成**一个 L5 面板** ——
 * 原 L6 的 tab 键盘就是 L5,原 L5 溢出行变成它的 tab「场景」(有溢出键时排在最前)。
 * 于是打开一次(键盘图标 / `Sym+Space`)看到的是**整块面板**,不再有 L5→L6 两级。
 *
 * ## 为什么是「容器 + 数据」而不是写死几个页
 *
 * 面板要能加 tab、加页、加键,这些都是**内容**;而「当前在哪个 tab/哪一页、
 * 翻页怎么夹取、到头怎么停」是**状态机**。两者混在一起写,加一页就要动状态机,
 * 于是每次加内容都有一处能改坏翻页。⇒ 拆开:本文件只有数据与翻页规则。
 *
 * ## 键的输出复用 [KeyOut]
 *
 * 键帽 = 自由标签 + 输出(文本或功能键),与 L4 面板同一套语言
 * (见 `dev.hwkbd.ime.keymap.L4Key`)—— 所以面板的键能直接走
 * [ImeController] 已有的上屏通路,不需要第二套「这个键干什么」的分支。
 */
/**
 * 一颗键 + **占几格网格**。
 *
 * [span] 只对 [L5Layout.GRID] 有意义(2026-10-19 用户「内容可跨网格增长」):
 * 所有 GRID tab 共用 [L5Ui.GRID_COLUMNS] 列的基础网格,短键占 1 格,
 * `@hotmail.com` 这类长串按长度跨 2–3 格;换行/分页都按「格」算,
 * 于是不同 tab 的键边缘对齐在同一套列上。
 */
data class L5Key(val label: String, val out: KeyOut, val span: Int = 1)

/** 一页键帽。分页由内容决定(页宽 × 页高),不是固定切片。 */
data class L5Page(val keys: List<L5Key>)

/**
 * tab 内键格的排布(2026-10-16 剪贴板 tab 引入)。
 * - [GRID]:所有 tab 共用 [L5Ui.GRID_COLUMNS] 列基础网格(符号盘 / 场景键);
 *   短键占 1 格,长键按 [L5Key.span] 跨 2–3 格;
 * - [ROWS]:每行 1 颗、整行宽(剪贴板:每条一行,长文本要宽度)。
 */
enum class L5Layout { GRID, ROWS }

/**
 * 一个 tab:标题 + 若干页 + 键格排布。翻页只在本 tab 内(2026-10-15 定「tab 内翻页」)。
 *
 * [columns] 只对 [L5Layout.GRID] 有意义:所有出厂 GRID tab 统一用
 * [L5Ui.GRID_COLUMNS] 列(最小网格),长键用 [L5Key.span] 跨格;
 * 不再给场景 tab 单独改列数 —— 列数一变,tab 之间的键就对不齐了。
 */
data class L5Tab(
    val id: String,
    val label: String,
    val pages: List<L5Page>,
    val layout: L5Layout = L5Layout.GRID,
    val columns: Int = 8,
)

/**
 * L5 的打开状态与页码(空 = 关闭)。
 *
 * ## 为什么是「开着才有的对象」而不是两个字段
 *
 * `tabIndex` / `pageIndex` 在关闭时**没有意义**,而两个裸字段会诱使人
 * 在关闭态去读它们(读到上一次的位置,于是「关掉再打开回到上次那页」变成
 * 默认行为而不是明确选择)。⇒ 关闭 = null,打开 = 一个对象,页码与它同生共死。
 */
class L5Ui(
    val tabs: List<L5Tab>,
    var tabIndex: Int = 0,
    var pageIndex: Int = 0,
) {
    /** 当前 tab;**页码夹取**在这里做,别处不再各自夹一遍。 */
    val tab: L5Tab
        get() = tabs[tabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))]

    /** 当前页。 */
    val page: L5Page
        get() = tab.pages[pageIndex.coerceIn(0, (tab.pages.size - 1).coerceAtLeast(0))]

    val pageCount: Int get() = tab.pages.size

    /** 面板里这一页要画的键。 */
    fun pageKeys(): List<L5Key> = page.keys

    /**
     * 上下翻页,**本 tab 内**。
     *
     * 到头**不循环**:翻到底再往上翻停住 —— 循环会让「一直往上滑」永远停不下来,
     * 而翻页是用来找某一页的,不是在循环浏览。
     *
     * @return 是否真的翻了(调用方据此决定要不要给反馈动画)
     */
    fun stepPage(delta: Int): Boolean {
        if (tabs.isEmpty() || tab.pages.isEmpty()) return false
        val next = (pageIndex + delta).coerceIn(0, tab.pages.size - 1)
        if (next == pageIndex) return false
        pageIndex = next
        return true
    }

    /** 切 tab:页码**归零** —— 上一 tab 的第 3 页在下一 tab 里没有意义。 */
    fun selectTab(index: Int) {
        if (tabs.isEmpty()) return
        tabIndex = index.coerceIn(0, tabs.size - 1)
        pageIndex = 0
    }

    /**
     * 换场景键(原 L5 溢出行),**保留当前 tab** —— 换输入框/换语言时键序变了,
     * 不该把用户弹回第 0 个 tab;但停在场景 tab 时页号归零(键序变了,旧的第 N 页没有意义,
     * 与旧 L5 行换键序回第 0 页同一条规则)。
     *
     * 场景键为空(或全被 [allowText] 滤掉)时**整个 tab 消失**;当前正停在该 tab,
     * 就落到夹取后的位置并回第 0 页。([dev.hwkbd.ime.ime.ImeController.setOverflowCut] 用它原地刷新。)
     */
    fun withScene(keys: List<L5Key>, allowText: (String) -> Boolean = allowAll): L5Ui {
        val activeId = activeTabId()
        val others = tabs.filter { it.id != SCENE_TAB_ID }
        val scene = sceneTab(keys, allowText)
        val newTabs = if (scene != null) listOf(scene) + others else others
        return rebuilt(newTabs, activeId, pageIndex)
    }

    /**
     * 换一套剪贴板内容,**保留当前 tab/页号** —— 剪贴板一变不该把用户弹回第 0 个 tab
     * ([dev.hwkbd.ime.ime.ImeController.setL5Clipboard] 用它原地刷新)。
     *
     * 没有条目(或全被 [allowText] 滤掉)时整个 tab 消失;当前正停在该 tab 就回退到
     * 夹取后的位置并回第 0 页。
     */
    fun withClipboard(items: List<ClipItem>, allowText: (String) -> Boolean = allowAll): L5Ui {
        val activeId = activeTabId()
        val others = tabs.filter { it.id != CLIP_TAB_ID }
        val clip = clipboardTab(items, allowText)
        val newTabs = if (clip != null) others + clip else others
        return rebuilt(newTabs, activeId, pageIndex)
    }

    /**
     * 用一套重算后的 tabs 替换内容,**尽量按 id 保留当前 tab 与页号**。
     *
     * 面板开着时改「中文全角」开关,字符盘要与物理 L2/L3 同步换一套(见
     * [dev.hwkbd.ime.ime.ImeController.refreshL5Content]);这与 [withScene]/[withClipboard]
     * 的「保留用户位置」是同一条规则。
     */
    fun withTabs(newTabs: List<L5Tab>): L5Ui = rebuilt(newTabs, activeTabId(), pageIndex)

    private fun activeTabId(): String? =
        tabs.getOrNull(tabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)))?.id

    /**
     * 用新 tabs 重建:尽量按 id 保留当前 tab(动态 tab 增删会让下标整体漂移)。
     * 当前 tab 消失时落到夹取后的位置并回第 0 页;页码变化时由 [clamped] 夹住。
     */
    private fun rebuilt(newTabs: List<L5Tab>, activeId: String?, wantedPage: Int): L5Ui {
        if (newTabs.isEmpty()) return L5Ui(newTabs)
        val keep = newTabs.indexOfFirst { it.id == activeId }
        val index = if (keep >= 0) keep else tabIndex.coerceIn(0, newTabs.size - 1)
        val page = if (keep >= 0) wantedPage else 0
        return L5Ui(newTabs, index, page).clamped()
    }

    /** 把 tab/页号夹到新范围(内容变短时 pageIndex 可能越界)。 */
    private fun clamped(): L5Ui = apply {
        if (tabs.isEmpty()) return@apply
        tabIndex = tabIndex.coerceIn(0, tabs.size - 1)
        val pages = tabs[tabIndex].pages.size
        pageIndex = if (pages == 0) 0 else pageIndex.coerceIn(0, pages - 1)
    }

    companion object {
        /** 场景 tab 的 id:原 L5 溢出行(content = L4 装不下的尾巴)。 */
        const val SCENE_TAB_ID = "scene"

        /** 剪贴板 tab 的 id。 */
        const val CLIP_TAB_ID = "clip"

        /**
         * 出厂内容(2026-10-15,2026-10-19 并入场景):场景 + 两个字符盘 + 剪贴板。
         *
         * ## 选这两类的理由
         *
         * 物理层 L1/L2/L3 已能敲出字母、数字、常见符号;L4 行按「L1-L3 敲不出
         * 才上屏」的尺子只放少量补差键。虚拟键盘的用武之地是**敲不出的成片字符**:
         * 标点块、技术符号 —— 这两类也是真机上最常被抱怨「打不出」的。
         * **不做「控制」tab**(2026-10-19 用户「不需要控制tab」):Tab/Esc/Enter
         * 等控制键由物理键或 L3 承担,面板只留字符盘。
         *
         * 标点/技术各两页 ⇒ 上下翻页有东西可翻(单页的面板验不了翻页)。
         *
         * ## 无键的 tab 不出现(2026-10-19 用户定案)
         *
         * [allowText] 按当前输入框的合法字符集过滤文本键(密码只留可打印 ASCII、
         * 数字类只留数字与该类型放行的符号,见 [ImeController]);滤完没有键的 tab
         * **整个不显示**。场景键/剪贴板为空时同理 —— 面板上不再有「(无溢出键)」
         * 这类占位页。功能键([KeyOut.Special])不是字符,不参与过滤。
         *
         * ## L1-L3 已能敲出的字符不进面板(2026-10-19 用户定案)
         *
         * [producible] 是当前语言 + 全角开关下 L1-L3 真正能敲出的取值(与 L4 共用
         * [dev.hwkbd.ime.strategy.ImeRegistry.producible]);出厂字符盘里同字的键直接
         * 滤掉,不再在面板上重复摆物理键已经能敲出的字。场景 tab 已由 L4 的同一条去重筛过;
         * 剪贴板是**整段文本**的快捷入口,不按单字符参与这把筛。
         */
        fun factory(
            clipItems: List<ClipItem> = emptyList(),
            sceneKeys: List<L5Key> = emptyList(),
            allowText: (String) -> Boolean = allowAll,
            producible: Set<String> = emptySet(),
        ): L5Ui = L5Ui(
            tabs = buildList {
                sceneTab(sceneKeys, allowText)?.let(::add)
                addAll(factoryTabs(allowText, producible))
                clipboardTab(clipItems, allowText)?.let(::add)
            },
        )

        /** 不按输入框过滤(纯内容/测试的默认)。 */
        private val allowAll: (String) -> Boolean = { true }

        /** 文本键要过 [allowText]、非空且不在 [producible] 里;功能键不是字符,始终保留。 */
        private fun L5Key.allowedBy(
            allowText: (String) -> Boolean,
            producible: Set<String> = emptySet(),
        ): Boolean = when (val o = out) {
            is KeyOut.Text -> o.text.isNotEmpty() && o.text !in producible && allowText(o.text)
            is KeyOut.Special -> true
        }

        private fun t(s: String) = L5Key(s, KeyOut.Text(s))
        private fun page(vararg keys: L5Key) = L5Page(keys.toList())
        private fun chars(s: String) = page(*s.map { t(it.toString()) }.toTypedArray())

        /**
         * 场景 tab = **原 L5 溢出行**(L4 装不下的尾巴,见 [ImeController.setOverflowCut])。
         * 与符号盘共用 [GRID_COLUMNS] 列网格;长域名/短语按 [gridSpan] 跨格,
         * 换行/分页都按格算(每页 [SCENE_ROWS_PER_PAGE] 行)。没有可显示的键时返回 null。
         */
        private fun sceneTab(keys: List<L5Key>, allowText: (String) -> Boolean): L5Tab? {
            val visible = keys.filter { it.allowedBy(allowText) }
                .map { it.copy(span = gridSpan(it.label)) }
            if (visible.isEmpty()) return null
            return L5Tab(
                SCENE_TAB_ID, "场景",
                paginateGrid(visible, GRID_COLUMNS, SCENE_ROWS_PER_PAGE),
                columns = GRID_COLUMNS,
            )
        }

        /** 长内容跨几格:短键 1 格,`@hotmail.com` 这类长串 2 格,再长 3 格(封顶 [GRID_COLUMNS])。 */
        private fun gridSpan(label: String): Int = when {
            label.length <= 6 -> 1
            label.length <= 12 -> 2
            else -> 3
        }.coerceAtMost(GRID_COLUMNS)

        /**
         * 按**网格格数**分页(不是按按键颗数):一行排到 [columns] 格就换行,
         * 满 [rowsPerPage] 行就换页。长键跨格时不会被页边界劈成两半。
         */
        private fun paginateGrid(keys: List<L5Key>, columns: Int, rowsPerPage: Int): List<L5Page> {
            if (keys.isEmpty()) return emptyList()
            val pages = mutableListOf<L5Page>()
            var page = mutableListOf<L5Key>()
            var row = 0
            var col = 0
            for (key in keys) {
                val span = key.span.coerceIn(1, columns)
                // 本行剩余格放不下这颗长键 ⇒ 先换行(不要把 span 压小,否则又裁字)
                if (col > 0 && col + span > columns) {
                    row++
                    col = 0
                }
                if (row >= rowsPerPage) {
                    pages += L5Page(page)
                    page = mutableListOf()
                    row = 0
                    col = 0
                }
                page += key
                col += span
                if (col >= columns) {
                    row++
                    col = 0
                }
            }
            if (page.isNotEmpty()) pages += L5Page(page)
            return pages
        }

        private fun factoryTabs(
            allowText: (String) -> Boolean,
            producible: Set<String>,
        ): List<L5Tab> = listOfNotNull(
            buildTab(
                "punct", "标点",
                listOf(
                    chars("，。！？：；…—（）《》【】「」“”‘’～·"),
                    chars("＋－×÷＝≠≈±∞√°′″№§¶†"),
                ),
                allowText,
                producible,
            ),
            buildTab(
                "tech", "技术",
                listOf(
                    chars("αβγδεζηθλμπσφωΔΣΩ"),
                    chars("{}[]()<>|\\"),
                ),
                allowText,
                producible,
            ),
        )

        /** 滤掉本框非法 / L1-L3 已能敲出的键;空页丢弃,整 tab 没键就返回 null。 */
        private fun buildTab(
            id: String,
            label: String,
            pages: List<L5Page>,
            allowText: (String) -> Boolean,
            producible: Set<String>,
        ): L5Tab? {
            val visiblePages = pages
                .map { p -> L5Page(p.keys.filter { it.allowedBy(allowText, producible) }) }
                .filter { it.keys.isNotEmpty() }
            if (visiblePages.isEmpty()) return null
            return L5Tab(id, label, visiblePages)
        }

        /** 剪贴板 tab:每条一行(2026-10-16 用户「每条占用一行 显示最近十条」)。 */
        private fun clipboardTab(items: List<ClipItem>, allowText: (String) -> Boolean): L5Tab? {
            val visible = items.filter { it.text.isNotEmpty() && allowText(it.text) }
            if (visible.isEmpty()) return null
            val keys = visible.map { L5Key(clipLabel(it), KeyOut.Text(it.text)) }
            return L5Tab(
                CLIP_TAB_ID, "剪贴板",
                keys.chunked(CLIP_ROWS_PER_PAGE).map { L5Page(it) },
                L5Layout.ROWS,
            )
        }

        /**
         * 所有 [L5Layout.GRID] tab 共用的基础网格列数。
         * 短键占 1 格;长键用 [L5Key.span] 跨 2–3 格,所以列数不必再按 tab 改 ——
         * 8 列是「最小网格」:单格宽 = 屏宽/8,长内容靠跨格而不是换列数。
         */
        const val GRID_COLUMNS = 8

        /** 场景 tab 每页两行(与旧 L5 溢出行的高度手感一致);行数满后换页。 */
        private const val SCENE_ROWS_PER_PAGE = 2

        /** 每条占一行,一页放这么多行(10 条分两页,与出厂 tab「每 tab 两页」手感一致)。 */
        private const val CLIP_ROWS_PER_PAGE = 5

        /**
         * 剪贴板条目的键帽文字:**敏感内容遮罩**,普通内容截断到 [CLIP_LABEL_MAX]。
         * 敏感项点击仍上屏全文 —— 遮罩防的是「面板上明文摆着密码」,不是禁用。
         */
        private fun clipLabel(item: ClipItem): String = when {
            item.sensitive -> "••••"
            item.text.length <= CLIP_LABEL_MAX -> item.text
            else -> item.text.take(CLIP_LABEL_MAX) + "…"
        }

        /** 一行键帽的字数上限。 */
        private const val CLIP_LABEL_MAX = 24
    }
}
