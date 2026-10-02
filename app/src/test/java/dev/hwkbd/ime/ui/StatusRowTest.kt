package dev.hwkbd.ime.ui

import dev.hwkbd.ime.ime.ImeState
import dev.hwkbd.ime.ime.LayerState
import dev.hwkbd.ime.ime.ShiftMode
import dev.hwkbd.ime.keymap.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 状态行左区呈现判定的回归测试。
 *
 * **这些规则以前一行测试都没有**(住在 `ImeService.renderStatus` 里,纯靠人眼在机上看):
 * 大小写与层互斥、锁定统一样式、数字态只剩 Alt、语言恒显……
 * 现在它们是纯函数 [StatusRow.present],可以逐条钉住。
 *
 * 顺带钉住公理 A:**状态行是状态的纯函数**——只吃状态,不吃时间(手势进行到哪一步都是一个
 * 真实状态,直接显示)。
 */
class StatusRowTest {

    private val theme = ImeTheme.DEFAULT

    /** 英文态默认输入项(英文才有大小写状态)。 */
    private fun enSpec(
        digitsOnly: Boolean = false,
        altLabel: String = "alt",
        showShift: Boolean = true,
        fullWidth: Boolean = true,
        primary: Boolean = false,
    ) = StatusRow.Spec(
        altLabel = altLabel,
        digitsOnly = digitsOnly,
        languageId = "en",
        languageLabel = "En",
        languageIsPrimary = primary,
        showShift = showShift,
        fullWidth = fullWidth,
        theme = theme,
    )

    /** 拼音态输入项(有大小写状态是 false 的,见 [StatusRowTest.拼音态不画大写状态] 那条)。 */
    private fun zhSpec(fullWidth: Boolean = true) = StatusRow.Spec(
        altLabel = "alt",
        digitsOnly = false,
        languageId = "zh",
        languageLabel = "拼",
        languageIsPrimary = true,
        showShift = false,
        fullWidth = fullWidth,
        theme = theme,
    )

    private fun labels(state: ImeState, spec: StatusRow.Spec = enSpec()) =
        StatusRow.present(state, spec).map { it.label }

    private fun kinds(state: ImeState, spec: StatusRow.Spec = enSpec()) =
        StatusRow.present(state, spec).map { it.kind }

    // ---- 语言 -------------------------------------------------------------------

    @Test
    fun `语言恒显 主语言主色 非主语言前景色`() {
        val s = ImeState().apply { languageId = "en" }
        val items = StatusRow.present(s, enSpec(primary = true))
        assertEquals(StatusRow.Kind.MODE, items.first().kind)
        assertEquals("En", items.first().label)
        assertEquals(theme.accent, items.first().color) // 主语言 = 主色
        val minor = StatusRow.present(s, enSpec(primary = false))
        assertEquals(theme.fg, minor.first().color) // 非主语言 = 前景色
    }

    @Test
    fun `数字框没有语言可切`() {
        val s = ImeState().apply { layerState = LayerState.Locked(Layer.L2) } // 数字类聚焦即锁 L2
        val got = kinds(s, enSpec(digitsOnly = true, altLabel = "0-9+*#"))
        assertEquals("数字态只剩 Alt 那一项(文字是实际可输字符集)", listOf(StatusRow.Kind.ALT), got)
    }

    // ---- 大写 -------------------------------------------------------------------

    @Test
    fun `大写用字形表达_ab_Ab_AB 三档`() {
        val s = ImeState()
        // 2026-09-30 用户定案「把 A 改为 ab Ab AB 的模式,不显示下划线锁定了」
        s.shiftMode = ShiftMode.OFF
        val off = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
        assertEquals("ab", off.label)
        assertEquals("未大写是前景色", theme.fg, off.color)
        assertEquals(StatusRow.OFF_LABEL, off.contentDescription)

        s.shiftMode = ShiftMode.ONCE
        val once = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
        assertEquals("Ab", once.label)
        assertEquals("大写一次是主色", theme.accent, once.color)
        assertEquals(StatusRow.ONCE_LABEL, once.contentDescription)

        s.shiftMode = ShiftMode.HELD
        val held = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
        assertEquals("按住中也是 Ab", "Ab", held.label)
        // 2026-10-02 用户「英文大写无论是一次性还是锁定还是长按都应该有颜色变化」:
        // 按住物理 Shift 此前走前景色,与 OFF 同色 —— 状态行看上去和没按一样。
        assertEquals("长按也应有颜色变化(accent)", theme.accent, held.color)
        assertEquals("长按与一次大写看起来一致,但朗读文本要区分", StatusRow.HELD_LABEL, held.contentDescription)

        s.shiftMode = ShiftMode.CAPS
        val caps = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
        assertEquals("AB", caps.label)
        assertEquals(theme.accent, caps.color)
        assertEquals(StatusRow.CAPS_LABEL, caps.contentDescription)
    }

    @Test
    fun `大写一律不带下划线`() {
        // 锁定与否由字形 AB/Ab 表达,不再叠一个下划线标记(与 alt/sym 的锁定表示分成两套语言)
        for (mode in ShiftMode.values()) {
            val s = ImeState().apply { shiftMode = mode }
            val item = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
            assertFalse("$mode 不该带下划线", item.locked)
        }
    }

    @Test
    fun `三种大写态都有颜色变化 只有 OFF 是前景色`() {
        // 颜色只回答"是不是大写",字形回答"哪一种"(2026-10-02 用户定)
        for (mode in listOf(ShiftMode.ONCE, ShiftMode.HELD, ShiftMode.CAPS)) {
            val s = ImeState().apply { shiftMode = mode }
            val item = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
            assertEquals("$mode 应为主色", theme.accent, item.color)
            assertTrue("$mode 不该与未大写同色", theme.fg != item.color)
        }
        val off = StatusRow.present(
            ImeState().apply { shiftMode = ShiftMode.OFF },
            enSpec(),
        ).single { it.kind == StatusRow.Kind.SHIFT }
        assertEquals("未大写仍是前景色", theme.fg, off.color)
    }

    @Test
    fun `英文态没有大写也显示 ab`() {
        val s = ImeState().apply { shiftMode = ShiftMode.OFF }
        assertTrue("英文态恒显", labels(s).contains("ab"))
    }

    @Test
    fun `拼音态不显示大写`() {
        val s = ImeState().apply { shiftMode = ShiftMode.CAPS }
        val spec = enSpec(showShift = false) // 拼音:showShift = false
        assertFalse(kinds(s, spec).contains(StatusRow.Kind.SHIFT))
    }

    // ---- 互斥(2026-09-29 用户定案)------------------------------------------------

    @Test
    fun `层生效时不显示大写_层优先`() {
        val s = ImeState().apply { shiftMode = ShiftMode.CAPS }
        s.layerState = LayerState.Locked(Layer.L2)
        val got = kinds(s)
        assertEquals("符号层上大小写不参与输出,两项不该并排", listOf(StatusRow.Kind.MODE, StatusRow.Kind.ALT), got)
        // 回 L1 它自己回来,而且仍是锁定态(状态没被清掉)——锁定现在由字形 AB 表达
        s.layerState = LayerState.L1
        val back = StatusRow.present(s, enSpec())
        val item = back.single { it.kind == StatusRow.Kind.SHIFT }
        assertEquals("AB", item.label)
        assertEquals(StatusRow.CAPS_LABEL, item.contentDescription)
    }

    @Test
    fun `alt 与 sym 互斥 effectiveLayer 是单值`() {
        val s = ImeState().apply { layerState = LayerState.Locked(Layer.L3) }
        assertEquals(listOf(StatusRow.Kind.MODE, StatusRow.Kind.SYM), kinds(s))
        s.layerState = LayerState.Locked(Layer.L2)
        assertEquals(listOf(StatusRow.Kind.MODE, StatusRow.Kind.ALT), kinds(s))
    }

    // ---- 只吃状态,不吃时间(2026-09-30)--------------------------------------------
    //
    // 原先这里有五条"敲击窗口内不显示暂定态"的断言。那条规则已删:敲击判定统一到 keyUp 之后,
    // 按住期间显示的东西和敲击落定的结果**长得一模一样**,藏它只会造成闪烁。

    @Test
    fun `按住中的层照样显示`() {
        val s = ImeState().apply { layerState = LayerState.Hold(Layer.L3, LayerState.L1) }
        assertTrue("长按 Sym 期间 sym 状态就该在", kinds(s).contains(StatusRow.Kind.SYM))
    }

    @Test
    fun `长按已有层状态 显示前后一模一样`() {
        // 真机报障「在 alt 状态下长按 alt 会有 alt 消失 0.5s 左右」的回归:
        // 借来的 L2 上按下 Alt → Hold(L2, 借用 L2),两次渲染必须**完全相同**,
        // 否则就是"闪一下"。锁定样式取自 snap,所以两者天然一致。
        val before = ImeState().apply { layerState = LayerState.OneShot(Layer.L2, LayerState.L1) }
        val during = ImeState().apply {
            layerState = LayerState.Hold(Layer.L2, LayerState.OneShot(Layer.L2, LayerState.L1))
        }
        assertEquals(StatusRow.present(before, enSpec()), StatusRow.present(during, enSpec()))

        val lockedBefore = ImeState().apply { layerState = LayerState.Locked(Layer.L2) }
        val lockedDuring = ImeState().apply { layerState = LayerState.Hold(Layer.L2, LayerState.Locked(Layer.L2)) }
        assertEquals(StatusRow.present(lockedBefore, enSpec()), StatusRow.present(lockedDuring, enSpec()))
    }

    @Test
    fun `锁定与借用的层都显示 区别只在下划线`() {
        val one = ImeState().apply { layerState = LayerState.OneShot(Layer.L2, LayerState.L1) }
        val locked = ImeState().apply { layerState = LayerState.Locked(Layer.L2) }
        assertFalse(StatusRow.present(one, enSpec()).single { it.kind == StatusRow.Kind.ALT }.locked)
        assertTrue(StatusRow.present(locked, enSpec()).single { it.kind == StatusRow.Kind.ALT }.locked)
    }

    @Test
    fun `互斥优先于锁定 层锁定时即使 Caps 开着也不显示大写`() {
        val s = ImeState().apply {
            layerState = LayerState.Locked(Layer.L2)
            shiftMode = ShiftMode.CAPS
        }
        val got = kinds(s)
        assertTrue(got.contains(StatusRow.Kind.ALT))
        assertFalse("层优先:Caps 期间不显示大写", got.contains(StatusRow.Kind.SHIFT))
    }

    // ---- 锁定表示分两套语言(2026-09-30 用户定案)-----------------------------------

    @Test
    fun `借用中的层不带下划线 底下的锁没显示但还在`() {
        // 用户报障「alt 锁定的时候单击 sym,sym 状态也是锁定的」。借用期间:
        // 显示的是 sym、**不带下划线**(借的,不是锁的);锁着的 Alt 暂时不显示(同一时间只显示
        // 一项层状态),借的那一下用完它就带着下划线回来。
        val s = ImeState().apply { layerState = LayerState.OneShot(Layer.L3, LayerState.Locked(Layer.L2)) }
        val during = StatusRow.present(s, enSpec())
        val sym = during.single { it.kind == StatusRow.Kind.SYM }
        assertFalse("借用不是锁定,不该有下划线", sym.locked)
        assertFalse("同一时间只显示一项层状态", kinds(s).contains(StatusRow.Kind.ALT))
        s.layerState = LayerState.Locked(Layer.L2) // 借完回锁
        val after = StatusRow.present(s, enSpec())
        assertTrue("回到锁:alt 带下划线", after.single { it.kind == StatusRow.Kind.ALT }.locked)
    }

    @Test
    fun `层锁定用下划线 大写锁定用字形`() {
        val s = ImeState().apply { layerState = LayerState.Locked(Layer.L2) }
        val alt = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.ALT }
        s.layerState = LayerState.Locked(Layer.L3)
        val sym = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SYM }
        // 层状态:锁定 = 下划线(alt/sym 仍是同一套语言)
        assertTrue(listOf(alt, sym).all { it.locked })
        // 大写:锁定 = 字形 AB,**不带下划线**——两套表示各管各的
        s.layerState = LayerState.L1
        s.shiftMode = ShiftMode.CAPS
        val shift = StatusRow.present(s, enSpec()).single { it.kind == StatusRow.Kind.SHIFT }
        assertFalse(shift.locked)
        assertEquals("AB", shift.label)
    }

    // ---- 全/半开关 ---------------------------------------------------------------

    @Test
    fun `全半开关只在拼音态显 且标的是当前状态`() {
        val s = ImeState().apply { languageId = "zh" }
        val on = StatusRow.present(s, zhSpec(fullWidth = true))
            .single { it.kind == StatusRow.Kind.FULLWIDTH }
        assertEquals("全", on.label)
        assertEquals(theme.accent, on.color)
        val off = StatusRow.present(s, zhSpec(fullWidth = false))
            .single { it.kind == StatusRow.Kind.FULLWIDTH }
        assertEquals("半", off.label)
        assertEquals(theme.fg, off.color)
        // 英文态没有全角概念 ⇒ 不显示这一项(判据在 present 里,不是 service 算好传进来的)
        assertFalse(
            kinds(s, enSpec()).contains(StatusRow.Kind.FULLWIDTH),
        )
        // 数字类同样绕过语言表 ⇒ 也不显示
        assertFalse(
            kinds(s, zhSpec().copy(digitsOnly = true)).contains(StatusRow.Kind.FULLWIDTH),
        )
    }

    // ---- 顺序与公理 A -----------------------------------------------------------

    @Test
    fun `状态项顺序固定为左区从左到右`() {
        val s = ImeState().apply {
            languageId = "zh"
            layerState = LayerState.Locked(Layer.L2)
        }
        val got = kinds(s, zhSpec())
        assertEquals(
            listOf(StatusRow.Kind.MODE, StatusRow.Kind.FULLWIDTH, StatusRow.Kind.ALT),
            got,
        )
    }

    @Test
    fun `纯函数 同样入参给同样的单子`() {
        val s = ImeState().apply {
            languageId = "en"
            shiftMode = ShiftMode.ONCE
            layerState = LayerState.OneShot(Layer.L3, LayerState.L1)
        }
        val a = StatusRow.present(s, enSpec())
        val b = StatusRow.present(s, enSpec())
        assertEquals(a, b)
    }
}
