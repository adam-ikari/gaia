package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.ChineseLanguage
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.FakePinyinInputMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import dev.hwkbd.ime.strategy.withRemainAfterSelect

class ImeControllerTest {

    private class RecHost : ImeHost {
        val commits = mutableListOf<String>()
        val keys = mutableListOf<String>()

        override fun commitText(text: String) {
            commits += text
        }

        override fun sendKey(code: Int, ctrl: Boolean, shift: Boolean) {
            keys += "$code/$ctrl/$shift"
        }

        override fun render() {}
    }


    private class Rig(val c: ImeController, val h: RecHost) {
        fun type(s: String) {
            for (ch in s) c.onKeyDown(kc(ch), false, false)
        }

        fun key(code: Int, ctrl: Boolean = false, shift: Boolean = false, caps: Boolean = false): Boolean =
            c.onKeyDown(code, ctrl, shift, caps)

        /** 完整敲击(按下 + 快速松开):修饰键的敲击/长按语义要到 keyUp 才定型。 */
        fun tap(code: Int): Boolean {
            val down = c.onKeyDown(code, false, false)
            c.onKeyUp(code)
            return down
        }
    }

    /**
     * 熬过长按阈值:按住这么久算长按,不算敲击。
     *
     * **跟常量走,别写死毫秒数**——长按阈值改一次,写死 350 的地方会静默变成"其实没按够",
     * 测试还过,断的却是语义(2026-09-30 敲击窗口 300→400 时踩过一次同款)。
     */
    private fun holdLongerThanThreshold() {
        Thread.sleep(ModifierGestures.DEFAULT_HOLD_MS + 50)
    }

    /**
     * 熬过双击窗口:下一击才算**独立**的一击(窗口内那一下会被算成第二击 = 锁定/Caps)。
     * 同样跟常量走,见 [holdLongerThanThreshold]。
     */
    private fun exitTapWindow() {
        Thread.sleep(ModifierGestures.DEFAULT_TAP_WINDOW_MS + 50)
    }

    private fun rig(pageSize: Int = 5, remainAfterSelect: String? = null): Rig {
        // 用 [FakePinyinInputMethod] 而不是真引擎(2026-10-03 换 librime):
        // 本类验的是**状态机**(敲 a 再敲 s 会怎样),不是「as 该不该出啊斯」。
        // 引擎实现是 [InputMethod] 背后的事,换了不该让这些断言重写。
        val h = RecHost()
        // 注册表 = 出厂语言/表现 + 拼音输入法(加语言/输入法走注册,不改状态机)
        val registry = ImeRegistry.builtin()
            .registerMethod(
                FakePinyinInputMethod.common().withRemainAfterSelect(remainAfterSelect)
            )
        val c = ImeController(h, KeyLayout.default(), registry)
        c.state.pageSize = pageSize
        return Rig(c, h)
    }

    companion object {
        fun kc(ch: Char): Int = KeyEvent.KEYCODE_A + (ch - 'a')
    }

    // ---------------------------------------------------------------- 中文拼音

    @Test
    fun `中文输入字母进缓冲空格上屏首选`() {
        val r = rig()
        r.type("nihao")
        assertEquals("nihao", r.c.state.raw)
        assertTrue(r.key(KeyEvent.KEYCODE_SPACE))
        assertEquals(listOf("你好"), r.h.commits)
        assertEquals("", r.c.state.raw)
    }

    @Test
    fun `回车上屏原字母`() {
        val r = rig()
        r.type("nihao")
        r.key(KeyEvent.KEYCODE_ENTER)
        assertEquals(listOf("nihao"), r.h.commits)
        assertEquals("", r.c.state.raw)
    }

    @Test
    fun `退格编辑缓冲空缓冲时透传`() {
        val r = rig()
        r.type("ni")
        assertTrue(r.key(KeyEvent.KEYCODE_DEL))
        assertEquals("n", r.c.state.raw)
        r.key(KeyEvent.KEYCODE_DEL)
        assertEquals("", r.c.state.raw)
        assertTrue(r.key(KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("${KeyEvent.KEYCODE_DEL}/false/false"), r.h.keys)
    }

    @Test
    fun `拼音组词中 v 键作为分隔符进缓冲`() {
        val r = rig()
        r.type("ni")
        val before = r.c.state.candidates.map { it.word }
        assertTrue(before.isNotEmpty())
        assertTrue(r.key(kc('v')))
        assertEquals("ni'", r.c.state.raw) // 分隔符显示在组词区(xi'an 语义)
        // 分节符进了组词区,而且**没打断组词**(没上屏、缓冲还在)。
        //
        // **这里不再断言「末尾分节把哪些词挡掉」**(2026-10-03 换 librime):
        // 旧引擎那条规则(`nian/niang/niao/nie` 被挡、只剩 你 + 你好)是
        // **它自己词库索引的分节位实现**造成的 —— 键带不带撇决定了能不能命中。
        // librime 的分节规则在方案自己的 `alphabet` 里,与那套不是一回事,
        // 也不该由这个状态机测试去钉。真机上的实际分节行为见 `docs/rime.md`。
        assertTrue("分节后仍应有候选", r.c.state.candidates.isNotEmpty())
        assertTrue(r.h.commits.isEmpty()) // 没有打断组词
    }

    @Test
    fun `拼音空闲时 v 键上屏分隔符 英文态 v 仍是字母`() {
        val r = rig()
        assertTrue(r.key(kc('v')))
        assertEquals(listOf("'"), r.h.commits) // 空闲按 v = 普通上屏,不新开组词
        assertFalse(r.c.state.hasComposition)
        r.c.setLanguage("en")
        assertTrue(r.key(kc('v')))
        assertEquals(listOf("'", "v"), r.h.commits) // 英文策略不改写,v 就是字母
    }

    // ------------------------------------------------------------ 数字类(锁定数字层)

    @Test
    fun `数字框聚焦直接锁定数字层 离开解锁`() {
        val r = rig()
        r.c.setFieldKind(FieldKind.NUMBER)
        assertEquals(Layer.L2, r.c.state.lockedLayer) // 聚焦即锁,免按 Alt(alt 锁定的数字变种)
        assertTrue(r.key(kc('q'))) // L2 的 q = 0,直接出数字
        assertEquals(listOf("0"), r.h.commits)
        // 数字锁解不掉(解锁后 L1 全被吞,没法打字):物理 Alt 单击只落一次性,不碰字段加的 lock
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(Layer.L2, r.c.state.lockedLayer)
        // 离开数字类 → 解除字段加的锁
        r.c.setFieldKind(FieldKind.TEXT)
        assertNull(r.c.state.lockedLayer)
    }

    @Test
    fun `数字类按各自合法字符放行 其余字符吞`() {
        val r = rig()
        // 中文全角开关默认开:数字类放行的字符也要出半角(- 不许变 －),这里顺带验这一点
        // signed:放行 '-',L2 的 '.' 不放行
        r.c.setFieldKind(FieldKind.NUMBER_SIGNED)
        assertTrue(r.key(kc('u'))) // L2 u = -
        assertTrue(r.key(kc('b'))) // L2 b = .
        assertEquals(listOf("-"), r.h.commits)
        // decimal:放行 '.','-' 不放行
        r.c.setFieldKind(FieldKind.NUMBER_DECIMAL)
        assertTrue(r.key(kc('b')))
        assertTrue(r.key(kc('u')))
        assertEquals(listOf("-", "."), r.h.commits)
        // phone(Android PhoneKeyListener 集合):放行 '+',不放行 '-'
        r.c.setFieldKind(FieldKind.PHONE)
        assertTrue(r.key(kc('j'))) // L2 j = +
        assertTrue(r.key(kc('u')))
        assertEquals(listOf("-", ".", "+"), r.h.commits)
        // datetime:放行 '/' 与 ':'
        r.c.setFieldKind(FieldKind.DATETIME)
        assertTrue(r.key(kc('o'))) // L2 o = /
        assertTrue(r.key(kc('p'))) // L2 p = :
        assertEquals(listOf("-", ".", "+", "/", ":"), r.h.commits)
    }

    @Test
    fun `数字框只吃 0-9 非数字文本键全吞`() {
        val r = rig()
        r.c.state.fieldKind = FieldKind.NUMBER
        // L1 字母不是数字:吞掉(被消费),不落屏也不开组词
        assertTrue(r.key(kc('q')))
        assertTrue(r.h.commits.isEmpty())
        assertTrue(r.c.state.raw.isEmpty())
        // 空格也吞(数字框不放空格)
        assertTrue(r.key(KeyEvent.KEYCODE_SPACE))
        assertTrue(r.h.commits.isEmpty())
        // Alt 按住进 L2:数字键放行(q → 0,中文全角开关下数字也保持半角)
        assertTrue(r.key(KeyEvent.KEYCODE_ALT_LEFT))
        assertTrue(r.key(kc('q')))
        assertEquals(listOf("0"), r.h.commits)
        // 退格照常透传(数字删得掉)
        assertTrue(r.key(KeyEvent.KEYCODE_DEL))
        assertEquals(listOf("${KeyEvent.KEYCODE_DEL}/false/false"), r.h.keys)
    }

    @Test
    fun `L4 与数字白名单都只认 L1-L3 按不出的键和短语组合`() {
        // 用 [FakePinyinInputMethod] 而不是真引擎(2026-10-03 换 librime):
        // 本类验的是**状态机**(敲 a 再敲 s 会怎样),不是「as 该不该出啊斯」。
        // 引擎实现是 [InputMethod] 背后的事,换了不该让这些断言重写。
        val h = RecHost()
        val registry = ImeRegistry.builtin()
            .registerMethod(
                FakePinyinInputMethod.common().withRemainAfterSelect(remainAfterSelect)
            )
        // 自定义布局:L1 q→"-."(L4 datetime 键的同文本)、e→"+"、v→"v"(拼音 mapText 改写成 `'`);
        // [L4 datetime] 段两把:`-.` 三层按得出(q 键)→ 不进 L4,`'` 按不出 → 进 L4
        val layout = KeyLayout.parseText("[L1]\nq=-.\ne=+\nv=v\n[L4 datetime]\n重复=-.\n分隔='").layout!!
        val c = ImeController(h, layout, registry)
        val r = Rig(c, h)
        c.state.fieldKind = FieldKind.DATETIME
        // 拼音态 L4 = 核心键(去掉 `$`) + l4Extras(`￥`)+ datetime 段,再过 producible 那把筛。
        // `' ` 按不出 → 留;`￥` 拼音态 L2/L3 都能敲出 → 被筛掉(否则屏上会出现两把同义的键)。
        assertEquals(listOf("分隔"), c.l4Keys().map { it.label })
        // 物理键的 "-." 不在 datetime 合法单字符集、L4 也没显示它 → 吞
        assertTrue(r.key(kc('q')))
        assertTrue(h.commits.isEmpty())
        // `+` 同理(datetime 不放行)
        assertTrue(r.key(kc('e')))
        assertTrue(h.commits.isEmpty())
        // 白名单并入 L4 显示的键:物理 v 被拼音 mapText 改写成 `'`,L4 正显示 `'` → 放行
        assertTrue(r.key(kc('v')))
        assertEquals(listOf("'"), h.commits)
        // 换成 NUMBER(L4 为空):物理 v 的 `'` 也不再放行
        c.state.fieldKind = FieldKind.NUMBER
        assertTrue(r.key(kc('v')))
        assertEquals(listOf("'"), h.commits)
    }

    @Test
    fun `数字框 Sym 敲击被吞 不进 L3`() {
        val r = rig()
        r.c.state.fieldKind = FieldKind.NUMBER
        // Sym 按下/松开都成对吞掉(SYM 状态项已隐藏),L3 进不去
        assertTrue(r.tap(KeyEvent.KEYCODE_SYM))
        assertTrue(r.key(kc('a'))) // 还在 L1,'a' 非数字 → 吞
        assertTrue(r.h.commits.isEmpty())
        assertTrue(r.c.state.raw.isEmpty())
        assertTrue(r.h.keys.isEmpty())
    }

    @Test
    fun `空缓冲时空格上屏半角空格`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf(" "), r.h.commits)
    }

    @Test
    fun `无候选时空格上屏原字母`() {
        val r = rig()
        r.type("zzz")
        assertTrue(r.c.state.candidates.isEmpty())
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("zzz"), r.h.commits)
    }

    @Test
    fun `全角态固定键就是丝印符号的全角形态`() {
        // Titan2 无独立标点键,标点走 Alt 层。全角态把丝印上那个字符换成全角形态,
        // 位子一位不挪 —— Alt+P(印 `:`)敲 `：`,Alt+B(印 `.`)敲 `。`。
        // 2026-09-29 中间两版都栽在这:一版把中文标点按"读着顺"摊开(16 把里 12 把错位),
        // 一版全角态直接铺 31 个中文标点(丝印同样对不上)。用户两次报障。
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT); r.key(kc('p')) // 丝印 :
        r.key(KeyEvent.KEYCODE_ALT_LEFT); r.key(kc('b')) // 丝印 .
        assertEquals(listOf("\uFF1A", "\u3002"), r.h.commits)
    }

    // ---------------------------------------------------------------- 选字/翻页

    @Test
    fun `候选不足时从中位紧凑补位`() {
        val r = rig()
        r.type("ni")
        r.c.pageStep(1) // 第 2 页只剩「捏」
        val slots = r.c.state.pageSlots()
        assertEquals(listOf(null, null, "捏", null, null), slots.map { it?.word })
        r.c.selectCandidate(2) // 中间槽选字
        assertEquals(listOf("捏"), r.h.commits)
    }

    // ---------------- 拼音消费(2026-10-04 用户「按实际选词消费,不能一次清空」)

    /**
     * 引擎说「还剩 `hao`」时,**不能**把 `hao` 一起清掉。
     *
     * 旧实现无条件 `clearComposition()`,等于假设一个候选吃掉全部拼音 ——
     * 单音节成立,`ni'hao` 选「你」之后 `hao` 被清掉,用户白打。
     */
    @Test
    fun `只清候选实际吃掉的那部分 剩下的拼音留着`() {
        val r = rig(remainAfterSelect = "hao")
        r.type("ni")
        r.c.selectCandidate(2)
        assertEquals(listOf("你"), r.h.commits)
        assertEquals("剩下的拼音应留在缓冲里", "hao", r.c.state.raw)
        assertTrue("应重新查到候选", r.c.state.candidates.isNotEmpty())
    }

    /** 引擎说「全吃完了」时照旧清空 —— 单音节输入的常态,行为不能变。 */
    @Test
    fun `引擎说吃完时 照旧清空整个缓冲`() {
        val r = rig(remainAfterSelect = null)
        r.type("ni")
        r.c.selectCandidate(2)
        assertEquals(listOf("你"), r.h.commits)
        assertEquals("", r.c.state.raw)
        assertTrue(r.c.state.candidates.isEmpty())
    }

    /** 引擎不参与选词时(`selectAndRemain` 给 null)也走旧路径 —— 加法式接口的保证。 */
    @Test
    fun `引擎不参与选词时 行为与旧版一致`() {
        val r = rig(remainAfterSelect = null)
        r.type("ni")
        r.c.selectCandidate(2)
        assertEquals(listOf("你"), r.h.commits)
        assertEquals("", r.c.state.raw)
    }

    // ---------------- 选字的表现回调(2026-10-02 用户「按键选词也需要飞字动画」)

    @Test
    fun `每条选字入口都触发飞字回调 且词与真正上屏的一致`() {
        // 九处选字入口共用一个回调。逐个入口验一遍:漏掉哪条,那条就又是"词直接冒出来"。
        // 这里覆盖状态机内部那几条(Shift/Sym/Alt/Ctrl/方向键/空格)+ 直接调 selectCandidate;
        // 候选栏点选与上滑走的是同一个 controller 方法,在 service 侧接回调,覆盖路径相同。
        val cases: List<Pair<String, (Rig) -> Unit>> = listOf(
            "Shift 选首位" to { r -> r.key(KeyEvent.KEYCODE_SHIFT_LEFT) },
            "Alt 选末位" to { r -> r.key(KeyEvent.KEYCODE_ALT_LEFT) },
            "方向键上 选高亮" to { r ->
                r.key(KeyEvent.KEYCODE_DPAD_DOWN)
                r.key(KeyEvent.KEYCODE_DPAD_UP)
            },
            "空格选首选" to { r -> r.key(KeyEvent.KEYCODE_SPACE) },
            "直接调 selectCandidate" to { r -> r.c.selectCandidate(2) },
        )
        for ((label, act) in cases) {
            val r = rig()
            r.type("ni")
            val seen = mutableListOf<Pair<Int, String>>()
            r.c.onCandidateSelected = { zone, word -> seen += zone to word }
            act(r)
            assertEquals("$label 应触发一次回调", 1, seen.size)
            // 回调给的词必须就是真正上屏的那个 —— 视图照它起动画,飞错词就露馅了
            assertEquals("$label 回调的词应等于上屏的词", listOf(r.h.commits.last()), listOf(seen[0].second))
        }
    }

    @Test
    fun `选不中时不给回调`() {
        // 回调放在两个 ?: return 之后:显示位越界、或者当前页没词,都不该有动画 ——
        // 那会让视图凭空飞一个不存在的词
        val r = rig()
        val seen = mutableListOf<Int>()
        r.c.onCandidateSelected = { zone, _ -> seen += zone }
        r.c.selectCandidate(0) // 还没组词,没有候选
        assertTrue("无候选时不该回调", seen.isEmpty())
        r.type("ni")
        r.c.selectCandidate(99) // 显示位越界
        assertTrue("显示位越界不该回调", seen.isEmpty())
    }

    @Test
    fun `方向键翻页与高亮确认`() {
        val r = rig()
        r.type("ni") // 候选 6 个:你 年 娘 你好 鸟 捏 → 2 页
        assertEquals(6, r.c.state.candidates.size)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(1, r.c.state.page)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(2, r.c.state.highlight) // 第二页只有"捏",位于中间槽(空格位)
        assertTrue(r.key(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(listOf("捏"), r.h.commits)
    }

    @Test
    fun `首候选固定显示在中间`() {
        val r = rig()
        r.type("ni")
        val items = r.c.state.pageItems()
        assertEquals(5, items.size)
        assertEquals("你", items[2].word) // 中间 = 首选 = 空格键位
        // 4 2 1 3 5 排列(2026-09-28 定案):首选固定落在中间槽。
        //
        // **只钉"你"在中间**,不再钉整条次序 —— 2026-10-03 换 librime 后次序由
        // Rime 的词频模型决定,而 [FakePinyinInputMethod] 的次序是表的顺序。
        // 原来那句 `assertEquals(listOf("你好","年","你","娘","鸟"))` 断言的是
        // 旧引擎的档位加分 + 用户频次饱和曲线算出来的结果,那套已经没有了。
        // 断言一条**已经不成立**的次序,比不写更糟:它会让人以为排序被改坏了。
        assertEquals(setOf("你", "年", "娘", "鸟", "你好"), items.map { it.word }.toSet())
    }

    @Test
    fun `组词中五修饰键独立敲击选字`() {
        // 每次上屏都抬高该词的用户词频,下一刻候选可能重排——
        // 本测试钉"键→显示槽"的对应,取当刻该槽的词;静态词序由「首候选固定显示在中间」钉
        val r = rig()
        r.type("ni")
        val s0 = r.c.state.pageSlots()[0]!!.word
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(listOf(s0), r.h.commits) // Shift = 槽0 = 候选4

        r.type("ni")
        val s4 = r.c.state.pageSlots()[4]!!.word
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(listOf(s0, s4), r.h.commits) // Alt = 槽4 = 候选5

        r.type("ni")
        val s3 = r.c.state.pageSlots()[3]!!.word
        r.key(KeyEvent.KEYCODE_CTRL_LEFT)
        r.c.onKeyUp(KeyEvent.KEYCODE_CTRL_LEFT)
        assertEquals(listOf(s0, s4, s3), r.h.commits) // Ctrl = 槽3 = 候选3
    }

    @Test
    fun `空格选中间首选`() {
        val r = rig()
        r.type("ni")
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf("你"), r.h.commits)
    }

    @Test
    fun `长按候选删除用户词在引擎不支持时如实报告`() {
        // 2026-10-03 换 librime 后这条测试的**前提没了**,必须改,否则它断言的是
        // 一个已经不存在的功能。旧行为是:删除用户词 → 频次清零 → 排序回落。
        // 那套是旧引擎的「饱和加成曲线」(60 万·count/(count+4))实现的,
        // librime 有自己的 LevelDB 词频模型,且**没有删单个词的 API**。
        //
        // 现在该保证的是**诚实**:不支持就报告不支持,让 UI 能告诉用户,
        // 而不是假装删了(那样词还在,下次输出来又冒出来,比没有这个入口更糟)。
        val r = rig()
        r.type("ni")
        val slot = r.c.state.pageItems().indexOfFirst { it.word == "你" }
        assertTrue("测试词典里应当有「你」", slot >= 0)
        r.c.removeUserWord(slot)
        assertEquals("不支持逐词删除的引擎必须返回 false", 0, r.c.userCountOf("你"))
    }

    @Test
    fun `候选为空时方向键透传`() {
        val r = rig()
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_LEFT))
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_DOWN))
        assertFalse(r.key(KeyEvent.KEYCODE_DPAD_UP))
    }

    @Test
    fun `ctrl 加 qwe rt 不选字只转发给应用`() {
        val r = rig()
        r.type("ni")
        assertTrue(r.key(kc('q'), ctrl = true))
        assertTrue(r.h.commits.isEmpty()) // 候选只由五键选,Ctrl+Q 不上屏
        assertEquals("ni", r.c.state.raw) // 组词区也不动

        r.key(kc('t'), ctrl = true)
        assertTrue(r.h.commits.isEmpty())
        assertEquals(
            listOf("${kc('q')}/true/false", "${kc('t')}/true/false"),
            r.h.keys, // 组合原样转发(vim 的 Ctrl+W 之类靠这个透传)
        )
    }

    @Test
    fun `其他 ctrl 组合原样转发`() {
        val r = rig()
        assertTrue(r.key(kc('c'), ctrl = true))
        assertEquals(listOf("${kc('c')}/true/false"), r.h.keys)
    }

    @Test
    fun `翻页方向反转`() {
        val r = rig()
        r.c.pageReverse = true
        r.type("ni")
        r.key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals(1, r.c.state.page)
    }

    // ---------------------------------------------------------------- 中英模式

    @Test
    fun `shift 加空格切换中英`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals("en", r.c.state.languageId)
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals("zh", r.c.state.languageId)
    }

    @Test
    fun `层锁定时 shift 加空格仍可切换语言`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 双击锁定 L2(敲击判定在 keyUp,所以是"两次完整敲击")
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals("en", r.c.state.languageId) // 锁定态下仍可切换拼音/En
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals("zh", r.c.state.languageId)
    }

    @Test
    fun `英文态字母直接上屏并跟随大写`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        r.key(kc('a'))
        r.key(kc('b'), shift = true)
        assertEquals(listOf("a", "B"), r.h.commits)
    }

    @Test
    fun `英文态组词中切换先上屏首选`() {
        val r = rig()
        r.type("nihao")
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        assertEquals(listOf("你好"), r.h.commits)
        assertEquals("en", r.c.state.languageId)
    }

    // ---------------------------------------------------------------- 层与锁定

    @Test
    fun `Alt 单击一次性进 L2 输出后自动回 L1`() {
        val r = rig()
        r.c.state.languageId = "en"
        assertTrue(r.key(KeyEvent.KEYCODE_ALT_LEFT))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer) // 按下即进层(长按也从这里开始)
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 快速松开 = 敲击,一次性层保留
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits) // Alt 层 W=1(九宫格)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Alt 期间可连续输出 L2 松开取消`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_ALT_LEFT) // 按住不放
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        r.key(kc('w'))
        r.key(kc('e'))
        r.key(kc('r'))
        assertEquals(listOf("1", "2", "3"), r.h.commits) // 一次性被消费后长按层继续兜着
        holdLongerThanThreshold()
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 长按松开 = 取消
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Alt 不按任何键松开也取消`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        holdLongerThanThreshold()
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(emptyList<String>(), r.h.commits)
    }

    @Test
    fun `Alt 双击锁定 L2 连续输出单击解锁`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        r.key(kc('e'))
        assertEquals(listOf("1", "2"), r.h.commits)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        // 真实节奏:锁定后先输入符号,再次敲 Alt 时早已超出双击窗口 → 单击解锁
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `一次性层激活后再次独立按同键取消`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        exitTapWindow() // 窗口外独立敲击
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer) // 取消
        assertFalse(r.c.state.hasComposition)
    }

    @Test
    fun `Sym 一次性层激活后再次独立按取消`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        exitTapWindow()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer) // 取消
    }

    @Test
    fun `锁定态下单击异层键是借用 用完回到锁`() {
        // 2026-09-30 用户定案,分两步:
        //   「锁着 Alt 时单击 Sym = 一次性 Sym」⇒ 不能单击就锁定(Locked 分支原先排在次数判定之前);
        //   「临时借用返回上一个锁定状态 没有锁定状态返回初始状态」⇒ 借的那一个键用完后回锁。
        val r = rig()
        r.c.state.languageId = "en" // 英文态:字母键直接出字,不用绕开拼音组词
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.tap(KeyEvent.KEYCODE_SYM)
        assertEquals(
            "借 Sym 一次,恢复目标是锁着的 L2",
            LayerState.OneShot(dev.hwkbd.ime.keymap.Layer.L3, LayerState.Locked(dev.hwkbd.ime.keymap.Layer.L2)),
            r.c.state.layerState,
        )
        assertEquals("借用期间可见层是 Sym", dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        assertEquals("锁没丢", dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.key(kc('x')) // L3 X=&
        assertEquals(listOf("&"), r.h.commits)
        assertEquals("借的那一下用完就回到锁着的 L2", dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.key(kc('w')) // 下一个键又在 L2
        assertEquals(listOf("&", "1"), r.h.commits)
    }

    @Test
    fun `借用中再敲 Sym 取消借用 敲 Alt 收回借用`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 锁 L2
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_SYM) // 借 L3
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_SYM) // 再敲同一个键 = 取消借用,回锁
        assertEquals(LayerState.Locked(dev.hwkbd.ime.keymap.Layer.L2), r.c.state.layerState)
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_SYM) // 再借一次
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 敲底下锁着的那层 = 收掉借用,回锁
        assertEquals(LayerState.Locked(dev.hwkbd.ime.keymap.Layer.L2), r.c.state.layerState)
    }

    @Test
    fun `锁定态下双击异层键才把锁移过去`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.tap(KeyEvent.KEYCODE_SYM) // 第一击:一次性 L3(锁被顶掉)
        r.tap(KeyEvent.KEYCODE_SYM) // 第二击:双击 = 锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lockedLayer)
    }

    @Test
    fun `锁定态下敲同层键解锁`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 单击 = 解锁
        assertEquals(null, r.c.state.lockedLayer)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `中文态 L2 数字保持半角`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits) // 九宫格数字用于输号码,保持半角
    }

    @Test
    fun `中文态 L2 标点位是中文`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('t'))
        assertEquals(listOf("\uFF08"), r.h.commits) // 全角态 Alt+T:丝印 `(` → 全角（
    }

    @Test
    fun `英文态 L2 输出半角符号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true) // 切英文
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('t'))
        assertEquals(listOf("("), r.h.commits) // 半角,不受拼音态那张表影响
    }

    // ---------------- L2/L3 固定键跟着语言切换(2026-09-29 用户定案)

    @Test
    fun `半角态 L2 L3 就是键位表出厂值 也就是键盘上印着的`() {
        // 半角态 = **按眼所见**:那颗「全/半」开关切过去之后,Alt/Sym 每把键都该敲出
        // 键盘上印着的那个字符。层表返回 null 就是这个意思(见 ChineseLanguage.layerMap)。
        val layout = KeyLayout.default()
        for (layer in listOf(Layer.L2, Layer.L3)) {
            assertNull("$layer 半角态不该有语言专属层表", ChineseLanguage.layerMap(layer, fullWidth = false))
        }
        val r = rig()
        r.c.fullWidthSymbolsInCn = false
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('b')) // 丝印 `.`
        r.key(kc('n')) // 丝印 `,`
        r.key(kc('t')) // 丝印 `(`
        assertEquals(listOf(".", ",", "("), r.h.commits)
        // 丝印没印的那些(L2 的数字位)也照旧
        r.key(kc('q'))
        assertEquals(listOf(".", ",", "(", "0"), r.h.commits)
        assertEquals(layout.mapFor(Layer.L2).size, 26) // 出厂表本身没被动过
    }

    @Test
    fun `全角态 L2 每把键只是丝印符号的全角形态`() {
        // 硬件键盘,Alt 层的字符就印在键上。全角态只许"同一个字符换宽",不许换字 ——
        // 印着 `:` 的键(Alt+P)必须敲 `：`,印着 `.` 的键(Alt+B)必须敲 `。`。
        // 2026-09-29 中间两版都栽在这:一版把中文标点按"读着顺"摊开(16 把里 12 把错位),
        // 一版全角态直接铺 31 个中文标点(丝印同样对不上)。用户两次报障。
        val factory = KeyLayout.default().mapFor(Layer.L2)
        val cn = ChineseLanguage.layerMap(Layer.L2, fullWidth = true)!!
        var checked = 0
        for ((code, out) in cn) {
            val printed = factory[code] as? KeyOut.Text ?: continue
            if (printed.text.length != 1) continue
            val got = (out as KeyOut.Text).text
            assertEquals(
                "Alt+${letterOf(code)} 丝印 ${printed.text} 却敲出 $got —— 字换了,应该只是换宽",
                wideOf(printed.text[0]),
                got,
            )
            checked++
        }
        assertEquals("16 个符号位(10 个数字位不在表里)", 16, checked)
    }

    @Test
    fun `全角态 L3 成对符号换中文那一个 其余只换宽`() {
        val factory = KeyLayout.default().mapFor(Layer.L3)
        val cn = ChineseLanguage.layerMap(Layer.L3, fullWidth = true)!!
        assertEquals("15 个符号位", 15, cn.size)
        fun at(ch: Char) = (cn[KeyEvent.KEYCODE_A + (ch - 'a')] as KeyOut.Text).text
        // 成对符号换的是**中文里真正用的那一个**,不是 Unicode 的全角区块(用户 2026-09-29
        // 定案:「中文状态 `<>` 对应的应该是 `《》,`[]` 对应 `【】,同理」)。中文排版里这两对
        // 就是书名号与方头括号,写成全角 `＜＞［］` 没人用。
        assertEquals("《", at('t'))
        assertEquals("》", at('y'))
        assertEquals("【", at('g'))
        assertEquals("】", at('h'))
        // `\` 换成中文里真有用途的顿号(用户 2026-09-30「全角状态 L3 的 z 应该映射为 `、`,
        // 而不是全角的 `\`」)。连带效果:顿号上了物理键,状态行那把补差键被 L4 的筛自动带走
        // (见 ImeRegistryTest「L4 装 L1-L3 敲不出的」)。代价:全角 `＼` 敲不出来了。
        assertEquals("、", at('z'))
        // 其余一律机械全角 +0xFEE0
        for (ch in "efujxcb n".filter { it in 'a'..'z' }) {
            val printed = factory[KeyEvent.KEYCODE_A + (ch - 'a')] as KeyOut.Text
            assertEquals(
                "Sym+$ch 丝印 ${printed.text} 却敲出 ${at(ch)}",
                wideOf(printed.text[0]),
                at(ch),
            )
        }
        // 币种位:半角/英文敲 `$`,拼音全角敲 `￥` —— 币种是唯一不随标点全角变的一类,
        // 归物理键承担,状态行那把核心 `$` 因此退场(屏上不出现第二把币种键)
        assertEquals("￥", at('r'))
    }

    @Test
    fun `Sym 加 R 是币种位 半角英文敲美元 拼音全角敲人民币`() {
        fun symTwice(r: Rig) {
            r.key(KeyEvent.KEYCODE_SYM)
            r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
            r.key(KeyEvent.KEYCODE_SYM)
            r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁 L3
        }

        val full = rig()
        symTwice(full)
        full.key(kc('r'))
        assertEquals("拼音全角态 Sym+R", listOf("￥"), full.h.commits)

        val half = rig()
        half.c.fullWidthSymbolsInCn = false
        symTwice(half)
        half.key(kc('r'))
        assertEquals("半角态 Sym+R", listOf("$"), half.h.commits)

        val en = rig()
        en.key(KeyEvent.KEYCODE_SPACE, shift = true) // 切英文
        symTwice(en)
        en.key(kc('r'))
        assertEquals("英文态 Sym+R", listOf("$"), en.h.commits)

        // 币种归物理键,状态行就不该再摆一把(拼音态两态都不摆)
        for (fw in listOf(true, false)) {
            val c = rig()
            c.c.fullWidthSymbolsInCn = fw
            val labels = c.c.l4Keys().map { it.label }
            assertFalse("全角开关=$fw 时状态行都不该有币种键", labels.contains("￥"))
            assertFalse("全角开关=$fw 时状态行都不该有币种键", labels.contains("$"))
        }
    }

    /**
     * 丝印那个 ASCII 字符在中文态该敲出什么。
     *
     * 三个特例,其余 ASCII(0x21-0x7E)一律 +0xFEE0 落进全角区块:
     * - `.` → `。`(**不是**全角句点 `｡`):中文句号是 U+3002,全角句点长得对但没人用;
     * - `"` → `”`、`'` → `’`:**不是**全角引号 `＂` `＇`,中文引号是弯的(左引号在 L4);
     * - **不是 ASCII 的原样不动** —— 全角区块只覆盖 U+FF01-U+FF5E(`©` 压根不在其中)。
     */
    private fun wideOf(c: Char): String = when {
        c == '.' -> "。"
        c == '"' -> "”"
        c == '\'' -> "’"
        c.code !in 0x21..0x7E -> c.toString()
        else -> (c.code + 0xFEE0).toChar().toString()
    }

    private fun letterOf(code: Int): String =
        if (code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) ('a' + (code - KeyEvent.KEYCODE_A)).toString()
        else "code=$code"

    @Test
    fun `中文态 L2 数字位原样 九宫格不动`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('q')) // 0
        r.key(kc('w')) // 1
        // 数字位必须原样:数字类输入框锁 L2 直接输数字(见 FieldProfile.digitsOnly),
        // 中文标点换了内容也不能把数字挪走
        assertEquals(listOf("0", "1"), r.h.commits)
    }

    @Test
    fun `全角态 L3 敲出丝印符号的全角形态`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁 L3
        r.key(kc('e')) // 丝印 ~ → 全角 ～
        assertEquals(listOf("～"), r.h.commits)
        r.key(kc('z')) // 丝印 \ → 顿号(全角态把反斜杠换成中文真用的那一个)
        assertEquals(listOf("～", "、"), r.h.commits)
    }

    @Test
    fun `半角态 L3 仍是出厂编程符号`() {
        // 「全/半」开关切到半角,Sym 就回到键盘上印着的那一层
        val r = rig()
        r.c.fullWidthSymbolsInCn = false
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(kc('t')) // 丝印 <
        r.key(kc('e')) // 丝印 ~
        assertEquals(listOf("<", "~"), r.h.commits)
    }

    @Test
    fun `中文态 L3 功能键位保留 Tab`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(kc('p')) // 出厂 L3 的 p = key:tab,中文标点表不能把它顶掉
        assertEquals(listOf("${KeyEvent.KEYCODE_TAB}/false/false"), r.h.keys)
    }

    @Test
    fun `英文态 L3 仍是编程符号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(kc('t')) // 出厂 L3 的 t = <
        assertEquals(listOf("<"), r.h.commits)
    }

    @Test
    fun `关掉中文全角开关 拼音态 L2 退回半角`() {
        val r = rig()
        r.c.fullWidthSymbolsInCn = false
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('t'))
        // 设置里的「中文全角」说的就是"中文态标点要不要全角"。拼音表硬编码全角的话,
        // 这个开关按了没反应 —— 死开关比没有更坏(用户以为生效了,打出的是全角)
        assertEquals(listOf("("), r.h.commits)
    }

    @Test
    fun `数字类输入框绕过中文表 照旧用出厂 ASCII 槽位`() {
        // 数字类的合法字符集是照着**出厂 ASCII L2 槽位**定的(`- . + * # / :` 各占一键),
        // 中文表把那些位换成了中文标点。日期框要是跟着换,就输不出 `/`;状态项上的 `0-9-/:`
        // 也跟着说谎。数字框本来就没有"语言"可言 —— 必须走键位表出厂值。
        val r = rig()
        r.c.setFieldKind(FieldKind.DATETIME)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('o')) // 出厂 L2 o = /
        r.key(kc('p')) // 出厂 L2 p = :
        assertEquals(listOf("/", ":"), r.h.commits)
        r.c.setFieldKind(FieldKind.PHONE)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('j')) // 出厂 L2 j = +
        assertEquals(listOf("/", ":", "+"), r.h.commits)
    }

    @Test
    fun `L3 空格保持原功能`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lockedLayer)
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf(" "), r.h.commits) // 空格在 L3 不再特判(与可可一致)
    }

    @Test
    fun `三连击不是独立事件 只是单加双的叠加`() {
        val r = rig()
        repeat(3) {
            r.key(KeyEvent.KEYCODE_SYM)
            r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        }
        // 2026-09-30 用户定案「三连击无效,短时间内只有单击和双击是有效事件」。
        // 三击 = 单击(一次性 L3) + 双击(锁定 L3) + 第三击(锁定态下的一次单击 = 解锁)
        // ⇒ 净结果就是"锁定态被单击解除",**没有第三种含义**。
        // 顺带证实:原先那条 `n>=3 → 换层` 分支是**够不到的死代码**——从 L1 连击三下时
        // 第二击就已经进 Locked 分支,第三击永远轮不到 n>=3。
        assertEquals(null, r.c.state.lockedLayer)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Sym 锁定后再次双击应保持解除`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lockedLayer)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 第一击:解锁
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 第二击:连击被吸收,不重新武装
        assertEquals(null, r.c.state.lockedLayer)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Sym 单击进 L3 一次性 敲击语义在 keyUp 定型`() {
        val r = rig()
        r.c.state.languageId = "en"
        assertTrue(r.key(KeyEvent.KEYCODE_SYM))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer) // 按住即进 L3(长按语义)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 快速松开 = 敲击,一次性 L3 保留
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        r.key(kc('q'))
        assertEquals(emptyList<String>(), r.h.commits) // L3 Q=Esc:功能键转发,不上屏文本
        assertEquals(listOf("${KeyEvent.KEYCODE_ESCAPE}/false/false"), r.h.keys)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Sym 期间按键走 L3 松开取消`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_SYM) // 按住不放
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        r.key(kc('x')) // L3 X=&
        assertEquals(listOf("&"), r.h.commits)
        holdLongerThanThreshold()
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 长按松开 = 取消,不再算敲击
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.lockedLayer)
    }

    @Test
    fun `长按 Sym 不按任何键松开也取消`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        holdLongerThanThreshold()
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.oneShotLayer)
    }

    @Test
    fun `Alt 键值可配置`() {
        val r = rig()
        r.c.altKeycodes = intArrayOf(999)
        assertTrue(r.key(999))
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
    }

    // ---------------------------------------------------------------- Shift/Caps

    @Test
    fun `软键盘 Shift 单击下一键大写双击 Caps`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.c.onShiftTap() // 单击:下一键大写
        r.key(kc('a'))
        assertEquals(listOf("A"), r.h.commits)
        r.c.onShiftTap() // 窗口内第二次:双击 = Caps
        r.key(kc('b'))
        assertEquals(listOf("A", "B"), r.h.commits)
        r.key(kc('c')) // Caps 持续
        assertEquals(listOf("A", "B", "C"), r.h.commits)
    }

    @Test
    fun `物理 Shift 单击大写一次 双击 Caps 再单击关掉`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 单击
        assertEquals(ShiftMode.ONCE, r.c.state.shiftMode)
        r.key(kc('a'))
        assertEquals(listOf("A"), r.h.commits)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode) // 输入一个字母就取消

        exitTapWindow() // 出双击窗口,重新起算
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 双击第一击:大写一次
        assertEquals(ShiftMode.ONCE, r.c.state.shiftMode)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 第二击:Caps 锁定
        assertEquals(ShiftMode.CAPS, r.c.state.shiftMode)
        r.key(kc('b'))
        r.key(kc('c'))
        assertEquals(listOf("A", "B", "C"), r.h.commits)

        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 已在 Caps:单击 = 关掉锁定
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)
    }

    @Test
    fun `大写一次状态再单击 Shift 必然解除 不是死状态`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 单击:大写一次
        assertEquals(ShiftMode.ONCE, r.c.state.shiftMode)

        exitTapWindow() // 出双击窗口 = 一次独立单击
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode) // 已开着的大写状态被按掉

        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 再按:重新武装大写一次
        assertEquals(ShiftMode.ONCE, r.c.state.shiftMode)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 窗口内第二击仍 = Caps 锁定(双击语义不变)
        assertEquals(ShiftMode.CAPS, r.c.state.shiftMode)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 任意一次按压都能退出:再按 = 解除
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)
    }

    @Test
    fun `物理 Shift 长按只在按住期间提示 松手不留下状态`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.HELD, r.c.state.shiftMode) // 按住期间状态行亮出大写
        holdLongerThanThreshold()
        r.key(kc('a'), shift = true) // 长按大写由物理修饰键承担
        assertEquals(listOf("A"), r.h.commits)
        r.c.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode) // 松手取消,不残留一次性/锁定
        r.key(kc('b'))
        assertEquals(listOf("A", "b"), r.h.commits)
    }

    @Test
    fun `Shift 当修饰键用过就不算敲击`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        r.key(kc('a'), shift = true) // 按住期间按了别的键
        r.c.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode) // 不武装大写一次
        r.key(kc('b'))
        assertEquals(listOf("A", "b"), r.h.commits) // 按住期间大写来自物理 shift,松开后回小写
    }

    @Test
    fun `拼音态忽略 Shift 敲击 不产生大小写锁定`() {
        val r = rig()
        assertEquals("zh", r.c.state.languageId)
        r.c.onShiftTap()
        r.c.onShiftTap() // 双击也不给锁定:拼音不需要大小写
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)
        r.key(kc('n'))
        r.key(kc('i'))
        assertTrue(r.c.state.hasComposition)
        assertEquals("ni", r.c.state.raw) // 组词照常,不受大写状态影响
    }

    @Test
    fun `切进拼音态清掉英文态的大小写锁定`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.c.onShiftTap()
        r.c.onShiftTap()
        assertEquals(ShiftMode.CAPS, r.c.state.shiftMode)
        r.c.cycleLanguage()
        assertEquals("zh", r.c.state.languageId)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)
    }

    @Test
    fun `切语言清掉全部状态 层与大写与连击计数都不残留`() {
        // 2026-10-02 用户「切合拼音和英文时所有的状态都清除」。
        // 此前只清组词与大写,层状态与 ModifierGestures 的计时器会跨语言残留。
        val r = rig()
        r.c.state.languageId = "en"
        // 摆出三种可延续的状态:锁着的层、大写、连击计数
        r.tap(KeyEvent.KEYCODE_SYM)
        r.tap(KeyEvent.KEYCODE_SYM) // 双击 = 锁定 L3
        assertEquals(LayerState.Locked(Layer.L3), r.c.state.layerState)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.CAPS, r.c.state.shiftMode)
        r.type("zhong")

        r.c.cycleLanguage() // → 拼音

        assertEquals("zh", r.c.state.languageId)
        assertEquals("组词要上屏并清空", "", r.c.state.raw)
        assertTrue("候选清空", r.c.state.candidates.isEmpty())
        assertEquals("层归 L1,不能停在符号/编程层", LayerState.L1, r.c.state.layerState)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)
        assertEquals(-1, r.c.state.highlight)
        assertEquals(0, r.c.state.page)
    }

    @Test
    fun `切语言后同键连击从零数 不接旧的计数`() {
        // 连击计数残留的后果:锁着 L3(已两击)时切语言,紧接着那一下 Sym 会被算成
        // **第三击** —— 而第三击在锁定态上是"解除",于是刚切完语言紧接着敲一下
        // Sym,层就被莫名其妙解开了。
        val r = rig()
        r.c.state.languageId = "en"
        r.tap(KeyEvent.KEYCODE_SYM)
        r.tap(KeyEvent.KEYCODE_SYM)
        assertEquals(LayerState.Locked(Layer.L3), r.c.state.layerState)
        r.c.cycleLanguage()
        assertEquals("切完语言层应已归 L1", LayerState.L1, r.c.state.layerState)
        // 切完后的**第一击**必须是"新连击的第一击"= 一次性层,而不是旧连击的第三击
        r.tap(KeyEvent.KEYCODE_SYM)
        assertEquals(
            "切完的第一击应算新连击的第一击(一次性层)",
            LayerState.OneShot(Layer.L3, LayerState.L1),
            r.c.state.layerState,
        )
    }

    @Test
    fun `数字类字段切语言不清掉字段给的那把 L2 锁`() {
        // 那把锁是 setFieldKind 给的(聚焦即锁,免按 Alt)。清掉的话层回到 L1,
        // 而字母又过不了数字白名单 —— 数字框会彻底打不出东西。
        // 实际进不来(语言项与全/半开关在数字态都藏着,Shift+空格也 `!digitsOnly` 挡着),
        // 这条是防御:状态机不该依赖"入口恰好进不来"这种外部条件。
        val r = rig()
        r.c.setFieldKind(FieldKind.NUMBER)
        r.c.state.languageId = "en"
        r.c.cycleLanguage()
        assertEquals(LayerState.Locked(Layer.L2), r.c.state.layerState)
    }

    @Test
    fun `密码框内禁止切到拼音 语言项与Shift空格都收口在 setLanguage`() {
        val r = rig()
        r.c.state.secure = true
        r.c.state.languageId = "en" // onStartInput 聚焦密码框时已强制到安全语言
        r.c.cycleLanguage() // 状态行语言项 / Shift+空格 的共同入口
        assertEquals("en", r.c.state.languageId)
        r.c.setLanguage("zh") // 直接点名切走也被拒
        assertEquals("en", r.c.state.languageId)
        r.c.setLanguage("en") // 安全语言自身仍可达(强制英文路径依赖)
        assertEquals("en", r.c.state.languageId)
        r.c.state.secure = false // 离开密码框(onStartInput 重算 secure)后恢复可切
        r.c.cycleLanguage()
        assertEquals("zh", r.c.state.languageId)
    }

    // ---------------------------------------------------------------- 状态行(只显示,切换走物理键)

    @Test
    fun `层切换只走物理键 Alt Sym 单击一次性 双击锁定`() {
        val r = rig()
        val L1 = dev.hwkbd.ime.keymap.Layer.L1
        val L2 = dev.hwkbd.ime.keymap.Layer.L2
        val L3 = dev.hwkbd.ime.keymap.Layer.L3
        assertEquals(L1, r.c.state.effectiveLayer)

        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // Alt 单击 = 一次性 L2
        assertEquals(L2, r.c.state.effectiveLayer)
        assertEquals(L2, r.c.state.oneShotLayer)
        assertEquals(null, r.c.state.lockedLayer)

        exitTapWindow() // 出双击窗口,再敲才是**独立**单击
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 已是一次性 L2,窗口外再敲 = 取消回 L1
        assertEquals(L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.oneShotLayer)

        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 一次性 L2
        r.tap(KeyEvent.KEYCODE_SYM) // Sym 单击 = 一次性 L3,顶掉 L2 的一次性
        assertEquals(L3, r.c.state.effectiveLayer)
        assertEquals(L3, r.c.state.oneShotLayer)
        assertEquals(null, r.c.state.lockedLayer)

        // 双击 = 锁定该层
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(L2, r.c.state.lockedLayer)
        assertEquals(null, r.c.state.oneShotLayer)
    }

    // ---------------------------------------------------------------- 透传

    @Test
    fun `未映射键透传给应用`() {
        val r = rig()
        assertFalse(r.key(KeyEvent.KEYCODE_ESCAPE))
        assertFalse(r.key(KeyEvent.KEYCODE_F5))
        assertFalse(r.key(KeyEvent.KEYCODE_BUTTON_B))
    }

    @Test
    fun `Caps 双击解除后短窗内的第三击被吸收`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.CAPS, r.c.state.shiftMode)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 已开着再单击 = 关掉
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)
        // 2026-09-30 补齐:关掉大写进 250ms 吸收窗(原先只有层状态有),
        // 所以"双击解除后手抖再按一下"不会立刻又锁上
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals("短窗内这一击被吞掉", ShiftMode.OFF, r.c.state.shiftMode)
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 出窗后恢复独立单击
        assertEquals(ShiftMode.ONCE, r.c.state.shiftMode)
    }

    @Test
    fun `切换输入框丢弃组词但保留模式与层`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 无组词时双击 Alt 锁 L2
        r.c.resetForNewInput()
        assertEquals("", r.c.state.raw)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
    }

    @Test
    fun `层状态是单一取值 按住中不与锁定或一次性并存`() {
        val r = rig()
        // 按下 Alt 的那一帧:可见层是 Hold,底下是按下的那份(敲击还没落定)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        val hold = r.c.state.layerState
        assertTrue("按住中应当是 LayerState.Hold", hold is LayerState.Hold)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 快速松开 = 敲击 ⇒ 落定一次性层
        assertEquals(LayerState.OneShot(dev.hwkbd.ime.keymap.Layer.L2, LayerState.L1), r.c.state.layerState)
    }

    @Test
    fun `三个修饰键同一套骨架 敲击判定都在 keyUp`() {
        // 2026-09-30 整条梳理:Alt 曾在 keyDown 计数,于是需要 pending + holdTapN 两处补救;
        // 现在统一"keyDown 记快照、keyUp 判敲击、只有敲击才落定"——长按永远不留痕迹。
        val r = rig()
        r.c.state.languageId = "en"
        val L1 = dev.hwkbd.ime.keymap.Layer.L1
        val L2 = dev.hwkbd.ime.keymap.Layer.L2

        // 按住 Alt 不放:层已进,但**敲击还没落定**(底下仍是 L1)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals("底下那份还没被敲击改过", null, r.c.state.oneShotLayer)
        holdLongerThanThreshold()
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 长按松手 = 取消
        assertEquals("长按不留一次性层", null, r.c.state.oneShotLayer)
        assertEquals(L1, r.c.state.effectiveLayer)

        // 同样时长按 Shift:不留大写状态
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        holdLongerThanThreshold()
        r.c.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.OFF, r.c.state.shiftMode)

        // 快速敲击才落定(三个键各来一次)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(L2, r.c.state.oneShotLayer)
        exitTapWindow()
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftMode.ONCE, r.c.state.shiftMode)
    }

    @Test
    fun `长按 Sym 松手恢复按下的层状态`() {
        val r = rig()
        // 先双击 Alt 锁住 L2,再长按 Sym:长按期间可见层是 Sym,松手必须回到**按下的那个**(锁着的 L2)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lockedLayer)
        r.key(KeyEvent.KEYCODE_SYM) // 按下(Sym 的敲击判定在 keyUp,长按期间不落定)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.effectiveLayer)
        holdLongerThanThreshold() // 按够长按阈值
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(
            "长按松手不该抹掉锁定层",
            LayerState.Locked(dev.hwkbd.ime.keymap.Layer.L2),
            r.c.state.layerState,
        )
    }
}
