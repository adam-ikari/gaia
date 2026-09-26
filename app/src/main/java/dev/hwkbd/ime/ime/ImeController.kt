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
            if (!repeat) onAltTap()
            return true
        }
        if (symDown && !repeat) {
            symProducedKey = true
            return handleCtrlKey(code, shift)
        }
        if (ctrl) return handleCtrlKey(code, shift)
        return handleNormalKey(code, shift, caps)
    }

    fun onKeyUp(code: Int): Boolean {
        if (code == symKeycode && symDown) {
            symDown = false
            if (!symProducedKey) onSymTap()
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

    fun selectCandidate(i: Int) {
        val items = state.pageItems()
        if (i !in items.indices) return
        val c = items[i]
        host.commitText(c.word)
        engine.onCommitted(c.word)
        clearComposition()
        host.render()
    }

    fun pageStep(delta: Int) {
        if (state.totalPages == 0) return
        state.page = (state.page + delta).coerceIn(0, state.totalPages - 1)
        state.highlight = -1
        host.render()
    }

    fun moveHighlight(delta: Int) {
        val items = state.pageItems()
        if (items.isEmpty()) return
        state.highlight = if (state.highlight < 0) 0 else (state.highlight + delta).coerceIn(0, items.size - 1)
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
        // L3 的空格输出编程串(默认 "->")
        val l3 = layout.mapFor(Layer.L3)[KeyEvent.KEYCODE_SPACE] as? KeyOut.Text
        if (layer == Layer.L3 && l3 != null) {
            flushComposition()
            host.commitText(l3.text)
            consumeOneShot()
            host.render()
            return true
        }
        if (state.hasComposition) {
            if (state.candidates.isNotEmpty()) {
                selectCandidate(if (state.highlight >= 0) state.highlight else 0)
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
        if (state.mode == ImeMode.CN) {
            if (fullWidthSymbolsInCn && layer == Layer.L2) out = fullwidth(out)
            if (layer == Layer.L1 && out.length == 1) out = CN_PUNCT[out] ?: out
        }
        host.commitText(out)
    }

    /** 组词被符号/功能键打断:先把当前首选上屏(可可同款体验)。 */
    private fun flushComposition() {
        if (!state.hasComposition) return
        if (state.candidates.isNotEmpty()) selectCandidate(0) else commitRaw()
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

        /** 中文态 L1 标点的全角形态。 */
        private val CN_PUNCT = mapOf(
            "," to "\uFF0C",
            "." to "\u3002",
            ";" to "\uFF1B",
            "?" to "\uFF1F",
            "!" to "\uFF01",
        )

        private fun fullwidth(s: String): String = buildString {
            for (c in s) append(if (c.code in 0x21..0x7E) (c.code + 0xFEE0).toChar() else c)
        }
    }
}
