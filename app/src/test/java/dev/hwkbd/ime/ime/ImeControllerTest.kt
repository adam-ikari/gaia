package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.engine.PinyinDict
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.PinyinInputMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    private val dictLines = listOf(
        // 多音节键带分节符,镜像真实词库格式(ni'hao ≠ nihao,分节位参与匹配)
        "ni\t你\t500",
        "hao\t好\t400",
        "ni'hao\t你好\t300",
        "shi'jie\t世界\t250",
        "zhong'guo\t中国\t600",
        "nian\t年\t900",
        "niang\t娘\t800",
        "niao\t鸟\t100",
        "nie\t捏\t50",
    )

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

    private fun rig(pageSize: Int = 5): Rig {
        val dict = PinyinDict()
        dict.load(dictLines)
        val h = RecHost()
        // 注册表 = 出厂语言/表现 + 拼音输入法(加语言/输入法走注册,不改状态机)
        val registry = ImeRegistry.builtin()
            .registerMethod(PinyinInputMethod(PinyinEngine(dict, dev.hwkbd.ime.engine.UserDict())))
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
        // 末尾分节符立起边界:全串的 你 照中;前缀只续 ni 之后有边界的词(你好),
        // 读作 nian/niang/niao/nie 的候选被分节位挡掉——分隔符真正生效(不再"剥掉查询")
        assertEquals(listOf("你", "你好"), r.c.state.candidates.map { it.word })
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
        assertEquals(Layer.L2, r.c.state.lock) // 聚焦即锁,免按 Alt(alt 锁定的数字变种)
        assertTrue(r.key(kc('q'))) // L2 的 q = 0,直接出数字
        assertEquals(listOf("0"), r.h.commits)
        // 数字锁解不掉(解锁后 L1 全被吞,没法打字):物理 Alt 单击只落一次性,不碰字段加的 lock
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(Layer.L2, r.c.state.lock)
        // 离开数字类 → 解除字段加的锁
        r.c.setFieldKind(FieldKind.TEXT)
        assertNull(r.c.state.lock)
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
        val dict = PinyinDict()
        dict.load(dictLines)
        val h = RecHost()
        val registry = ImeRegistry.builtin()
            .registerMethod(PinyinInputMethod(PinyinEngine(dict, dev.hwkbd.ime.engine.UserDict())))
        // 自定义布局:L1 q→"-."(L4 datetime 键的同文本)、e→"+"、v→"v"(拼音 mapText 改写成 `'`);
        // [L4 datetime] 段两把:`-.` 三层按得出(q 键)→ 不进 L4,`'` 按不出 → 进 L4
        val layout = KeyLayout.parseText("[L1]\nq=-.\ne=+\nv=v\n[L4 datetime]\n重复=-.\n分隔='").layout!!
        val c = ImeController(h, layout, registry)
        val r = Rig(c, h)
        c.state.fieldKind = FieldKind.DATETIME
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
        // Sym 按下/松开都成对吞掉(SYM 芯片已隐藏),L3 进不去
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
    fun `中文态标点全角逗号句号`() {
        // Titan2 无独立标点键:中文标点走 Alt 层 N=, B=.
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT); r.key(kc('n'))
        r.key(KeyEvent.KEYCODE_ALT_LEFT); r.key(kc('b'))
        assertEquals(listOf("\uFF0C", "\u3002"), r.h.commits)
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
        // 4 2 1 3 5 排列(2026-09-28 定案):
        // 测试词典 logical = [你, 年, 娘, 你好, 鸟, 捏] → 显示 [你好, 年, 你, 娘, 鸟]
        assertEquals(listOf("你好", "年", "你", "娘", "鸟"), items.map { it.word })
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
    fun `长按候选删除用户词刷新排序`() {
        val r = rig()
        val prefixes = { r.c.state.candidates.filter { it.kind == dev.hwkbd.ime.engine.CandidateKind.PREFIX } }
        repeat(10) { // 饱和加成下多次使用,让"你好"在其档(前缀)内置顶
            r.type("nihao")
            r.key(KeyEvent.KEYCODE_SPACE) // 选"你好"(前缀候选) → 频次加权
        }
        r.type("ni")
        assertEquals("你", r.c.state.candidates.first().word) // 精确命中恒第一(档位稳定)
        assertEquals("你好", prefixes().first().word) // 档内被顶到第一(压过词频更高的"年")
        val slot = r.c.state.pageItems().indexOfFirst { it.word == "你好" }
        r.c.removeUserWord(slot) // 长按删除
        assertEquals(0, r.c.userCountOf("你好")) // 用户频次清零
        assertEquals("年", prefixes().first().word) // 回落到档内词频最高的"年"
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
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_ALT_LEFT) // 长按松开 = 取消
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `长按 Alt 不按任何键松开也取消`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        Thread.sleep(350)
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
        Thread.sleep(450)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `一次性层激活后再次独立按同键取消`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.effectiveLayer)
        Thread.sleep(450) // 窗口外独立敲击
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
        Thread.sleep(450)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer) // 取消
    }

    @Test
    fun `锁定态下 Alt Sym 互切层`() {
        val r = rig()
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lock)
        r.tap(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock) // 锁定态下异层键一次切换
    }

    @Test
    fun `中文态 L2 数字保持半角`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits) // 九宫格数字用于输号码,保持半角
    }

    @Test
    fun `中文态 L2 标点全角`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('t'))
        assertEquals(listOf("\uFF08"), r.h.commits) // Alt 层 T=( → 全角(
    }

    @Test
    fun `英文态 L2 输出半角符号`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SPACE, shift = true)
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(kc('w'))
        assertEquals(listOf("1"), r.h.commits)
    }

    @Test
    fun `L3 空格保持原功能`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock)
        r.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(listOf(" "), r.h.commits) // 空格在 L3 不再特判(与可可一致)
    }

    @Test
    fun `Sym 连击三击为武装锁定解除`() {
        val r = rig()
        repeat(3) {
            r.key(KeyEvent.KEYCODE_SYM)
            r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        }
        // tap1 一次性 L3,tap2 锁定 L3,tap3 解锁回 L1
        assertEquals(null, r.c.state.lock)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
    }

    @Test
    fun `Sym 锁定后再次双击应保持解除`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 双击锁定 L3
        assertEquals(dev.hwkbd.ime.keymap.Layer.L3, r.c.state.lock)
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 第一击:解锁
        r.key(KeyEvent.KEYCODE_SYM)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 第二击:连击被吸收,不重新武装
        assertEquals(null, r.c.state.lock)
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
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM) // 长按松开 = 取消,不再算敲击
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.lock)
    }

    @Test
    fun `长按 Sym 不按任何键松开也取消`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_SYM)
        Thread.sleep(350)
        r.c.onKeyUp(KeyEvent.KEYCODE_SYM)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.oneShot)
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
        assertEquals(ShiftState.ONCE, r.c.state.shift)
        assertFalse(r.c.state.shiftHeld) // 松手后大写提示熄灭
        r.key(kc('a'))
        assertEquals(listOf("A"), r.h.commits)
        assertEquals(ShiftState.OFF, r.c.state.shift) // 输入一个字母就取消

        Thread.sleep(350) // 出双击窗口,重新起算
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 双击第一击:大写一次
        assertEquals(ShiftState.ONCE, r.c.state.shift)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 第二击:Caps 锁定
        assertEquals(ShiftState.CAPS, r.c.state.shift)
        r.key(kc('b'))
        r.key(kc('c'))
        assertEquals(listOf("A", "B", "C"), r.h.commits)

        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 已在 Caps:单击 = 关掉锁定
        assertEquals(ShiftState.OFF, r.c.state.shift)
    }

    @Test
    fun `大写一次状态再单击 Shift 必然解除 不是死状态`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 单击:大写一次
        assertEquals(ShiftState.ONCE, r.c.state.shift)

        Thread.sleep(350) // 出双击窗口 = 一次独立单击
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertEquals(ShiftState.OFF, r.c.state.shift) // 已开着的大写状态被按掉

        Thread.sleep(350)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 再按:重新武装大写一次
        assertEquals(ShiftState.ONCE, r.c.state.shift)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 窗口内第二击仍 = Caps 锁定(双击语义不变)
        assertEquals(ShiftState.CAPS, r.c.state.shift)
        r.tap(KeyEvent.KEYCODE_SHIFT_LEFT) // 任意一次按压都能退出:再按 = 解除
        assertEquals(ShiftState.OFF, r.c.state.shift)
    }

    @Test
    fun `物理 Shift 长按只在按住期间提示 松手不留下状态`() {
        val r = rig()
        r.c.state.languageId = "en"
        r.key(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertTrue(r.c.state.shiftHeld) // 按住期间状态行亮大写芯片
        Thread.sleep(350)
        r.key(kc('a'), shift = true) // 长按大写由物理修饰键承担
        assertEquals(listOf("A"), r.h.commits)
        r.c.onKeyUp(KeyEvent.KEYCODE_SHIFT_LEFT)
        assertFalse(r.c.state.shiftHeld)
        assertEquals(ShiftState.OFF, r.c.state.shift) // 松手取消,不残留一次性/锁定
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
        assertEquals(ShiftState.OFF, r.c.state.shift) // 不武装大写一次
        r.key(kc('b'))
        assertEquals(listOf("A", "b"), r.h.commits) // 按住期间大写来自物理 shift,松开后回小写
    }

    @Test
    fun `拼音态忽略 Shift 敲击 不产生大小写锁定`() {
        val r = rig()
        assertEquals("zh", r.c.state.languageId)
        r.c.onShiftTap()
        r.c.onShiftTap() // 双击也不给锁定:拼音不需要大小写
        assertEquals(ShiftState.OFF, r.c.state.shift)
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
        assertEquals(ShiftState.CAPS, r.c.state.shift)
        r.c.cycleLanguage()
        assertEquals("zh", r.c.state.languageId)
        assertEquals(ShiftState.OFF, r.c.state.shift)
    }

    @Test
    fun `密码框内禁止切到拼音 语言芯片与Shift空格都收口在 setLanguage`() {
        val r = rig()
        r.c.state.secure = true
        r.c.state.languageId = "en" // onStartInput 聚焦密码框时已强制到安全语言
        r.c.cycleLanguage() // 状态栏语言芯片 / Shift+空格 的共同入口
        assertEquals("en", r.c.state.languageId)
        r.c.setLanguage("zh") // 直接点名切走也被拒
        assertEquals("en", r.c.state.languageId)
        r.c.setLanguage("en") // 安全语言自身仍可达(强制英文路径依赖)
        assertEquals("en", r.c.state.languageId)
        r.c.state.secure = false // 离开密码框(onStartInput 重算 secure)后恢复可切
        r.c.cycleLanguage()
        assertEquals("zh", r.c.state.languageId)
    }

    // ---------------------------------------------------------------- 状态栏(芯片只显示,切换走物理键)

    @Test
    fun `层切换只走物理键 Alt Sym 单击一次性 双击锁定`() {
        val r = rig()
        val L1 = dev.hwkbd.ime.keymap.Layer.L1
        val L2 = dev.hwkbd.ime.keymap.Layer.L2
        val L3 = dev.hwkbd.ime.keymap.Layer.L3
        assertEquals(L1, r.c.state.effectiveLayer)

        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // Alt 单击 = 一次性 L2
        assertEquals(L2, r.c.state.effectiveLayer)
        assertEquals(L2, r.c.state.oneShot)
        assertEquals(null, r.c.state.lock)

        Thread.sleep(350) // 出双击窗口,再敲才是**独立**单击
        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 已是一次性 L2,窗口外再敲 = 取消回 L1
        assertEquals(L1, r.c.state.effectiveLayer)
        assertEquals(null, r.c.state.oneShot)

        r.tap(KeyEvent.KEYCODE_ALT_LEFT) // 一次性 L2
        r.tap(KeyEvent.KEYCODE_SYM) // Sym 单击 = 一次性 L3,顶掉 L2 的一次性
        assertEquals(L3, r.c.state.effectiveLayer)
        assertEquals(L3, r.c.state.oneShot)
        assertEquals(null, r.c.state.lock)

        // 双击 = 锁定该层
        Thread.sleep(350)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        r.tap(KeyEvent.KEYCODE_ALT_LEFT)
        assertEquals(L2, r.c.state.lock)
        assertEquals(null, r.c.state.oneShot)
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
    fun `切换输入框丢弃组词但保留模式与层`() {
        val r = rig()
        r.key(KeyEvent.KEYCODE_ALT_LEFT)
        r.key(KeyEvent.KEYCODE_ALT_LEFT) // 无组词时双击 Alt 锁 L2
        r.c.resetForNewInput()
        assertEquals("", r.c.state.raw)
        assertEquals(dev.hwkbd.ime.keymap.Layer.L2, r.c.state.lock)
    }
}
