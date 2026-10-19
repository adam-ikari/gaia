package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.strategy.FakePinyinInputMethod
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.PinyinInputMethod
import dev.hwkbd.ime.strategy.withConsumed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * L5 面板的**状态机**断言(2026-10-19 用户「L6 和 L5 合并,都作为 L5」):
 * 原 L6 的 tab 虚拟键盘就是 L5,原 L5 溢出行变成 tab「场景」(有溢出键时排最前)。
 *
 * 定案各钉在这里:仅空闲态开关 / Sym+Space 抢键 / tab 内翻页 / 关闭不写字符 /
 * 场景 tab = L4 装不下的尾巴。面板的绘制与滑动手感在真机上验(本地 E2E),
 * 本类只管**状态**—— 与项目里「状态机纯逻辑、绘制靠真机」的分工一致。
 */
class L5PanelTest {

    /** 敲 Sym(一次性借用 L3)+ 空格:这就是「Sym+Space」这一把键。 */
    private fun ImeController.symSpace() {
        onKeyDown(KeyEvent.KEYCODE_SYM, false, false)
        onKeyUp(KeyEvent.KEYCODE_SYM)
        onKeyDown(KeyEvent.KEYCODE_SPACE, false, false)
        onKeyUp(KeyEvent.KEYCODE_SPACE)
    }

    /** **开** L5:合并后只有两态,按一次即可(2026-10-19)。 */
    private fun ImeController.openL5() {
        cyclePanel() // OFF → L5
    }

    @Test
    fun `空闲态两态开关 OFF L5 OFF`() {
        val r = ImeControllerTestRig()
        val c = r.c
        assertEquals("初始 OFF", PanelMode.OFF, c.state.panelMode)
        assertFalse(c.l5Open)
        c.cyclePanel()
        assertEquals("一按到 L5", PanelMode.L5, c.state.panelMode)
        assertTrue(c.l5Open)
        assertEquals("有场景键时第一个 tab 是场景", L5Ui.SCENE_TAB_ID, c.state.l5!!.tab.id)
        c.cyclePanel()
        assertEquals("再按回 OFF", PanelMode.OFF, c.state.panelMode)
        assertFalse(c.l5Open)
        assertNull(c.state.l5)
    }

    @Test
    fun `Sym+Space 开关面板 与状态行按钮同一通路`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.symSpace()
        assertEquals("Sym+Space 打开 L5", PanelMode.L5, c.state.panelMode)
        assertTrue(c.l5Open)
        assertTrue("不写字符", r.h.commits.isEmpty())

        // 再敲一次同一把键:关面板,仍不写字符
        c.symSpace()
        assertEquals(PanelMode.OFF, c.state.panelMode)
        assertFalse(c.l5Open)
        assertTrue("关闭也不写字符", r.h.commits.isEmpty())
    }

    @Test
    fun `英文态也开`() {
        // 2026-10-15 用户定「两种语言都开」:面板是符号入口,与当前语言无关。
        val r = ImeControllerTestRig()
        val c = r.c
        c.cycleLanguage() // 拼 → En
        assertEquals(dev.hwkbd.ime.strategy.EnglishLanguage.id, c.state.languageId)
        c.openL5()
        assertTrue("英文态同样能开 L5", c.l5Open)
    }

    @Test
    fun `组词中 Sym+Space 仍是空格 不开面板`() {
        // **这条与 `ImeControllerTest` 的 `L3 空格保持原功能` 是一对**:
        // 那条钉的是「面板不存在时 Sym+Space 是空格」,这条钉的是「组词中也是空格」。
        // 空闲态被抢走的那部分由上一条钉住。
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5() // 先打开面板
        assertTrue(c.l5Open)
        c.onKeyDown(KeyEvent.KEYCODE_A, false, false) // 敲个字母 → 组词 → 面板自动收
        assertTrue("组词了", c.state.hasComposition)
        assertFalse("组词开始面板就收掉", c.l5Open)
        assertEquals("a", c.state.raw)

        // 组词中 Sym+Space:走空格的老路
        c.symSpace()
        assertFalse("组词中不开面板", c.l5Open)
    }

    @Test
    fun `面板开着时敲任意字符自动关闭面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        assertTrue(c.l5Open)
        c.onKeyDown(KeyEvent.KEYCODE_N, false, false)
        assertFalse("一敲字就收", c.l5Open)
    }

    @Test
    fun `面板上敲符号直接上屏且面板留着`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        // 中文态标点不进组词缓冲(拼音只组字母 a-z):面板上点一颗「，」=直接上屏。
        // 面板是符号盘 —— 上屏一颗符号后留在原地,好接着点下一颗;组词一开始才收面板。
        c.commitL4Key(KeyOut.Text("，"))
        assertEquals(listOf("，"), r.h.commits)
        assertEquals("不进组词", "", c.state.raw)
        assertTrue("符号上屏后面板留着(接着敲下一颗)", c.l5Open)
    }

    @Test
    fun `英文态面板上敲字符直接上屏`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.cycleLanguage() // En
        c.openL5()
        c.commitL4Key(KeyOut.Text("{}"))
        assertEquals(listOf("{}"), r.h.commits)
        assertEquals("不进组词", "", c.state.raw)
    }

    @Test
    fun `Esc 关面板且不转发给应用`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        c.commitL4Key(KeyOut.Special("esc"))
        assertFalse("面板上的 Esc 收面板", c.l5Open)
        assertTrue("Esc 不转发", r.h.keys.isEmpty())
    }

    @Test
    fun `物理 Sym+Q(Esc)也收面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        assertTrue(c.l5Open)
        // 出厂 L3 的 Q = Esc(照可可扩展字符表):**按住 Sym 再敲 Q** 才是真发 Esc 的手势。
        // 开面板那一下借的 L3 已用掉(见 cyclePanel),不按住 Sym 的话 Q 只是字母 q。
        c.onKeyDown(KeyEvent.KEYCODE_SYM, false, false) // 按住 Sym → L3
        c.onKeyDown(KeyEvent.KEYCODE_Q, false, false) // L3 的 Q = Esc
        c.onKeyUp(KeyEvent.KEYCODE_Q)
        c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertFalse("Sym+Q 收面板", c.l5Open)
        assertTrue("不转发 Esc 给应用", r.h.keys.isEmpty())
    }

    @Test
    fun `面板开着时其他功能键照常转发`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        c.commitL4Key(KeyOut.Special("tab"))
        assertTrue("Tab 仍然转发", r.h.keys.any { it.startsWith(KeyEvent.KEYCODE_TAB.toString()) })
        assertTrue("Tab 不该收面板", c.l5Open)
    }

    @Test
    fun `切语言与换输入框都收面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        c.cycleLanguage()
        assertFalse("切语言收面板", c.l5Open)

        c.openL5()
        assertTrue(c.l5Open)
        c.resetForNewInput()
        assertFalse("换输入框收面板", c.l5Open)
    }

    @Test
    fun `数字框没有可显示键时不打开空面板`() {
        // 2026-10-19 去掉控制 tab 后,数字类可能一个可显示 tab 都没有:
        // 字符盘全被数字白名单滤掉、L4 number 又没有短语。此时不能进入「状态说 L5、
        // 视图取不到 tab」的空面板。
        val r = ImeControllerTestRig()
        val c = r.c
        c.setFieldKind(FieldKind.NUMBER)
        assertFalse("没有可显示键就不打开", c.cyclePanel())
        assertEquals(PanelMode.OFF, c.state.panelMode)
        assertFalse(c.l5Open)
        assertNull(c.state.l5)
    }

    @Test
    fun `场景是唯一 tab 时尾巴装回 L4 会收起面板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.setFieldKind(FieldKind.PHONE)
        c.openL5()
        assertTrue("手机号框只剩场景 tab", c.l5Open)
        c.setOverflowCut(c.l4Keys().size)
        assertFalse("没有可显示 tab 时不留下空面板", c.l5Open)
        assertEquals(PanelMode.OFF, c.state.panelMode)
        assertNull(c.state.l5)
    }

    @Test
    fun `面板开着时方向键翻页与切 tab`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        // 默认场景 tab:测试里 L4 还没测量切点,场景拿到全部 L4 键 ⇒ 只有一页,上下键不动
        assertEquals("默认在场景 tab", 0, c.state.l5!!.tabIndex)
        c.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, false, false)
        assertEquals("场景只有一页,↓ 不动", 0, c.state.l5!!.pageIndex)

        // 标点 tab 出厂两页
        c.l5SelectTab(1)
        assertTrue("出厂每 tab 至少两页(否则翻页无从验)", c.state.l5!!.pageCount >= 2)
        c.onKeyDown(KeyEvent.KEYCODE_DPAD_DOWN, false, false)
        assertEquals("↓ 下一页", 1, c.state.l5!!.pageIndex)
        c.onKeyDown(KeyEvent.KEYCODE_DPAD_UP, false, false)
        assertEquals("↑ 上一页", 0, c.state.l5!!.pageIndex)

        c.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT, false, false)
        assertEquals("→ 下一个 tab", 2, c.state.l5!!.tabIndex)
        c.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT, false, false)
        assertEquals("← 上一个 tab", 1, c.state.l5!!.tabIndex)

        assertTrue("方向键不写字符", r.h.commits.isEmpty())
    }

    @Test
    fun `翻页在 tab 内进行 不循环`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        c.l5SelectTab(1)
        val ui = c.state.l5!!
        val pages = ui.pageCount
        assertTrue("出厂每页至少两页(否则翻页无从验)", pages >= 2)

        assertTrue(c.l5StepPage(1))
        assertEquals(1, c.state.l5!!.pageIndex)
        // 翻到底:再往下不动,且返回 false(面板据此不给反馈)
        while (c.l5StepPage(1)) { /* 走到头 */ }
        assertEquals(pages - 1, c.state.l5!!.pageIndex)
        assertFalse("到底不循环", c.l5StepPage(1))
        assertTrue("往回能翻", c.l5StepPage(-1))
    }

    @Test
    fun `切 tab 时页码归零`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        c.l5SelectTab(1)
        c.l5StepPage(1)
        assertEquals(1, c.state.l5!!.pageIndex)
        c.l5SelectTab(2)
        assertEquals("切 tab 后回第 0 页", 0, c.state.l5!!.pageIndex)
        assertEquals(2, c.state.l5!!.tabIndex)
    }

    @Test
    fun `关着时翻页与切 tab 都是空操作`() {
        val r = ImeControllerTestRig()
        val c = r.c
        assertFalse(c.l5StepPage(1))
        c.l5SelectTab(1)
        assertNull(c.state.l5)
    }

    @Test
    fun `无场景键时不显示场景 tab`() {
        val ui = L5Ui.factory()
        assertTrue("没有溢出键就没有场景 tab", ui.tabs.none { it.id == L5Ui.SCENE_TAB_ID })
        assertEquals("没有场景时第一个 tab 是标点", "punct", ui.tabs.first().id)
    }

    @Test
    fun `溢出键按网格分页 长键跨格`() {
        val keys = (1..20).map { L5Key("k$it", KeyOut.Text("k$it")) }
        val ui = L5Ui.factory(sceneKeys = keys)
        val scene = ui.tabs.first { it.id == L5Ui.SCENE_TAB_ID }
        assertEquals("场景与符号盘共用同一套网格", L5Ui.GRID_COLUMNS, scene.columns)
        // 短键 k1..k20 各占 1 格;8 列 × 2 行 = 16 颗/页
        assertEquals("20 颗 = 16 + 4,两页", 2, scene.pages.size)
        assertEquals(16, scene.pages[0].keys.size)
        assertEquals(4, scene.pages[1].keys.size)
        assertEquals("顺序原样", keys, scene.pages.flatMap { it.keys })

        val long = L5Key("@hotmail.com", KeyOut.Text("@hotmail.com"))
        val longUi = L5Ui.factory(sceneKeys = listOf(long))
        val longKey = longUi.tabs.first { it.id == L5Ui.SCENE_TAB_ID }.pages[0].keys.single()
        assertTrue("长域名跨 2 格以上", longKey.span >= 2)
    }

    @Test
    fun `换场景键保留 tab 但场景页回第 0 页`() {
        val keys = (1..32).map { L5Key("k$it", KeyOut.Text("k$it")) }
        val ui = L5Ui.factory(sceneKeys = keys)
        assertTrue(ui.stepPage(1))
        assertEquals(1, ui.pageIndex)
        val shorter = keys.take(5)
        val next = ui.withScene(shorter)
        assertEquals("仍在场景 tab", 0, next.tabIndex)
        assertEquals("键序变了,场景页回第 0 页", 0, next.pageIndex)
        assertEquals(shorter, next.tabs.first().pages.flatMap { it.keys })
    }

    @Test
    fun `不在场景 tab 时换场景键保留 tab 与页号`() {
        val keys = (1..32).map { L5Key("k$it", KeyOut.Text("k$it")) }
        val ui = L5Ui.factory(sceneKeys = keys)
        ui.selectTab(1)
        assertTrue(ui.stepPage(1))
        val next = ui.withScene(keys.take(5))
        assertEquals("仍在标点 tab", 1, next.tabIndex)
        assertEquals("页码不受影响", 1, next.pageIndex)
    }

    @Test
    fun `setOverflowCut 把 L4 装不下的尾巴放进场景 tab`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        val all = c.l4Keys()
        assertTrue("默认 L4 至少两把(否则这条验不出切点)", all.size > 1)
        c.setOverflowCut(1)
        val scene = c.state.l5!!.tabs.first { it.id == L5Ui.SCENE_TAB_ID }
        assertEquals(
            "场景键 = L4 第 2 把起的尾巴",
            all.drop(1).map { it.label },
            scene.pages.flatMap { it.keys }.map { it.label },
        )
    }

    @Test
    fun `场景键都装进 L4 时隐藏场景 tab`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.openL5()
        val all = c.l4Keys()
        c.setOverflowCut(all.size)
        val ui = c.state.l5!!
        assertTrue("没有尾巴就没有场景 tab", ui.tabs.none { it.id == L5Ui.SCENE_TAB_ID })
        assertTrue("出厂 tab 仍在", ui.tabs.any { it.id == "punct" })
    }

    @Test
    fun `出厂没有控制 tab`() {
        // 2026-10-19 用户「不需要控制tab」:Tab/Esc/Enter 等走物理键或 L3,
        // 面板只留字符盘;出厂内容不再有 KeyOut.Special。
        val tabs = L5Ui.factory().tabs
        assertTrue("控制 tab 已移除", tabs.none { it.id == "control" })
        assertEquals("只剩标点与技术", listOf("punct", "tech"), tabs.map { it.id })
        assertTrue(
            "出厂键全是文本键",
            tabs.flatMap { it.pages }.flatMap { it.keys }.all { it.out is KeyOut.Text },
        )
    }

    @Test
    fun `出厂 tab 每页最多两行网格`() {
        // 2026-10-20 用户「L5的高度固定两行 多的内容需要翻页」:不再预设每盘几页,
        // 而是「先过滤、再按 8 列 × ROWS_PER_PAGE 行的网格分页」,所以只钉两条:
        // 每页有键、且每页占格不超过两行(跨格键也按格算,不能被页边界劈开)。
        val tabs = L5Ui.factory().tabs.filter { it.id in setOf("punct", "tech") }
        assertTrue("至少两个出厂 tab", tabs.size >= 2)
        val capacity = L5Ui.GRID_COLUMNS * L5Ui.ROWS_PER_PAGE
        for (t in tabs) {
            assertTrue("${t.id} 有页", t.pages.isNotEmpty())
            for ((i, p) in t.pages.withIndex()) {
                assertTrue("${t.id} 第 ${i + 1} 页有键", p.keys.isNotEmpty())
                val cells = p.keys.sumOf { it.span.coerceIn(1, L5Ui.GRID_COLUMNS) }
                assertTrue("${t.id} 第 ${i + 1} 页 $cells 格,不该超过两行($capacity)", cells <= capacity)
            }
        }
        assertTrue("内容超过一页的盘能翻", tabs.any { it.pages.size >= 2 })
    }

    @Test
    fun `L1-L3 已有的字符不进符号盘`() {
        // 2026-10-19 用户「L5 不需要 L1-L3 已经有的字符」:传进来的 producible
        // 与 L4 去重共用同一份结果(语言层表 + 全角开关),字符盘里同字的键直接消失。
        val producible = setOf("，", "。", "α", "|")
        val ui = L5Ui.factory(producible = producible)
        val tabs = ui.tabs.filter { it.id in setOf("punct", "tech") }
        val texts = tabs.flatMap { it.pages }.flatMap { it.keys }
            .mapNotNull { (it.out as? KeyOut.Text)?.text }
        assertTrue("重复字符全部滤掉", texts.none { it in producible })
        assertTrue("L1-L3 敲不出的中文标点仍在", "；" in texts)
        assertTrue("L1-L3 敲不出的希腊字母仍在", "β" in texts)
        assertTrue("滤掉 | 后同页其余 ASCII 仍在", "{" in texts)
        assertTrue("过滤后不留空页", tabs.all { t -> t.pages.all { it.keys.isNotEmpty() } })
    }

    @Test
    fun `打开面板时按当前语言与全角开关去重`() {
        val r = ImeControllerTestRig()
        val c = r.c

        // 默认拼音全角:L3 已能敲出 ， 《 等中文标点,面板不再重复摆。
        c.openL5()
        val fullWidthTexts = c.state.l5!!.tabs
            .filter { it.id in setOf("punct", "tech") }
            .flatMap { it.pages }.flatMap { it.keys }
            .mapNotNull { (it.out as? KeyOut.Text)?.text }
        assertTrue("全角 L3 的 ， 不在面板", "，" !in fullWidthTexts)
        assertTrue("全角 L3 的 《 不在面板", "《" !in fullWidthTexts)
        assertTrue("L3 敲不出的 ； 仍在面板", "；" in fullWidthTexts)

        // 面板开着时关掉全角:L3 退回 ASCII,， 回到面板;{ 则被 L3 的 ASCII 键带走。
        // 状态行开关会调 refreshL5Content,原地换字符盘,不把用户弹回第 0 个 tab。
        val tabBefore = c.state.l5!!.tab.id
        c.fullWidthSymbolsInCn = false
        c.refreshL5Content()
        assertEquals("重算保留当前 tab", tabBefore, c.state.l5!!.tab.id)
        val halfWidthTexts = c.state.l5!!.tabs
            .filter { it.id in setOf("punct", "tech") }
            .flatMap { it.pages }.flatMap { it.keys }
            .mapNotNull { (it.out as? KeyOut.Text)?.text }
        assertTrue("半角态 ， 回到面板", "，" in halfWidthTexts)
        assertTrue("半角 L3 的 { 不在面板", "{" !in halfWidthTexts)
    }

    /**
     * 剪贴板 tab(2026-10-16 用户「剪贴板功能:历史面板,复用浮层」)。
     *
     * 钉住:每个条目一行(ROWS 排布)、敏感内容遮罩、长文本截断、**遮罩不禁用**
     * (点敏感条目仍上屏全文)、空态隐藏 tab。
     */
    @Test
    fun `剪贴板 tab 每条一行 敏感遮罩 长文本截断`() {
        val ui = L5Ui.factory(
            listOf(
                ClipItem("normal text", sensitive = false),
                ClipItem("password", sensitive = true),
                ClipItem("x".repeat(50), sensitive = false),
            )
        )
        val clip = ui.tabs.first { it.id == L5Ui.CLIP_TAB_ID }
        assertEquals("剪贴板 tab 用整行排布", L5Layout.ROWS, clip.layout)
        assertTrue("空场景不显示", ui.tabs.none { it.id == L5Ui.SCENE_TAB_ID })
        assertEquals("出厂 tab 仍是网格排布", L5Layout.GRID, ui.tabs.first { it.id == "punct" }.layout)
        val keys = clip.pages.flatMap { it.keys }
        assertEquals("三条各一行", 3, keys.size)
        assertEquals("剪贴板一页最多两行(与面板固定两行一致)", 2, clip.pages.maxOf { it.keys.size })
        assertEquals("普通条目原样显示", "normal text", keys[0].label)
        assertEquals("敏感条目遮罩", "••••", keys[1].label)
        assertTrue("长条目截断", keys[2].label.endsWith("…"))
        assertEquals("普通条目上屏原文", "normal text", (keys[0].out as KeyOut.Text).text)
        assertEquals("敏感条目上屏仍是全文(遮罩不是禁用)", "password", (keys[1].out as KeyOut.Text).text)
    }

    @Test
    fun `剪贴板空时不显示 tab`() {
        val ui = L5Ui.factory()
        assertTrue("没有条目就没有剪贴板 tab", ui.tabs.none { it.id == L5Ui.CLIP_TAB_ID })
    }

    @Test
    fun `密码框隐藏非 ASCII 字符与词组`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.state.secure = true
        c.openL5()
        val ui = c.state.l5!!
        assertTrue("中文标点 tab 全是非 ASCII,应隐藏", ui.tabs.none { it.id == "punct" })
        val textKeys = ui.tabs.flatMap { it.pages }.flatMap { it.keys }
            .mapNotNull { (it.out as? KeyOut.Text)?.text }
        assertTrue("文本键只剩可打印 ASCII", textKeys.all { s -> s.all { it.code in 0x20..0x7E } })
        assertTrue("不再有控制 tab", ui.tabs.none { it.id == "control" })
        assertTrue("ASCII 技术 tab 仍在", ui.tabs.any { it.id == "tech" })

        c.commitClipboardText("，")
        assertTrue("密码框拒绝中文标点", r.h.commits.isEmpty())
        c.commitClipboardText("abc!123")
        assertEquals("ASCII 密码字符照常粘贴", listOf("abc!123"), r.h.commits)
    }

    @Test
    fun `手机号框隐藏非数字字符与词组并过滤剪贴板`() {
        val r = ImeControllerTestRig()
        val c = r.c
        c.setFieldKind(FieldKind.PHONE)
        c.openL5()
        val ui = c.state.l5!!
        assertTrue("标点 tab 没有合法字符,应隐藏", ui.tabs.none { it.id == "punct" })
        assertTrue("技术符号 tab 没有合法字符,应隐藏", ui.tabs.none { it.id == "tech" })
        assertTrue("不再有控制 tab", ui.tabs.none { it.id == "control" })
        assertTrue(
            "标点/技术/剪贴板都无合法键,只剩场景",
            ui.tabs.all { it.id == L5Ui.SCENE_TAB_ID },
        )

        val sceneText = ui.tabs.firstOrNull { it.id == L5Ui.SCENE_TAB_ID }
            ?.pages?.flatMap { it.keys }?.mapNotNull { (it.out as? KeyOut.Text)?.text }
            .orEmpty()
        assertTrue("场景短语每个字符都得是电话合法字符", sceneText.all { s -> s.all(::isPhoneChar) })

        c.setL5Clipboard(
            listOf(
                ClipItem("abc", sensitive = false),
                ClipItem("+8613800000000", sensitive = false),
            )
        )
        val clip = c.state.l5!!.tabs.first { it.id == L5Ui.CLIP_TAB_ID }
        val clipText = clip.pages.flatMap { it.keys }.mapNotNull { (it.out as? KeyOut.Text)?.text }
        assertEquals("只留电话合法条目", listOf("+8613800000000"), clipText)

        c.commitClipboardText("abc")
        assertTrue("手机号框拒绝字母", r.h.commits.isEmpty())
        c.commitClipboardText("+8613800000000")
        assertEquals("电话字符照常粘贴", listOf("+8613800000000"), r.h.commits)
    }

    private fun isPhoneChar(ch: Char): Boolean = ch in '0'..'9' || KeyLayout.DIGITS_PHONE.contains(ch)
}

/**
 * 共用的最小装置:控制器 + 记录上屏/按键的宿主。
 *
 * 注册方式照抄生产([ImeService] 里 `registerMethod(PinyinInputMethod(rime))`):
 * **注册装饰器,不是裸替身** —— 否则「装饰器漏转发某个成员」这类 bug 单测一律绿,
 * 生产上却静默走接口默认值(`isReady` 与 `topSpelling` 都这么漏过)。
 */
private class ImeControllerTestRig {
    val h = RecordingHost()
    private val engine = FakePinyinInputMethod.common().withConsumed(2)
    private val method = PinyinInputMethod.forTest(engine)
    private val registry = ImeRegistry.builtin().registerMethod(method)
    val c: ImeController = ImeController(h, KeyLayout.default(), registry)
}

/** 只记东西不做别的宿主;断言「写了什么 / 转发了什么」全看这两个表。 */
private class RecordingHost : ImeHost {
    val commits = mutableListOf<String>()
    val keys = mutableListOf<String>()
    override fun commitText(text: String) { commits += text }
    override fun sendKey(code: Int, ctrl: Boolean, shift: Boolean) { keys += "$code/$ctrl/$shift" }
    override fun render() {}
    // 长按调度/取消:本类不验长按,空实现即可
    override fun scheduleLongPress(code: Int) {}
    override fun cancelLongPress() {}
}
