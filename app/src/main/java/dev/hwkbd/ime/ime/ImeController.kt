package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.engine.PinyinEngine
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.keymap.TapTracker

/**
 * 纯逻辑输入法状态机:中英模式、三层键位、Alt/Sym 单双击锁定与长按临时层、Shift 单双击 Caps 与长按大写、
 * 拼音组词、候选分页与选字。只通过 [ImeHost] 回调与外界通信。
 *
 * 键事件入口约定:
 * - Alt 在 keyDown 进层并计数(单击一次性 / 双击锁定 / 长按临时层),keyUp 判定是否取消;
 * - Sym 在 keyUp 时若是独立敲击才生效;**按住 Alt/Sym 超过 [HOLD_MS] = 长按临时层,松开立即取消**;
 * - 按住修饰键期间按下别的键,这次长按即被"用掉",松开必然取消;
 * - Shift:单击=大写一次、双击=Caps 锁定、长按=按住期间大写(物理修饰键,松手取消);
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

    // ------------------------------------------------------------------ 长按修饰键

    /** 当前按住的层修饰键(Alt=L2 / Sym=L3),null 表示没有按住任何一个。 */
    private var holdMod: Layer? = null
    private var holdCode = 0
    private var holdAt = 0L

    /** 按住期间按过别的键(长按已被"用掉",松开必须取消)。 */
    private var holdUsed = false

    /** 本次按下前的 lock/oneShot 快照:长按松开时恢复,做到"松开即取消"。 */
    private var snapLock: Layer? = null
    private var snapOneShot: Layer? = null

    /** 本次敲击积累的连击数(>=2 表示刚刚建立了锁定,松开不取消)。 */
    private var holdTapN = 0

    /** 物理 Shift 按住状态(长按大写:大写来自物理修饰键,松手自然取消)。 */
    private var shiftHolding = false
    private var shiftHeldCode = 0
    private var shiftHeldAt = 0L
    private var shiftUsedAsModifier = false

    /** 本次 Shift keyDown 是否被消费(组词中消费、空闲透传);keyUp 必须与它成对,否则系统侧 Shift 会粘住。 */
    private var shiftDownConsumed = false

    /** 最近一次层修饰键敲击时间(渲染层判断一次性芯片是否仍在双击窗口内)。 */
    var lastLayerTapAt: Long = 0
        private set

    /** 解锁/取消后吸收同键连击(双击解除应保持解除)。 */
    private var suppressNextTap: Layer? = null
    private var suppressUntil = 0L

    // ------------------------------------------------------------------ 键事件

    fun onKeyDown(code: Int, ctrl: Boolean, shift: Boolean, caps: Boolean = false, repeat: Boolean = false): Boolean {
        // 按住修饰键期间按下任何别的键 = 这次长按"用掉了"(松开要取消,不再算独立敲击)
        if (!repeat) markModifiersUsed(code)
        if (code == symKeycode) {
            if (!repeat) {
                symDown = true
                symProducedKey = false
                // 长按 Sym = 按住 L3(松开取消);敲击语义(一次性/锁定)在 keyUp 判定
                beginHold(Layer.L3, code)
            }
            return true
        }
        if (altKeycodes.contains(code)) {
            if (!repeat) {
                // 组词中 Alt 直接选候选 5(显示位 4)
                if (state.hasComposition) {
                    selectCandidate(4)
                } else {
                    // 空闲:先记下按前状态再进层(松开取消时恢复),敲击计数仍在这里
                    // 另一个修饰键正按着时忽略(这次按压只是把那个长按"用掉")
                    if (holdMod == null || holdCode == code) {
                        beginHold(Layer.L2, code)
                        onAltTap()
                        holdTapN = altTracker.peek()
                    }
                }
            }
            return true
        }
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            // 组词中 Shift 直接选候选 4(显示位 0)
            if (state.hasComposition) {
                if (!repeat) {
                    selectCandidate(0)
                    shiftDownConsumed = true
                }
                return true
            }
            // 空闲:物理 Shift 透传给系统(长按大写由物理修饰键承担),同时武装自己的敲击判定
            if (!repeat) {
                shiftDownConsumed = false
                beginShiftHold(code)
            }
            return false
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
        if (ctrl) {
            ctrlDownAlone = false
            ctrlUsedCombo = true
            return handleCtrlKey(code, shift)
        }
        return handleNormalKey(code, shift, caps)
    }

    fun onKeyUp(code: Int): Boolean {
        // 物理 Shift 松开:快速独立敲击 → 单击大写一次 / 双击 Caps;长按 → 什么也不留(大写随松手消失)
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (shiftHolding && code == shiftHeldCode) {
                val quickTap = !shiftUsedAsModifier && System.currentTimeMillis() - shiftHeldAt < HOLD_MS
                shiftHolding = false
                state.shiftHeld = false
                if (quickTap) applyShiftTap() else host.render()
            }
            return shiftDownConsumed // 与 keyDown 的消费/透传严格成对,否则系统侧 Shift 会粘住
        }
        // Alt 的 keyDown 总是被消费,keyUp 也成对消费;正按着它则做长按收尾
        if (altKeycodes.contains(code)) {
            if (holdMod == Layer.L2 && code == holdCode) endHold()
            return true
        }
        // 长按 Sym 松开:超过 HOLD_MS 或按住期间按过键 = 取消 L3,不再算敲击
        if (code == symKeycode && holdMod == Layer.L3 && code == holdCode) {
            val quickTap = endHold()
            symDown = false
            // 敲击语义只属于"快速独立敲击"(组词中选候选 2 / 空闲切 L3 一次性)
            if (quickTap && !symProducedKey) {
                if (state.hasComposition) selectCandidate(1) else onSymTap()
            }
            return true
        }
        if (code == symKeycode && symDown) {
            symDown = false
            // 另一个修饰键还按着:这次 Sym 只是被"用掉",不走敲击语义
            if (holdMod == null && !symProducedKey) {
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

    // ------------------------------------------------------------------ 长按实现

    /**
     * 按下层修饰键:先记下按前的 lock/oneShot 快照,再把 [Layer] 挂到 [ImeState.hold]。
     * 敲击计数不在此处(Alt 在按下调用 [onAltTap],Sym 在松开时才计数)。
     */
    private fun beginHold(mod: Layer, code: Int) {
        if (holdMod != null) return
        holdMod = mod
        holdCode = code
        holdAt = System.currentTimeMillis()
        holdUsed = false
        holdTapN = 0
        snapLock = state.lock
        snapOneShot = state.oneShot
        state.hold = mod
        host.render()
    }

    /**
     * 松开层修饰键。返回 true = 这是一次快速独立敲击(< [HOLD_MS] 且按住期间没按过别的键),
     * 调用方据此走敲击语义;false = 长按结束:恢复按前的 lock/oneShot(松开即取消)并重置连击计数,
     * 唯独"这次敲击刚刚建立的锁定"保留(双击锁定后再按住不短于 300ms 也不该被抹掉)。
     */
    private fun endHold(): Boolean {
        val mod = holdMod
        val quickTap = mod != null &&
            System.currentTimeMillis() - holdAt < HOLD_MS && !holdUsed
        state.hold = null
        if (!quickTap && holdTapN < 2) {
            state.lock = snapLock
            state.oneShot = snapOneShot
            (if (mod == Layer.L2) altTracker else symTracker).reset()
        }
        holdMod = null
        holdUsed = false
        host.render()
        return quickTap
    }

    /** 按住修饰键期间按下别的键:标记这次长按已被使用(松开要取消)。 */
    private fun markModifiersUsed(code: Int) {
        if (holdMod != null && code != holdCode) holdUsed = true
        if (symDown && code != symKeycode) symProducedKey = true
        if (shiftHolding && code != shiftHeldCode) shiftUsedAsModifier = true
    }

    /** 空闲态按下物理 Shift:武装敲击判定(大写提示芯片随 [ImeState.shiftHeld] 亮起)。 */
    private fun beginShiftHold(code: Int) {
        shiftHolding = true
        shiftHeldCode = code
        shiftHeldAt = System.currentTimeMillis()
        shiftUsedAsModifier = false
        state.shiftHeld = true
        host.render()
    }

    /**
     * Shift 敲击:单击 = 大写一次(输入一个字母后取消,已是 Caps 则单击关掉锁定),
     * 双击 = Caps 大写锁定(再双击解除)。软键盘 ⇧ 与物理 Shift 共用。
     * 拼音态直接忽略——拼音不需要大小写锁定(状态芯片也只在英文态显示)。
     */
    private fun applyShiftTap() {
        if (state.mode == ImeMode.CN) return
        val n = shiftTracker.tap()
        state.shift = when {
            n == 1 -> if (state.shift == ShiftState.CAPS) ShiftState.OFF else ShiftState.ONCE
            else -> if (state.shift == ShiftState.CAPS) ShiftState.OFF else ShiftState.CAPS
        }
        host.render()
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

    /** 软键盘 Shift 键回调:单击大写一次,双击 Caps 锁定。 */
    fun onShiftTap() {
        applyShiftTap()
    }

    fun toggleMode() {
        flushComposition()
        state.mode = if (state.mode == ImeMode.CN) ImeMode.EN else ImeMode.CN
        // 拼音态没有大小写一说,切进来就把英文态可能挂着的锁定/一次性清掉
        if (state.mode == ImeMode.CN) state.shift = ShiftState.OFF
        host.render()
    }

    private fun layerKeyTap(mod: Layer, tracker: TapTracker) {
        val now = System.currentTimeMillis()
        // 解锁/取消后的短窗内吸收同键连击(双击解除应保持解除,不重新武装)
        if (suppressNextTap == mod && now < suppressUntil) {
            suppressNextTap = null
            tracker.reset()
            return
        }
        suppressNextTap = null
        lastLayerTapAt = now
        val n = tracker.tap()
        when {
            state.lock != null -> { // 锁定态:同层键解锁回 L1(并吸收连击),异层键切换锁定层
                val same = state.lock == mod
                state.lock = if (same) null else mod
                state.oneShot = null
                if (same) {
                    tracker.reset()
                    suppressNextTap = mod
                    suppressUntil = now + 250
                }
            }
            state.oneShot == mod && n == 1 -> { // 一次性激活后,窗口外的再次独立敲击 = 取消
                state.oneShot = null
                tracker.reset()
                suppressNextTap = mod
                suppressUntil = now + 250
            }
            n == 2 -> { // 双击窗口内第二次敲击 = 锁定该层
                state.lock = mod
                state.oneShot = null
            }
            n >= 3 -> { // 连击:从当前一次性层切换到下一层(L1↔L2/L3)
                state.oneShot = if (state.oneShot == Layer.L2) Layer.L3 else Layer.L2
                host.postDelayed({ host.render() }, DOUBLE_TAP_WINDOW + 20)
            }
            else -> { // 单击 = 一次性层;芯片延迟到双击窗口过期后显示(区分锁定/取消)
                state.oneShot = mod
                host.postDelayed({ host.render() }, DOUBLE_TAP_WINDOW + 20)
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
        val li = state.displayOrder(logical.size).getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        host.commitText(c.word)
        engine.onCommitted(c.word)
        clearComposition()
        host.render()
    }

    /** 长按候选(显示位):删除该词的用户频次/词条(可可同款),刷新当前候选。 */
    fun removeUserWord(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = state.displayOrder(logical.size).getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        engine.user.removeWord(c.word)
        refreshQuery()
    }

    /**
     * L4 虚拟按键输出:组词中先上屏首选再输出;文本原样上屏(配置即所见),
     * 功能键转发给目标应用。
     */
    fun commitL4Key(out: KeyOut) {
        flushComposition()
        when (out) {
            is KeyOut.Text -> host.commitText(out.text)
            is KeyOut.Special -> KeyOut.SPECIAL_CODES[out.name]?.let { host.sendKey(it) }
        }
        host.render()
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

        /** 层敲击双击判定窗口(TapTracker 一致)。 */
        const val DOUBLE_TAP_WINDOW = 200L

        /** 长按修饰键判定:按住 Alt/Sym/Shift 超过此时长算"长按"(松开即取消),不到则按敲击。 */
        const val HOLD_MS = 300L
    }
}
