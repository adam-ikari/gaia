package dev.hwkbd.ime.ime

import android.view.KeyEvent
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.keymap.TapTracker
import dev.hwkbd.ime.strategy.FieldKind
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
            // 数字框限定 0-9:SYM 芯片已隐藏,按下也不进 L3(L3 敲出的全是非数字)
            if (fieldProfile().digitsOnly) return true
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
        // 数字框:keyDown 已吞,keyUp 成对吞掉(不触发 L3 敲击语义)
        if (code == symKeycode && fieldProfile().digitsOnly) return true
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
     * 切到下一个语言(Shift+空格 / 语言芯片)。
     * 2026-09-29 用户定案:状态芯片**不可触摸**,切换只走物理按键 —— `Shift+Space`。
     */
    fun cycleLanguage() = setLanguage(registry.nextLanguage(state.languageId))

    /**
     * 切到指定语言(密码框强制英文等场景)。先把挂着的组词上屏,切入收尾交给语言策略
     * ([dev.hwkbd.ime.strategy.ChineseLanguage.onEnter] 清掉英文态的大小写锁定)。
     * **密码框内(secure)禁止切到安全语言以外**——切语言的两个入口(状态栏语言芯片、
     * Shift+空格)都收口在这里,一处守卫全覆盖。
     */
    fun setLanguage(id: String) {
        if (state.secure && id != registry.secureLanguageId) return
        val lang = registry.language(id)
        if (state.languageId == lang.id) return
        flushComposition()
        state.languageId = lang.id
        lang.onEnter(state)
        host.render()
    }

    private fun layerKeyTap(mod: Layer, tracker: TapTracker) {
        // 数字类的 L2 锁是**字段给的**(聚焦即锁,免按 Alt):解不掉,否则 L1 全被吞没法打字。
        // 触摸切换已下线,这条守卫现在只挡物理 Alt 键。
        if (mod == Layer.L2 && state.lock == Layer.L2 && fieldProfile().digitsOnly) {
            state.lock = Layer.L2
            state.oneShot = null
            host.render()
            return
        }
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
     * 选显示位 i 的候选上屏。显示位与物理五键对应(左→右 = 4 2 1 3 5):
     * 0=Shift(候选4) 1=Sym(候选2) 2=空格(候选1,首选) 3=Ctrl(候选3) 4=Alt(候选5)。
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
            is KeyOut.Text -> {
                // 策略先改写按键文本(拼音:v → 分节符),再决定进缓冲还是上屏
                val text = language().mapText(out.text, state)
                if (language().composes(text, state)) {
                    state.raw += text
                    refreshQuery()
                } else {
                    flushComposition()
                    host.commitText(text)
                }
            }
            is KeyOut.Special -> {
                flushComposition()
                KeyOut.SPECIAL_CODES[out.name]?.let { host.sendKey(it) }
            }
        }
        host.render()
    }

    /**
     * 状态行 L4 中区的键序 = **当前语言注册的补差键排最左**(空则直接是键位套;拼音没有——
     * 分节符走 `v` 键,不占屏)**接输入类型选的键位套**(网址框 → http:// 等)。
     * UI 按 [l4Signature] 变化才重建面板。
     *
     * 「中文全角」开关一并传下去:拼音态的 L2/L3 层表随它整体切到中文标点或退回 ASCII
     * ([Language.layerMap]),L4 那把"L1-L3 敲不出就不显示"的筛必须用同一份表,
     * 否则关了全角会把 `；` 这类误判掉、屏上一个分号都敲不出。
     */
    fun l4Keys(): List<L4Key> =
        registry.l4Keys(layout, state.fieldKind, state.languageId, fullWidthSymbolsInCn)

    /**
     * [l4Keys] 是否要重建键帽的判定依据 = **键序内容的 hash**(2026-09-29 用户定案)。
     * 不再手工拼"键位套 id|语言 id"那种签名:那种签名要靠人记得把每个影响键序的来源都列进去,
     * 漏一个就出现"键换了但没重绘"(旧坑:键位表变了/核心键变了,签名没变 → 键帽不更新)。
     * 直接对**最终键序**(标签 + 输出,逐把拼)取 hash:内容变 = hash 变 = 重建,内容没变就不重建。
     */
    fun l4Signature(): String = l4Keys().fold(1) { acc, k ->
        acc * 31 + k.label.hashCode() * 31 + k.out.format().hashCode()
    }.toString()

    /**
     * 输入框切换(服务在 onStartInput 调):**数字类(digitsOnly)直接锁定 L2 数字层**——
     * 聚焦即能敲 0-9,免按 Alt(alt 锁定的数字变种,用户 2026-09-28 定案);
     * 离开数字类时解除这把"字段加的锁"。
     */
    fun setFieldKind(kind: FieldKind) {
        val prev = state.fieldKind
        state.fieldKind = kind
        if (prev == kind) return
        val nowDigit = registry.fieldProfile(kind).digitsOnly
        val wasDigit = registry.fieldProfile(prev).digitsOnly
        when {
            nowDigit -> state.lock = Layer.L2
            wasDigit && state.lock == Layer.L2 -> state.lock = null
        }
        host.render()
    }

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

    /**
     * **大写 / Alt(L2) / Sym(L3) 全部回到初始态**(2026-09-29 用户定案,输入法隐藏时调用):
     * L1、无锁定、无一次性、长按层解除、大写关,连同长按/连击那套内部状态一起清干净。
     *
     * 与 [resetForNewInput] 的分工:那个刻意**保留**模式与层(换一个输入框不该丢用户选的层);
     * 这个是给"窗口都收起来了"用的——锁定过的 L2、开着的大写若原样留着,下次弹出来会莫名其妙
     * 还停在那个态,状态芯片也会显示一个用户根本没在用的层。语言与输入框类型不动。
     *
     * 内部那些 hold/snap/suppress 字段必须一起清:它们记的是"这次长按/连击的前置状态",
     * 跨过一次隐藏就该丢弃,否则下一次长按 Alt 会去恢复一份早已过期的快照。
     */
    fun resetLayers() {
        state.lock = null
        state.oneShot = null
        state.hold = null
        state.shift = ShiftState.OFF
        state.shiftHeld = false
        holdMod = null
        holdCode = 0
        holdAt = 0L
        holdUsed = false
        snapLock = null
        snapOneShot = null
        holdTapN = 0
        shiftHolding = false
        shiftHeldCode = 0
        shiftHeldAt = 0L
        shiftUsedAsModifier = false
        symDown = false
        symProducedKey = false
        suppressNextTap = null
        suppressUntil = 0L
        host.render()
    }

    /** onUpdateSelection 发现组词区被外部破坏时调用。 */
    fun abandonComposition() {
        clearComposition()
        host.render()
    }

    // ------------------------------------------------------------------ 内部处理

    /** 当前输入框的表现策略(键位套 / Alt 芯片文字 / 数字限定)。 */
    private fun fieldProfile() = registry.fieldProfile(state.fieldKind)

    private fun handleCtrlKey(code: Int, shift: Boolean): Boolean {
        // 候选只由五键(Shift/Sym/空格/Ctrl/Alt)选:Ctrl 组合一律原样转发给应用,
        // Ctrl+Q/W/E/R/T 不参与选字(vim 的 Ctrl+C/Ctrl+W 等组合必须透传)。
        host.sendKey(code, ctrl = true, shift = shift)
        return true
    }

    /**
     * 数字类字符放行判定(只管物理键路径;L4 虚拟键走 [commitL4Key],原样上屏不过筛):
     * `0-9` + 该类型的合法单字符 [dev.hwkbd.ime.strategy.FieldProfile.digitChars]
     * (这些字符 L2 上都有键帽,物理键直接敲得出,**所以它们不上 L4**),
     * **再并入当前 L4 面板显示的键的输出**——L4 放的是 L1-L3 敲不出的键或短语组合,
     * 显示什么就放行什么,多字符组合键一并放行。
     */
    private fun digitAllowed(text: String): Boolean {
        val fp = fieldProfile()
        if (text.length == 1 && (text[0] in '0'..'9' || text[0] in fp.digitChars)) return true
        return l4Keys().any { it.out is KeyOut.Text && it.out.text == text }
    }

    private fun handleNormalKey(code: Int, shift: Boolean, caps: Boolean): Boolean {
        val layer = state.effectiveLayer
        val fp = fieldProfile()
        val digitsOnly = fp.digitsOnly

        // Shift+Space = 中英切换(可可语义);**层锁定/一次性/长按按住时同样生效**
        // (2026-09-29 用户要求:锁定状态下也要能切拼音和 En)。
        // 数字类锁着数字层,没有语言可切(芯片已隐藏),维持排除。
        if (shift && code == KeyEvent.KEYCODE_SPACE && !digitsOnly) {
            cycleLanguage()
            return true
        }

        when (code) {
            // 数字框限定 0-9:空格吞掉(数字不需要空格,状态按钮也全隐藏)
            KeyEvent.KEYCODE_SPACE -> return if (digitsOnly) true else handleSpace()
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

        // 层键表:先问**当前语言的专属表**([Language.layerMap],拼音态的 L2/L3 换成中文标点),
        // 没有再用键位表的出厂值。没这一层时语言切换只影响字母与 L4,固定键的符号还是 ASCII。
        //
        // **数字类绕过语言表**([FieldProfile.digitsOnly]):那一档的合法字符集是**照着出厂
        // ASCII L2 的槽位**定的(数字位 + `- . + * # / :` 各占一键),芯片上的 `0-9-/:`
        // 也是照它标的。中文表把那些槽位换成了《》（》,再套上去日期框就输不出 `/`、电话框
        // 输不出 `+`,芯片也跟着说谎。数字框本来就没有"语言"可言,走键位表出厂值。
        //
        // 「中文全角」开关一并传下去:关掉时拼音的整张层表退回出厂 ASCII,那个开关才不是
        // 死开关(见 [Language.layerMap])。
        val lang = language()
        val layerMap = if (digitsOnly) null else lang.layerMap(layer, fullWidthSymbolsInCn)
        val out = layerMap?.get(code) ?: layout.lookup(layer, code) ?: return false

        when (out) {
            is KeyOut.Text -> {
                // 策略先改写按键文本(拼音:组词中的 v → 分隔符 `'`),再走组词/上屏两条路
                val text = lang.mapText(out.text, state)
                // 数字类限定合法字符(0-9 + 该类型放行的单字符 + L4 显示的键输出):其余吞掉,
                // 不落屏也不消耗修饰态;多字符组合键(单字符规则拦掉的那些)由 L4 那一支放行
                if (digitsOnly && !digitAllowed(text)) return true
                if (lang.composes(text, state)) {
                    // 进组词缓冲(拼音字母;分隔符只在组词中被接纳)
                    state.raw += text
                    refreshQuery()
                } else {
                    // 语言策略决定上屏形态:英文跟大写、中文按开关全角;**数字类放行的字符强制半角**
                    // (全角 `－` `。` `：` 进不了日期/小数/电话/签名的合法格式,数字本身本就半角)
                    flushComposition()
                    val upper = shift || caps || state.shift != ShiftState.OFF
                    val full = fullWidthSymbolsInCn && !digitsOnly
                    host.commitText(lang.commitText(text, layer, upper, full))
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
