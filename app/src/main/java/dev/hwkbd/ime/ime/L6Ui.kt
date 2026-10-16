package dev.hwkbd.ime.ime

import dev.hwkbd.ime.keymap.KeyOut

/**
 * L6 虚拟键盘的**内容模型**:通用容器 + 一份出厂数据。
 *
 * ## 它是什么(2026-10-15 用户「空闲状态 sym+space 打开虚拟键盘,这是 L6 键盘,
 * 支持多个 tab 页和上下滑动翻页」)
 *
 * 条上方的浮层面板:敲 `Sym+Space` 开/关,**仅空闲态**,里面的键点一下直接上屏。
 *
 * ## 为什么是「容器 + 数据」而不是写死三个页
 *
 * 面板要能加 tab、加页、加键,这些都是**内容**;而「当前在哪个 tab/哪一页、
 * 翻页怎么夹取、到头怎么停」是**状态机**。两者混在一起写,加一页就要动状态机,
 * 于是每次加内容都有一处能改坏翻页。⇒ 拆开:本文件只有数据与翻页规则。
 *
 * ## 键的输出复用 [KeyOut]
 *
 * 键帽 = 自由标签 + 输出(文本或功能键),与 L4/L5 面板同一套语言
 * (见 `dev.hwkbd.ime.keymap.L4Key`)—— 所以虚拟键盘的键能直接走
 * [ImeController] 已有的上屏通路,不需要第二套「这个键干什么」的分支。
 */
data class L6Key(val label: String, val out: KeyOut)

/** 一页键帽。分页由内容决定(页宽 × 页高),不是固定切片。 */
data class L6Page(val keys: List<L6Key>)

/**
 * tab 内键格的排布(2026-10-16 剪贴板 tab 引入)。
 * - [GRID]:每行 `L6PanelView.COLUMNS` 颗等宽键(出厂符号盘);
 * - [ROWS]:每行 1 颗、整行宽(剪贴板:每条一行,长文本要宽度)。
 */
enum class L6Layout { GRID, ROWS }

/** 一个 tab:标题 + 若干页 + 键格排布。翻页只在本 tab 内(2026-10-15 用户定「tab 内翻页」)。 */
data class L6Tab(
    val id: String,
    val label: String,
    val pages: List<L6Page>,
    val layout: L6Layout = L6Layout.GRID,
)

/**
 * L6 的打开状态与页码(空 = 关闭)。
 *
 * ## 为什么是「开着才有的对象」而不是两个字段
 *
 * `tabIndex` / `pageIndex` 在关闭时**没有意义**,而两个裸字段会诱使人
 * 在关闭态去读它们(读到上一次的位置,于是「关掉再打开回到上次那页」变成
 * 默认行为而不是明确选择)。⇒ 关闭 = null,打开 = 一个对象,页码与它同生共死。
 */
class L6Ui(
    val tabs: List<L6Tab>,
    var tabIndex: Int = 0,
    var pageIndex: Int = 0,
) {
    /** 当前 tab;**页码夹取**在这里做,别处不再各自夹一遍。 */
    val tab: L6Tab
        get() = tabs[tabIndex.coerceIn(0, (tabs.size - 1).coerceAtLeast(0))]

    /** 当前页。 */
    val page: L6Page
        get() = tab.pages[pageIndex.coerceIn(0, (tab.pages.size - 1).coerceAtLeast(0))]

    val pageCount: Int get() = tab.pages.size

    /** 面板里这一页要画的键。 */
    fun pageKeys(): List<L6Key> = page.keys

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
     * 换一套剪贴板内容的 tabs,**保留当前 tab/页号** —— 剪贴板一变不该把用户弹回第 0 个 tab
     * ([dev.hwkbd.ime.ime.ImeController.setL6Clipboard] 用它原地刷新)。
     */
    fun withClipboard(items: List<ClipItem>): L6Ui =
        L6Ui(tabs = factoryTabs() + clipboardTab(items), tabIndex = tabIndex, pageIndex = pageIndex)

    companion object {
        /**
         * 出厂内容(2026-10-15):三个 tab,各两页,覆盖「打键盘最费事的那些字符」。
         *
         * ## 选这三类的理由
         *
         * 物理层 L1/L2/L3 已能敲出字母、数字、常见符号;L4 行按「L1-L3 敲不出
         * 才上屏」的尺子只放少量补差键。虚拟键盘的用武之地是**敲不出的成片字符**:
         * 标点块、技术符号、控制与箭头 —— 这三类也是真机上最常被抱怨「打不出」的。
         *
         * 每个 tab 两页 ⇒ 上下翻页有东西可翻(单页的面板验不了翻页)。
         *
         * ## 功能键走 [KeyOut.Special],不是字面文本
         *
         * 「Tab」「Esc」当文本敲进去就是两个英文单词 —— 与 L3 层「空格/回车/删除
         * 保持原功能」同一类错误。功能键的 keycode 解析集中在
         * [KeyOut.SPECIAL_CODES],面板的键照那份走。
         */
        fun factory(clipItems: List<ClipItem> = emptyList()): L6Ui =
            L6Ui(tabs = factoryTabs() + clipboardTab(clipItems))

        private fun t(s: String) = L6Key(s, KeyOut.Text(s))
        private fun k(label: String, name: String) = L6Key(label, KeyOut.Special(name))
        private fun page(vararg keys: L6Key) = L6Page(keys.toList())
        private fun chars(s: String) = page(*s.map { t(it.toString()) }.toTypedArray())

        private fun factoryTabs(): List<L6Tab> = listOf(
            L6Tab(
                "punct", "标点",
                listOf(
                    chars("，。！？：；…—（）《》【】「」“”‘’～·"),
                    chars("＋－×÷＝≠≈±∞√°′″№§¶†"),
                ),
            ),
            L6Tab(
                "tech", "技术",
                listOf(
                    chars("αβγδεζηθλμπσφωΔΣΩ"),
                    chars("{}[]()<>|\\"),
                ),
            ),
            L6Tab(
                "control", "控制",
                listOf(
                    chars("←→↑↓⇄↔⇧⇩"),
                    page(
                        k("Tab", "tab"), k("Esc", "esc"), k("Enter", "enter"), k("Del", "del"),
                        k("Home", "move_home"), k("End", "move_end"),
                        k("PgUp", "pgup"), k("PgDn", "pgdn"),
                    ),
                ),
            ),
        )

        /** 剪贴板 tab:每条一行(2026-10-16 用户「每条占用一行 显示最近十条」)。 */
        private fun clipboardTab(items: List<ClipItem>): L6Tab {
            val keys = items.map { L6Key(clipLabel(it), KeyOut.Text(it.text)) }
            val pages = if (keys.isEmpty()) {
                // 空态也给一页(面板不该是白板):一个不可用的占位键。
                listOf(L6Page(listOf(L6Key("(剪贴板为空)", KeyOut.Text("")))))
            } else {
                keys.chunked(CLIP_ROWS_PER_PAGE).map { L6Page(it) }
            }
            return L6Tab("clip", "剪贴板", pages, L6Layout.ROWS)
        }

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