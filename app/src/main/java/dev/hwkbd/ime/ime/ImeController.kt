package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.keymap.TapTracker

/**
 * 纯逻辑输入法状态机:中英模式、三层键位、Alt/Sym 单双击锁定、Shift/Caps、
 * 拼音组词、候选分页与选字。只通过 [ImeHost] 回调与外界通信。
 *
 * 键事件入口约定:
 * - Alt 单击在 keyDown 立即生效(一次性/锁定/解锁);
 * - Sym 在 keyUp 时若是独立敲击才生效;按住 Sym 期间按其他键 = Ctrl 组合(松开即失效);
 * - 返回 false 的键未被本输入法消费,由 service 原样放行(保证 Termux/vim 拿到原始按键)。
 */
class ImeController(
    private val host: ImeHost,
    var layout: KeyLayout,
    private val engine: PinyinEngine,
) {
    val state = ImeState()

    private val altTracker = TapTracker()
    private val symTracker = TapTracker()
    private val shiftTracker = TapTracker()

    /** Sym 键 keycode。Titan 2 实测:AGUI_SYM → KEYCODE_SYM(63),可用探测页改。 */
    var symKeycode: Int = KeyEvent.KEYCODE_SYM

    /** Alt 键 keycode(默认左右都算)。 */
    var altKeycodes: IntArray = intArrayOf(KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT)

    /** 中文态下 L2 符号输出全角。 */
    var fullWidthSymbolsInCn = true

    /** 翻页方向反转(DPAD_LEFT 变下一页)。 */
    var pageReverse = false

    private var symDown = false
    private var symProducedKey = false
    private var ctrlDownAlone = false
    private var ctrlUsedCombo = false

    // ------------------------------------------------------------------ 键事件

    fun onKeyDown(code: Int, ctrl: Boolean, shift: Boolean, caps: Boolean = false, repeat: Boolean = false): Boolean {
        if (code == symKeycode) {
            if (!repeat) {
                symDown = true
                symProducedKey = false
            }
            return true
        }
        if (altKeycodes.contains(code)) {
            if (!repeat) {
                // 组词中 Alt 直接选候选 5(显示位 4);空闲时进 L2
                if (state.hasComposition) selectCandidate(4) else onAltTap()
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            // 组词中 Shift 直接选候选 4(显示位 0);空闲透传给系统(双击 Caps 等)
            if (state.hasComposition && !repeat) selectCandidate(0)
            return state.hasComposition
        }
        if (code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT) {
            if (state.hasComposition) {
                if (!repeat) {
                    ctrlDownAlone = true
                    ctrlUsedCombo = false
                }
                return true
            }
            return false
        }
        if (symDown && !repeat) {
            symProducedKey = true
            return handleCtrlKey(code, shift)
        }
        if (ctrl) {
            ctrlDownAlone = false
            ctrlUsedCombo = true
            return handleCtrlKey(code, shift)
        }
        return handleNormalKey(code, shift, caps)
    }

    fun onKeyUp(code: Int): Boolean {
        if (code == symKeycode && symDown) {
            symDown = false
            if (!symProducedKey) {
                // 组词中 Sym 直接选候选 2(显示位 1);空闲时切 L3
                if (state.hasComposition) selectCandidate(1) else onSymTap()
            }
            return true
        }
        if ((code == KeyEvent.KEYCODE_CTRL_LEFT || code == KeyEvent.KEYCODE_CTRL_RIGHT) && ctrlDownAlone) {
            ctrlDownAlone = false
            // 独立 Ctrl 敲击(没组合任何键)组词中选候选 3(显示位 3)
            if (!ctrlUsedCombo && state.hasComposition) selectCandidate(3)
            return true
        }
        return false
    }

    // ------------------------------------------------------------------ 层与模式

    /** 软键盘 Alt 键回调。 */
    fun onAltTap() {
        layerKeyTap(Layer.L2, altTracker)
        host.render()
    }

    /** 软键盘 Sym 键回调。 */
    fun onSymTap() {
        layerKeyTap(Layer.L3, symTracker)
        host.render()
    }

    /** 软键盘 Shift 键回调:单击大写下一键,双击 Caps。 */
    fun onShiftTap() {
        val n = shiftTracker.tap()
        state.shift = if (n == 1) ShiftState.ONCE else if (state.shift == ShiftState.CAPS) ShiftState.OFF else ShiftState.CAPS
        host.render()
    }

    fun toggleMode() {
        flushComposition()
        state.mode = if (state.mode == ImeMode.CN) ImeMode.EN else ImeMode.CN
        host.render()
    }

    private fun layerKeyTap(mod: Layer, tracker: TapTracker) {
        val n = tracker.tap()
        when {
            state.lock == mod && n == 1 -> state.lock = null // 已锁定该层:单击解锁回 L1
            state.lock != null && state.lock != mod && n == 1 -> state.lock = mod // 锁定态下互切
            n == 1 -> state.oneShot = mod // 单击一次性
            n == 2 -> {
                state.lock = mod
                state.oneShot = null
            }
            else -> { // 三击循环 L1→L2→L3
                state.lock = when (state.effectiveLayer) {
                    Layer.L1 -> Layer.L2
                    Layer.L2 -> Layer.L3
                    Layer.L3 -> null
                }
                state.oneShot = null
            }
        }
    }

    // ------------------------------------------------------------------ 候选与组词

    /**
     * 选显示位 i 的候选上屏。显示位与物理键对应:
     * 0=Shift(候选4) 1=Sym(候选2) 2=空格(候选1,首选) 3=Ctrl(候选3) 4=Alt(候选5)。
     * 候选栏点选/上滑 5 区/五键选字/高亮确认都用显示位。
     */
    fun selectCandidate(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = ImeState.DISPLAY_ORDER.getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        host.commitText(c.word)
        engine.onCommitted(c.word)
        clearComposition()
        host.render()
    }

    /** 长按候选(显示位):删除该词的用户频次/词条(可可同款),刷新当前候选。 */
    fun removeUserWord(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = ImeState.DISPLAY_ORDER.getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        engine.user.removeWord(c.word)
        refreshQuery()
    }

    /** 词的用户使用频次(测试/调试用)。 */
    fun userCountOf(word: String): Int = engine.user.count(word)

    fun pageStep(delta: Int) {
        if (state.totalPages == 0) return
        state.page = (state.page + delta).coerceIn(0, state.totalPages - 1)
        state.highlight = -1
        host.render()
    }

    fun moveHighlight(delta: Int) {
        val valid = state.validDisplays()
        if (valid.isEmpty()) return
        state.highlight = if (state.highlight < 0 || state.highlight !in valid) {
            if (delta >= 0) valid.first() else valid.last()
        } else {
            val cur = valid.indexOf(state.highlight)
            valid[(cur + delta).coerceIn(0, valid.size - 1)]
        }
        host.render()
    }

    /** 词库异步载入完成后主动刷新当前查询。 */
    fun refreshQuery() {
        if (state.raw.isNotEmpty()) {
            state.candidates = engine.query(state.raw, CANDIDATE_LIMIT)
            state.page = 0
            state.highlight = -1
        } else {
            state.candidates = emptyList()
            state.page = 0
            state.highlight = -1
        }
        host.render()
    }

    fun clearComposition() {
        state.raw = ""
        state.candidates = emptyList()
        state.page = 0
        state.highlight = -1
    }

    /** 切换输入框/外部改动组词区:丢弃当前组词,保留模式与层。 */
    fun resetForNewInput() {
        clearComposition()
        symDown = false
        host.render()
    }

    /** onUpdateSelection 发现组词区被外部破坏时调用。 */
    fun abandonComposition() {
        clearComposition()
        host.render()
    }

    // ------------------------------------------------------------------ 内部处理

    private fun handleCtrlKey(code: Int, shift: Boolean): Boolean {
        // Ctrl+Q/W/E/R/T 按键盘几何对应五个候选槽(Q=最左 … T=最右)
        val idx = when (code) {
            KeyEvent.KEYCODE_Q -> 0
            KeyEvent.KEYCODE_W -> 1
            KeyEvent.KEYCODE_E -> 2
            KeyEvent.KEYCODE_R -> 3
            KeyEvent.KEYCODE_T -> 4
            else -> -1
        }
        if (idx >= 0 && state.hasComposition) {
            selectCandidate(idx)
            return true
        }
        host.sendKey(code, ctrl = true, shift = shift)
        return true
    }

    private fun handleNormalKey(code: Int, shift: Boolean, caps: Boolean): Boolean {
        val layer = state.effectiveLayer

        // Shift+Space = 中英切换(可可语义)
        if (shift && code == KeyEvent.KEYCODE_SPACE && layer == Layer.L1) {
            toggleMode()
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_SPACE -> return handleSpace(layer)
            KeyEvent.KEYCODE_ENTER -> {
                if (state.hasComposition) commitRaw() else host.sendKey(KeyEvent.KEYCODE_ENTER)
                consumeShiftOnce()
                return true
            }
            KeyEvent.KEYCODE_DEL -> {
                if (state.hasComposition) {
                    state.raw = state.raw.dropLast(1)
                    refreshQuery()
                } else {
                    host.sendKey(KeyEvent.KEYCODE_DEL)
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT -> return if (state.candidates.isNotEmpty()) {
                pageStep(if (pageReverse) 1 else -1); true
            } else false
            KeyEvent.KEYCODE_DPAD_RIGHT -> return if (state.candidates.isNotEmpty()) {
                pageStep(if (pageReverse) -1 else 1); true
            } else false
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_TAB -> return if (state.candidates.isNotEmpty()) {
                moveHighlight(1); true
            } else false
            KeyEvent.KEYCODE_DPAD_UP -> return if (state.candidates.isNotEmpty() && state.highlight >= 0) {
                selectCandidate(state.highlight); true
            } else false
        }

        val out = layout.lookup(layer, code) ?: return false

        when (out) {
            is KeyOut.Text -> {
                var text = out.text
                val isLetter = text.length == 1 && text[0] in 'a'..'z'
                when (state.mode) {
                    ImeMode.CN ->
                        if (isLetter) {
                            state.raw += text
                            refreshQuery()
                        } else {
                            emitSymbol(text, layer)
                        }
                    ImeMode.EN -> {
                        val upper = shift || caps || state.shift != ShiftState.OFF
                        if (upper) text = text.uppercase()
                        host.commitText(text)
                    }
                }
                consumeShiftOnce()
                consumeOneShot()
                host.render()
                return true
            }
            is KeyOut.Special -> {
                flushComposition()
                KeyOut.SPECIAL_CODES[out.name]?.let { host.sendKey(it) }
                consumeShiftOnce()
                consumeOneShot()
                host.render()
                return true
            }
        }
    }

    private fun handleSpace(layer: Layer): Boolean {
        if (state.hasComposition) {
            if (state.candidates.isNotEmpty()) {
                selectCandidate(if (state.highlight >= 0) state.highlight else TOP_DISPLAY)
            } else {
                commitRaw()
            }
            return true
        }
        host.commitText(" ")
        host.render()
        return true
    }

    private fun emitSymbol(text: String, layer: Layer) {
        flushComposition()
        var out = text
        if (state.mode == ImeMode.CN && fullWidthSymbolsInCn && layer == Layer.L2) {
            // 中文标点特判(.→。),其余 ASCII 标点全角(,→，?→？…);
            // 数字保持半角(Alt 层九宫格 0-9 常用于输号码)
            out = out.map { c ->
                when {
                    c == '.' -> '。'
                    c.code in 0x21..0x2F || c.code in 0x3A..0x40 ||
                        c.code in 0x5B..0x60 || c.code in 0x7B..0x7E -> (c.code + 0xFEE0).toChar()
                    else -> c
                }
            }.joinToString("")
        }
        host.commitText(out)
    }

    /** 组词被符号/功能键打断:先把当前首选上屏(可可同款体验)。 */
    private fun flushComposition() {
        if (!state.hasComposition) return
        if (state.candidates.isNotEmpty()) selectCandidate(TOP_DISPLAY) else commitRaw()
    }

    private fun commitRaw() {
        host.commitText(state.raw)
        clearComposition()
        host.render()
    }

    private fun consumeOneShot() {
        state.oneShot = null
    }

    private fun consumeShiftOnce() {
        if (state.shift == ShiftState.ONCE) state.shift = ShiftState.OFF
    }

    companion object {
        private const val CANDIDATE_LIMIT = 45

        /** 首选(逻辑第 1 候选)的显示位 = 中间(空格键)。 */
        const val TOP_DISPLAY = 2
    }
}
