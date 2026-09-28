package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.keymap.TapTracker
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.InputMethod
import dev.hwkbd.ime.strategy.Language

/**
 * 纯逻辑输入法状态机:语言(策略注册表)、三层键位、Alt/Sym 单双击锁定与长按临时层、Shift 单双击 Caps 与长按大写、
 * 组词、候选分页与选字。只通过 [ImeHost] 回调与外界通信。
 *
 * 语言与输入法都是**策略**:状态机按 [ImeState.languageId] 到 [ImeRegistry] 取 [Language]/[InputMethod],
 * 加语言/加输入法只注册不改这里;状态行 L4 中区同理按输入框类型选键位套(见 [l4Keys])。
 *
 * 键事件入口约定:
 * - Alt 在 keyDown 进层并计数(单击一次性 / 双击锁定 / 长按临时层),keyUp 判定是否取消;
 * - Sym 在 keyUp 时若是独立敲击才生效;**按住 Alt/Sym 超过 [HOLD_MS] = 长按临时层,松开立即取消**;
 * - 按住修饰键期间按下别的键,这次长按即被"用掉",松开必然取消;
 * - Shift:单击=大写一次(已开着的任意大写状态再单击=关掉)、双击=Caps 锁定(再双击解除)、长按=按住期间大写(物理修饰键,松手取消);
 * - 返回 false 的键未被本输入法消费,由 service 原样放行(保证 Termux/vim 拿到原始按键)。
 */
class ImeController(
    private val host: ImeHost,
    var layout: KeyLayout,
    /** 策略注册表:语言 / 输入法 / 输入框表现都从这里取。 */
    val registry: ImeRegistry = ImeRegistry.builtin(),
) {
    val state = ImeState()

    private val altTracker = TapTracker()
    private val symTracker = TapTracker()
    private val shiftTracker = TapTracker()

    /** 当前语言策略(状态机只跟接口打交道)。 */
    private fun language(): Language = registry.language(state.languageId)

    /** 当前语言绑定的输入法策略。 */
    private fun inputMethod(): InputMethod = registry.method(language().methodId)

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
     * Shift 敲击。两条规则(软键盘 ⇧ 与物理 Shift 共用):
     * - **独立单击**(双击窗口外)= 开关自合:OFF → 大写一次;大写一次 / Caps → 关掉。
     *   已开着的任意大写状态再单击必然回 OFF —— Shift 永远是自己的退出键,不存在按不掉的死状态。
     * - **双击**(窗口内)= 跳到 Caps 锁定;已是 Caps 再双击解除。
     *   窗口内第二击被双击征用,所以"ONCE 时手快再按"得到的是 Caps,之后再按一次必然回 OFF。
     * 大写一次仍会在输入一个字母后自动取消。
     * 拼音态直接忽略——拼音不需要大小写锁定(状态芯片也只在英文态显示)。
     */
    private fun applyShiftTap() {
        if (!language().shiftTapsEnabled) return
        val n = shiftTracker.tap()
        state.shift = when {
            n == 1 -> if (state.shift == ShiftState.OFF) ShiftState.ONCE else ShiftState.OFF
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

    /**
     * 状态栏层芯片(ALT/SYM)**单击** = 单次切换:切到该层并落 [ImeState.oneShot]
     * (出下一个键后自动回 L1);已在该层时再点 = 取消回 L1。顺带清掉可能挂着的 lock,
     * 单击永远只是"临时切一下"。
     */
    fun tapLayerChip(target: Layer) {
        val active = state.effectiveLayer == target
        state.oneShot = if (active) null else target
        state.lock = null
        host.render()
    }

    /**
     * 状态栏层芯片(ALT/SYM)**长按** = 锁定切换:落 [ImeState.lock] 一直保持;
     * 已在该层锁定时再长按 = 解锁回 L1。顺带清掉一次性层。
     */
    fun longPressLayerChip(target: Layer) {
        val locked = state.lock == target
        state.lock = if (locked) null else target
        state.oneShot = null
        host.render()
    }

    /** 状态栏大写芯片触摸(英文态):小写 ⇄ Caps 锁定。 */
    fun tapShiftChip() {
        if (!language().shiftTapsEnabled) return
        state.shift = if (state.shift == ShiftState.CAPS) ShiftState.OFF else ShiftState.CAPS
        host.render()
    }

    /** 状态行语言芯片 / Shift+空格:循环到注册表里的下一个语言。 */
    fun cycleLanguage() = setLanguage(registry.nextLanguage(state.languageId))

    /**
     * 切到指定语言(密码框强制英文等场景)。先把挂着的组词上屏,切入收尾交给语言策略
     * ([dev.hwkbd.ime.strategy.ChineseLanguage.onEnter] 清掉英文态的大小写锁定)。
     */
    fun setLanguage(id: String) {
        val lang = registry.language(id)
        if (state.languageId == lang.id) return
        flushComposition()
        state.languageId = lang.id
        lang.onEnter(state)
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
            }
            else -> { // 单击 = 一次性层,芯片立刻渲染(描边),双击升级为锁定(实心)——无延迟无闪烁
                state.oneShot = mod
            }
        }
    }

    // ------------------------------------------------------------------ 候选与组词

    /**
     * 选显示位 i 的候选上屏。显示位与物理五键对应(左→右 = 候选2/5/1/4/3):
     * 0=Shift(候选2) 1=Sym(候选5) 2=空格(候选1,首选) 3=Ctrl(候选4) 4=Alt(候选3)。
     * 候选栏点选/上滑 5 区/五键选字/高亮确认都用显示位;Ctrl+QWERT 不选字(原样转发)。
     */
    fun selectCandidate(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = state.displayOrder(logical.size).getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        host.commitText(c.word)
        inputMethod().onCommitted(c.word)
        clearComposition()
        host.render()
    }

    /** 长按候选(显示位):删除该词的用户频次/词条(可可同款),刷新当前候选。 */
    fun removeUserWord(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = state.displayOrder(logical.size).getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        inputMethod().removeUserWord(c.word)
        refreshQuery()
    }

    /**
     * L4 虚拟按键输出。文本键**原样上屏**(配置即所见);语言策略判定为组词字符的键跟着组词
     * (拼音分隔符 `'`:组词中追加分节、不打断组词),功能键转发给目标应用。
     */
    fun commitL4Key(out: KeyOut) {
        when (out) {
            is KeyOut.Text ->
                if (language().composes(out.text, state)) {
                    state.raw += out.text
                    refreshQuery()
                } else {
                    flushComposition()
                    host.commitText(out.text)
                }
            is KeyOut.Special -> {
                flushComposition()
                KeyOut.SPECIAL_CODES[out.name]?.let { host.sendKey(it) }
            }
        }
        host.render()
    }

    /**
     * 状态行 L4 中区的键序 = **输入类型选的键位套**(网址框 → http:// 等)
     * **并上当前语言的补差键**(拼音 → 分隔符 `'`)。UI 按 [l4Signature] 变化才重建面板。
     */
    fun l4Keys(): List<L4Key> = registry.l4Keys(layout, state.fieldKind, state.languageId)

    /** [l4Keys] 的变化签名(键位套 id|语言 id):没变就不重建面板。 */
    fun l4Signature(): String =
        "${registry.fieldProfile(state.fieldKind).l4ProfileId}|${state.languageId}"

    /** 词的用户使用频次(测试/调试用)。 */
    fun userCountOf(word: String): Int = inputMethod().userCount(word)

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
            state.candidates = inputMethod().query(state.raw, CANDIDATE_LIMIT)
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
        // 候选只由五键(Shift/Sym/空格/Ctrl/Alt)选:Ctrl 组合一律原样转发给应用,
        // Ctrl+Q/W/E/R/T 不参与选字(vim 的 Ctrl+C/Ctrl+W 等组合必须透传)。
        host.sendKey(code, ctrl = true, shift = shift)
        return true
    }

    private fun handleNormalKey(code: Int, shift: Boolean, caps: Boolean): Boolean {
        val layer = state.effectiveLayer

        // Shift+Space = 中英切换(可可语义)
        if (shift && code == KeyEvent.KEYCODE_SPACE && layer == Layer.L1) {
            cycleLanguage()
            return true
        }

        when (code) {
            KeyEvent.KEYCODE_SPACE -> return handleSpace()
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
                val lang = language()
                if (lang.composes(out.text, state)) {
                    // 进组词缓冲(拼音字母;分隔符只在组词中被接纳)
                    state.raw += out.text
                    refreshQuery()
                } else {
                    // 语言策略决定上屏形态:英文跟大写、中文按开关全角
                    flushComposition()
                    val upper = shift || caps || state.shift != ShiftState.OFF
                    host.commitText(lang.commitText(out.text, layer, upper, fullWidthSymbolsInCn))
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

    private fun handleSpace(): Boolean {
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

        /** 长按修饰键判定:按住 Alt/Sym/Shift 超过此时长算"长按"(松开即取消),不到则按敲击。 */
        const val HOLD_MS = 300L
    }
}
