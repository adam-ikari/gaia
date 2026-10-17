package dev.hwkbd.ime.ime

import dev.hwkbd.ime.engine.Candidate
import android.view.KeyEvent
import dev.hwkbd.ime.keymap.KeyLayout
import dev.hwkbd.ime.keymap.KeyOut
import dev.hwkbd.ime.keymap.L4Key
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.ChineseLanguage
import dev.hwkbd.ime.strategy.FieldKind
import dev.hwkbd.ime.strategy.ImeRegistry
import dev.hwkbd.ime.strategy.InputMethod
import dev.hwkbd.ime.strategy.Language
import dev.hwkbd.ime.strategy.SyllableSpelling

/**
 * 纯逻辑输入法状态机:语言(策略注册表)、三层键位、Alt/Sym 单双击锁定与长按临时层、Shift 单双击 Caps 与长按大写、
 * 组词、候选分页与选字。只通过 [ImeHost] 回调与外界通信。
 *
 * 语言与输入法都是**策略**:状态机按 [ImeState.languageId] 到 [ImeRegistry] 取 [Language]/[InputMethod],
 * 加语言/加输入法只注册不改这里;状态行 L4 中区同理按输入框类型选键位套(见 [l4Keys])。
 *
 * 键事件入口约定:
 * - Alt / Sym 在 keyDown 进 [LayerState.Hold] 并记快照,keyUp 判定"快速独立敲击"还是长按,
 *   **只有敲击才落定新状态**(长按即取消、恢复快照);按住期间按下别的键,这次长按被"用掉";
 * - Shift 同理:keyDown 置 [ShiftMode.HELD],keyUp 恢复按前的值,再判敲击还是长按;
 * - 敲击落定**立刻**反映到状态行——状态行是状态的纯函数,不再按敲击窗口藏"暂定"态
 *   (见 [dev.hwkbd.ime.ui.StatusRow]);
 * - 返回 false 的键未被本输入法消费,由 service 原样放行(保证 Termux/vim 拿到原始按键)。
 *
 * ## 修饰键状态迁移总表(2026-09-30 整条梳理)
 *
 * 三个修饰键**同一套骨架**,差别只在"落定什么"那一格:
 * `keyDown` 记快照进 [LayerState.Hold]/`shiftBeforeHold` → `keyUp` 判是不是快速独立敲击 →
 * **是**才落定新状态(敲击),**否**就恢复快照(长按取消)。所以"长按"永远不会留下一次敲击,
 * 也就**不需要**任何"记住这次敲了几个来补救"的字段。
 *
 * | 事件 | Alt(L2) | Sym(L3) | Shift(大写) |
 * |---|---|---|---|
 * | keyDown 组词中 | 选候选 5 | 选候选 2(在 keyUp) | 选候选 4 |
 * | keyDown 数字类 | 照常(数字类的 L2 锁见下) | **吞掉**,不进 L3 | 照常 |
 * | keyDown 别的修饰键正按着 | 忽略(只把那个长按"用掉") | 同左 | 同左 |
 * | keyDown 空闲 | [beginHold] → `Hold(L2, snap)` | `Hold(L3, snap)` | `shiftMode = HELD` |
 * | keyUp 快速独立 | [endHold] + [layerKeyTap] | 同左 | `applyShiftTap()` |
 * | keyUp 长按/被用掉 | 恢复 `snap` | 恢复 `snap` | 恢复按前的大写 |
 *
 * 敲击落定的状态迁移(层,Alt 与 Sym 共用 [layerKeyTap];`n` = 敲击窗口内的连续次数。
 * **主轴是 `n`**:锁定只由双击产生,单击是**借用**一个层、用完恢复底下那份。
 * **`n>=2` 必须排第一**:第二击的可见层正是这个键,排在"取消"之后会被当成取消借用):
 * ```
 * 数字类且已锁 L2   →  Locked(L2)      (守卫:数字类的锁解不掉,否则 L1 全被吞没法打字)
 * 命中解锁吸收窗     →  不变            (双击解除后手抖再按一下,别重新武装)
 * n>=2              →  Locked(该层)+清计数 (双击 = 锁定;锁在别的层时 = 把锁搬过去。
 *                                    清计数:下一击重新算单击,否则窗口内第三击又是 n>=2)
 * Locked(同层)      →  L1    + 吸收窗   (敲同层键 = 解锁;双击的第二下被吸收窗吃掉)
 * 临时层(借用/按住中)→  cur.base + 吸收窗 (敲的正是当前那层、或底下锁着的那层 = 收掉它)
 * 其余单击           →  borrow(该层, cur) (借用一个键;恢复目标 = 底下那份:有锁回锁,没锁回 L1)
 * ```
 * 借用的生命周期:下一个键消费掉([consumeOneShot])或再敲一下同层键,都恢复 `snap`;
 * `snap` 由 [LayerState.borrow] 造 —— 锁定留下,没被用掉的旧借用丢掉。
 * 大写([applyShiftTap],拼音态直接忽略——拼音没有大小写):
 * ```
 * n=1  → OFF→ONCE,ONCE/CAPS→OFF     (Shift 永远是自己的退出键)
 * n>=2 → CAPS→OFF,其它→CAPS         (双击 = Caps 锁定 / 再双击解除)
 * ```
 * 其它迁移:字母键消费 `OneShot` 与 `ONCE`;聚焦数字类 → `Locked(L2)`,离开数字类且锁着 L2 → `L1`;
 * 切语言 → 语言策略 [Language.onEnter] 清大写(层**不**清,换一个输入框不该丢用户选的层);
 * 窗口隐藏 → [resetLayers] 全回初始;换输入框(调出输入法)→ 同样全回初始
 * (2026-10-12 用户「每次调出输入法需要恢复到初始状态」,复位在 setFieldKind 之前)。
 *
 * **只有单击和双击两种事件(2026-09-30 用户定案「三连击无效」)**:窗口内第三次及以后的敲击
 * 不再是独立事件,只是"锁定态下的一次单击" = 解锁。原先那条 `n>=3 → L1↔L2/L3 换层` 已删除——
 * 它**够不到**:从 L1 连击三下时第二击就进了 `Locked` 分支,第三击永远轮不到 `n>=3`;
 * 换个起点(先一次性 L2 再连按 Sym)也一样,第二击锁定、第三击被"同层解锁"吃掉。
 * 也就是说这个功能从来没有真正生效过,却让人以为"三击能换层",属反直觉,删掉。
 * 解锁后 250ms 吸收窗只针对"刚解除"这一种情形(层与大小写都有)。
 */
class ImeController(
    private val host: ImeHost,
    var layout: KeyLayout,
    /** 策略注册表:语言 / 输入法 / 输入框表现都从这里取。 */
    val registry: ImeRegistry = ImeRegistry.builtin(),
    /**
     * 触感反馈。**状态机本身不依赖它** —— 锁定/提交这些判定照跑,震动只是附带反馈,
     * 所以测试里传 null 即可,不需要造一个假实现(2026-09-30)。
     */
    private val haptics: Haptics? = null,
) {
    val state = ImeState()

    /**
     * 修饰键手势(敲击计数 / 敲击窗口 / 按住时长 / 解锁吸收窗)。
     * **所有"时间"都在这一个纯 Kotlin 对象里**(原先散成七八个字段 + 状态上的 `tapAt`),
     * 它只做判定、不管显示(状态行是状态的纯函数,见 `StatusRow`)。
     * 构造参数留给测试注入假时钟;生产用系统时钟。
     */
    val gestures = ModifierGestures()

    /** 长按阈值(转发给 [ModifierGestures],别处不再各留一份常量)。 */
    private val holdMs: Long get() = gestures.holdMs

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

    /**
     * 空闲态长按字母键 = 上屏该键的 L2 符号(2026-10-15 用户定;设置开关,默认开)。
     * 关掉时长按回到系统连发(连续上屏,旧行为)。
     * **只支持字母键**(2026-10-16 用户定):A-Z 之外的键(数字/标点/空格/功能键)一律不长按。
     * **不影响拼音输入中的长按分隔符**——那条不可关闭(见 [tryArmLongPress])。
     */
    var longPressSymbolEnabled = true

    private var symDown = false
    private var symProducedKey = false
    private var ctrlDownAlone = false
    private var ctrlUsedCombo = false

    // ------------------------------------------------------------------ 长按字母键(2026-10-15 用户定)

    /**
     * 一次「长按待判定」的按压。四个迁移全部经本对象,别处不直接改字段:
     *
     * - [arm] —— DOWN:登记(输出推迟 + 吞连发的起点)
     * - [confirm] —— 300ms 定时器到点:长按成立,**层状态这一刻才跳 L2**
     *   (DOWN 不跳:短按会闪 alt 芯片,那是预览;用户 2026-10-15 报障修正)
     * - [cancel] —— 按住期间又按了别的键 / 复位:这次按压作废
     * - [clear] —— UP 收尾,归还字段
     *
     * 与修饰键的 Hold([holdMod]/`LayerState.Hold`)是**两件事**:那个管层显示,
     * 这个管输入时机;两者只在 confirm 到 UP 之间共存(Hold 由 confirm 装上)。
     */
    private class LongPress {
        /** 待判定的键码;0 = 没有待判定的按压。 */
        var code = 0
            private set

        /** 按下时的 Caps 态(短按补输出要用)。 */
        var caps = false
            private set

        /** 落点:true = 组分节符(不可关闭);false = 上屏 L2 符号(受开关管)。 */
        var separator = false
            private set

        /** 空闲落点要上屏的 L2 符号文本。 */
        var symbol = ""
            private set

        /** 300ms 已确认(出过屏)。 */
        var fired = false
            private set

        /** 被作废(按住期间按了别的键)。作废后 UP 仍认得出自己(code 保留),但不出字。 */
        var canceled = false
            private set

        /** 有效的待判定:登记了、没确认、没作废。 */
        val pending: Boolean get() = code != 0 && !fired && !canceled

        /** 连发吞断期:待判定中或已确认(已出屏,不能再溅字);作废的不吞。 */
        val swallowing: Boolean get() = code != 0 && !canceled

        fun arm(code: Int, caps: Boolean, separator: Boolean, symbol: String) {
            this.code = code
            this.caps = caps
            this.separator = separator
            this.symbol = symbol
            fired = false
            canceled = false
        }

        fun confirm() {
            fired = true
        }

        /** @return true = 这一下真的作废了一个待判定(调用方要撤定时器)。 */
        fun cancel(): Boolean {
            if (code == 0 || canceled || fired) return false
            canceled = true
            return true
        }

        /** UP 收尾:归还所有字段。 */
        fun clear() {
            code = 0
            caps = false
            separator = false
            symbol = ""
            fired = false
            canceled = false
        }
    }

    private val lp = LongPress()

    // ------------------------------------------------------------------ 长按修饰键

    /** 当前按住的层修饰键(Alt=L2 / Sym=L3),null 表示没有按住任何一个。 */
    private var holdMod: Layer? = null
    private var holdCode = 0

    /** 按住期间按过别的键(长按已被"用掉",松开必须取消)。 */
    private var holdUsed = false

    /** 本次按下前的层状态已收进 [LayerState.Hold.snap],controller 不再另留一份快照。 */

    /** 物理 Shift 按住状态(长按大写:大写来自物理修饰键,松手自然取消)。 */
    private var shiftHolding = false
    private var shiftHeldCode = 0
    private var shiftUsedAsModifier = false

    /**
     * 本次 Shift 按下**之前**的大写状态:长按松手时恢复它(大写随松手消失),
     * 快速敲击则拿它当基准去算 [applyShiftTap]。
     * 有了它,[ImeState.shiftMode] 才能是单一取值——[ShiftMode.HELD] 不会吃掉
     * "Caps 开着时按住 Shift"这个前提。
     */
    private var shiftBeforeHold: ShiftMode = ShiftMode.OFF

    /** 本次 Shift keyDown 是否被消费(组词中消费、空闲透传);keyUp 必须与它成对,否则系统侧 Shift 会粘住。 */
    private var shiftDownConsumed = false

    // ------------------------------------------------------------------ 键事件

    fun onKeyDown(code: Int, ctrl: Boolean, shift: Boolean, caps: Boolean = false, repeat: Boolean = false): Boolean {
        // 按住修饰键期间按下任何别的键 = 这次长按"用掉了"(松开要取消,不再算独立敲击)
        if (!repeat) markModifiersUsed(code)
        if (code == symKeycode) {
            // 数字框限定 0-9:SYM 状态项已隐藏,按下也不进 L3(L3 敲出的全是非数字)
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
                    // 空闲:先进层(长按期间可用),敲击判定**留到 keyUp**——与 Sym/Shift 同一套骨架
                    // 另一个修饰键正按着时忽略(这次按压只是把那个长按"用掉")
                    if (holdMod == null || holdCode == code) {
                        beginHold(Layer.L2, code)
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
        // ── 长按字母键(2026-10-15 用户定)────────────────────────────────────
        //
        // 命中则本次 DOWN **不出屏**,输出推迟到 UP(短按)或 300ms 定时器(长按):
        //   · 空闲 + 开关开 ⇒ 上屏该键的 L2 符号(长按 L → `'`)
        //   · 组词中 + 该键 L2 输出是 `'` ⇒ 插分节符(补上"手动分节符在本机不可达"
        //     的坑:Alt+L 组合会被五键选字吃掉)。**这条不受开关限制。**
        // 武装期间系统连发(repeat)全部吞掉 —— 否则"按住先出一串字母再出符号"
        // 会变成 `llll'`。DOWN 就出屏再删更脏(动 composition/发 DEL 两条路)。
        //
        // 代价(随开关一起给):开开关期间字母提交从 DOWN 挪到 UP,快速连打无感,
        // 慢按有"松手才出字"的感觉;关开关则完全回到旧行为。
        if (!shift && !repeat && tryArmLongPress(code, caps)) return true
        if (repeat && lp.swallowing && code == lp.code) return true
        return handleNormalKey(code, shift, caps)
    }

    fun onKeyUp(code: Int): Boolean {
        // 物理 Shift 松开:快速独立敲击 → 单击大写一次 / 双击 Caps;长按 → 什么也不留(大写随松手消失)
        if (code == KeyEvent.KEYCODE_SHIFT_LEFT || code == KeyEvent.KEYCODE_SHIFT_RIGHT) {
            if (shiftHolding && code == shiftHeldCode) {
                val quickTap = !shiftUsedAsModifier && gestures.up(ModifierGestures.Mod.SHIFT)
                shiftHolding = false
                // 松手先恢复按前的值:长按=大写随松手消失(回到 OFF 或按前的 CAPS/ONCE),
                // 敲击=拿这个基准去算 applyShiftTap
                state.shiftMode = shiftBeforeHold
                if (quickTap) applyShiftTap() else host.render()
            }
            return shiftDownConsumed // 与 keyDown 的消费/透传严格成对,否则系统侧 Shift 会粘住
        }
        // Alt 的 keyDown 总是被消费,keyUp 也成对消费;正按着它则做长按收尾
        if (altKeycodes.contains(code)) {
            if (holdMod == Layer.L2 && code == holdCode) {
                // 快速独立敲击才落定状态;长按/被用掉 = 已在 [endHold] 里恢复快照
                if (endHold()) onAltTap()
            }
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
        // 长按待判定的收尾(三种到达):短按 ⇒ 撤定时器补普通输出(无 Hold,走 L1);
        // 长按已出屏 ⇒ 撤 [onLongPressFire] 跳的 L2 Hold 恢复层;被
        // markModifiersUsed 作废 ⇒ 什么都不出。
        if (lp.code != 0 && code == lp.code) {
            val emit = lp.pending // 只有"还待判定的短按"要补输出
            val caps = lp.caps
            lp.clear()
            host.cancelLongPress()
            // 撤 [onLongPressFire] 装上的 Hold:层可能是 L2 也可能是 L3(设置定的),
            // 所以认 [holdCode] 而不是写死哪一层。先恢复层再出字母,反了字母会变该层符号。
            if (holdMod != null && holdCode == code) endHold()
            if (emit) return handleNormalKey(code, false, caps)
            return true
        }
        return false
    }

    /**
     * 尝试为一次按键按压**武装长按判定**;成功则调用方应该吞掉本次 DOWN 的输出。
     *
     * 资格(全部满足):
     * - 不是 Shift/Ctrl 组合(Shift+键 = 大写 / 组词中选字,不抢);Ctrl 组合在
     *   [onKeyDown] 更早的分支就走了,到不了这里
     * - **是字母键**(2026-10-16 用户定「只支持字母键」):A-Z 之外的键一律不长按
     * - 不是数字类框([FieldProfile.digitsOnly]):那一档 L2 已聚焦锁定,当前输出
     *   就是"该键的符号",长按没有额外语义,照旧连发
     * - L6 面板开着时不武装:敲字母 = 收面板 + 正常输入(面板只在空闲态存在,
     *   见 [refreshQuery]);长按符号是纯空闲态的事,面板开着时不该出符号
     * - 落点二选一:
     *   · **空闲态 + [longPressSymbolEnabled]**(默认开)⇒ 上屏该键的 L2 符号
     *     (中文全角态就是中文标点,L2 表与画面一致);
     *   · **组词态 + 该键 L2 输出是撇号**(ASCII `'` 或全角 `’`)⇒ 插分节符,
     *     **不受开关限制**(用户 2026-10-15 定;本机唯一可达的手动分节路径)
     *
     * L2 输出走"当前语言表优先、出厂表兜底"——与 [handleNormalKey] 的查表同一套,
     * 所以中文全角态长按出的是中文标点,不是 ASCII。
     */
    private fun tryArmLongPress(code: Int, caps: Boolean): Boolean {
        // **只支持字母键**(2026-10-16 用户定):数字 / 标点 / 空格 / 功能键都不长按,
        // 这条做成结构性的第一道闸,不依赖"出厂 L2/L3 表里恰好只有字母"这个巧合。
        if (code !in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) return false
        // 已有层生效(Alt 按住 / 一次性 / 锁定 / 数字类锁 L2)时该键当前输出就是
        // L2 符号,DOWN 即出屏 —— 没有"长按"可判,也不制造第二份 Hold
        if (fieldProfile().digitsOnly) return false
        if (holdMod != null) return false
        if (state.effectiveLayer != Layer.L1) return false
        // 物理 Shift 正按住(HELD):这一按是 Shift+字母,走大写、不抢长按。
        // 真机上这时的 event.isShiftPressed 已把 shift 参数置真;状态再兜一层,
        // 是因为 shiftMode=HELD 也能表达同一件事(测试替身不模拟事件参数)。
        if (state.shiftMode == ShiftMode.HELD) return false
        val l2 = language().layerMap(Layer.L2, fullWidthSymbolsInCn)?.get(code)
            ?: layout.lookup(Layer.L2, code)
            ?: return false
        val text = (l2 as? KeyOut.Text)?.text ?: return false
        // 分节符判定**两形都认**:半角态 L2(L)= ASCII `'`(分隔符常量本身),
        // 中文全角态 L2(L)= `’`(U+2019,标点全角化) —— 默认就在全角态,
        // 只认 ASCII 会让这条唯一可达的分节路径默认失灵。插入缓冲的恒为
        // ASCII(引擎的 delimiter,见 ChineseLanguage.SEPARATOR)。
        val separatorMode = state.hasComposition &&
            (text == ChineseLanguage.SEPARATOR || text == "’")
        // 空闲出符号这一支:**L6 面板开着时不武装**(2026-10-16 L6 分支的定案,合流时
        // 落到这支上)——敲字母 = 收面板 + 正常输入,面板只在空闲态存在,符号快捷是
        // 纯空闲态的事。分节符那一支不受影响(那是组词态的事,面板不在组词态存在)。
        val symbolMode = !state.hasComposition && !l6Open && longPressSymbolEnabled
        if (!separatorMode && !symbolMode) return false
        lp.arm(code, caps, separatorMode, text)
        // **不在 DOWN 跳 L2**(2026-10-15 用户报障修正):武装时就 beginHold 会让
        // 每次敲字母都闪一下 alt 状态项 —— 那是预览,而用户决策 3 定的就是
        // "不要预览"。状态跳变推迟到 [onLongPressFire](长按确认那一刻)与符号
        // 同时出现,松手恢复 —— 这才是"短暂的把状态跳到 L2"。
        host.scheduleLongPress(code)
        return true
    }

    /**
     * 长按到点([ImeHost.scheduleLongPress] 的 300ms 回调):键仍未松开 ⇒ 出屏。
     *
     * 组词中插分节符(`raw += '` 后按新界线重新查询 —— 分节位约束匹配的语义),
     * 空闲直接提交符号;两次都震一下(与飞字过阈值同一套"这次定了"的反馈)。
     */
    fun onLongPressFire() {
        if (!lp.pending) return
        lp.confirm()
        // 状态与显示一致(用户 2026-10-15 定):长按确认这一刻才把层状态跳到 L2,
        // 与符号同帧出现;松手(endHold)恢复。**DOWN 不跳** —— 否则短按也会
        // 闪一下 alt 状态项,那是预览而不是一致性。
        beginHold(Layer.L2, lp.code)
        if (lp.separator) {
            state.appendRaw(ChineseLanguage.SEPARATOR)
            refreshQuery(from = "长按分节")
        } else {
            host.commitText(lp.symbol)
            host.render()
        }
        haptics?.confirm()
    }

    // ------------------------------------------------------------------ 长按实现

    /**
     * 按下层修饰键:把 [Layer] 作为 [LayerState.Hold] 挂上(按下前的层状态收进它的 snap),
     * 并让状态行立刻把层状态显示出来。
     * 敲击判定不在此处——**统一在 keyUp 做**,Alt/Sym/Shift 同一套骨架(见类注释的迁移总表)。
     *
     * 按住中的呈现与敲击落定的结果**长得一模一样**(同一层,锁定样式取自 snap),所以不需要
     * "先藏着、等敲击窗口闭合再补画"那一套——真机报障「在 alt 状态下长按 alt 会有 alt 消失
     * 0.5s 左右」就是那套规则的后遗症(2026-09-30 连同 `ModifierGestures.settled` 一起删了)。
     */
    private fun beginHold(mod: Layer, code: Int) {
        gestures.down(mod.keyMod())
        if (holdMod != null) return
        holdMod = mod
        holdCode = code
        holdUsed = false
        // 按下的层状态收进 [LayerState.Hold.snap]:长按松手恢复它(松开即取消);敲击则在
        // [endHold] 恢复之后另落定一个新状态。顶层从此只有一种状态。
        state.layerState = LayerState.Hold(mod, state.layerState)
        host.render()
    }

    /**
     * 松开层修饰键。返回 true = 这是一次快速独立敲击(按住不足 [holdMs] 且按住期间没按过别的键),
     * 调用方据此走敲击语义([layerKeyTap]);false = 长按结束。
     *
     * **无论哪种,Hold 都先撤掉、恢复按下前的层状态**([LayerState.Hold.snap]):敲击是"恢复后再落定
     * 一个新状态",长按是"恢复即取消"。因为**敲击判定统一在 keyUp 做**,长按永远不会留下一次敲击,
     * 所以这里不需要"记住这次敲了几个来补救"的字段(2026-09-30 整条梳理时删掉了 `holdTapN`
     * 与 [LayerState.Hold] 的 pending——它们只是因为 Alt 曾在 keyDown 计数才存在)。
     */
    private fun endHold(): Boolean {
        val mod = holdMod
        val quickTap = mod != null && gestures.up(mod.keyMod()) && !holdUsed
        val h = state.layerState as? LayerState.Hold
        if (h != null) state.layerState = h.snap
        holdMod = null
        holdUsed = false
        host.render()
        return quickTap
    }

    /** [Layer.L2]/[Layer.L3] → 对应的修饰键(唯一一份映射在 [ModifierGestures.Mod.of])。 */
    private fun Layer.keyMod(): ModifierGestures.Mod = ModifierGestures.Mod.of(this)

    /** 按住修饰键期间按下别的键:标记这次长按已被使用(松开要取消)。 */
    private fun markModifiersUsed(code: Int) {
        if (holdMod != null && code != holdCode) {
            holdUsed = true
            // 另一颗键也按了(rollover):本次长按意图作废 —— 定时器取消、不收尾出字。
            // 仍按住的那颗键松手只恢复层(见 onKeyUp 的长按收尾分支)。
            if (lp.cancel()) host.cancelLongPress()
        }
        if (symDown && code != symKeycode) symProducedKey = true
        if (shiftHolding && code != shiftHeldCode) shiftUsedAsModifier = true
    }

    /**
     * 空闲态按下物理 Shift:武装敲击判定(大小写随 [ShiftMode.HELD] 立刻亮成 `Ab`)。
     * 松手在 [onKeyUp] 的 Shift 分支:先恢复按前的值,再判敲击还是长按。
     */
    private fun beginShiftHold(code: Int) {
        gestures.down(ModifierGestures.Mod.SHIFT)
        shiftHolding = true
        shiftHeldCode = code
        shiftUsedAsModifier = false
        shiftBeforeHold = state.shiftMode // 记住按前的值,松手恢复(见字段注释)
        state.shiftMode = ShiftMode.HELD
        host.render()
    }

    /**
     * Shift 敲击。两条规则(软键盘 ⇧ 与物理 Shift 共用):
     * - **独立单击**(双击窗口外)= 开关自合:OFF → 大写一次;大写一次 / Caps → 关掉。
     *   已开着的任意大写状态再单击必然回 OFF —— Shift 永远是自己的退出键,不存在按不掉的死状态。
     * - **双击**(窗口内)= 跳到 Caps 锁定;已是 Caps 再双击解除。
     *   窗口内第二击被双击征用,所以"ONCE 时手快再按"得到的是 Caps,之后再按一次必然回 OFF。
     * 大写一次仍会在输入一个字母后自动取消。
     * 拼音态直接忽略——拼音不需要大小写锁定(大小写状态也只在英文态显示)。
     * 调用前 [ImeState.shiftMode] 已恢复成按前的值(见 [onKeyUp] 的 Shift 分支),所以
     * `HELD` 不会参与这里的判定。
     */
    private fun applyShiftTap() {
        if (!language().shiftTapsEnabled) return
        val key = ModifierGestures.Mod.SHIFT
        // 解锁后的短窗内吸收同键连击(与层状态同一条规则,2026-09-30 补齐)
        if (gestures.consumeSuppressed(key)) {
            gestures.resetTaps(key)
            return
        }
        val n = gestures.tap(key)
        val cur = state.shiftMode
        val next = when {
            n == 1 -> if (cur == ShiftMode.OFF) ShiftMode.ONCE else ShiftMode.OFF
            else -> if (cur == ShiftMode.CAPS) ShiftMode.OFF else ShiftMode.CAPS
        }
        state.shiftMode = next
        // 只在**进入 Caps 锁定**与**从 Caps 解锁**时震:这两处是"大写粘住了 / 不粘了"的确认点。
        // ONCE↔OFF 的单击切换不震 —— 那是打字过程中的常态,震一下反而像误触(2026-09-30)。
        if ((cur == ShiftMode.CAPS) != (next == ShiftMode.CAPS)) haptics?.lock()
        // **关掉大写就进吸收窗**:双击解除 Caps 后手抖再按一下,不会立刻又锁上
        // (原先层状态有这窗、大写没有,双击解除后第三击就重新锁上了)
        if (state.shiftMode == ShiftMode.OFF) gestures.suppress(key)
        host.render()
    }

    // ------------------------------------------------------------------ 层与模式

    /** 软键盘 Alt 键回调。 */
    fun onAltTap() {
        layerKeyTap(Layer.L2)
        host.render()
    }

    /** 软键盘 Sym 键回调。 */
    fun onSymTap() {
        layerKeyTap(Layer.L3)
        host.render()
    }

    /** 软键盘 Shift 键回调:单击大写一次,双击 Caps 锁定。 */
    fun onShiftTap() {
        applyShiftTap()
    }

    /**
     * 切到下一个语言(Shift+空格 / 状态行的语言项)。
     * 2026-09-29 用户定案:状态项**不可触摸**,切换只走物理按键 —— `Shift+Space`。
     */
    fun cycleLanguage() = setLanguage(registry.nextLanguage(state.languageId))

    /**
     * 切到指定语言(密码框强制英文等场景)。**清掉全部状态**再切入,收尾再交给语言策略
     * ([dev.hwkbd.ime.strategy.ChineseLanguage.onEnter])。
     * **密码框内(secure)禁止切到安全语言以外**——切语言的两个入口(状态行的语言项、
     * Shift+空格)都收口在这里,一处守卫全覆盖。
     */
    fun setLanguage(id: String) {
        if (state.secure && id != registry.secureLanguageId) return
        val lang = registry.language(id)
        if (state.languageId == lang.id) return
        flushComposition()
        state.languageId = lang.id
        resetTransientState()
        state.closePanels() // 切语言是一次新的空闲态:面板里的固定符号键跟着语言走,不留
        lang.onEnter(state)
        host.render()
    }

    /**
     * 切语言时**清空一切可延续的状态**(2026-10-02 用户「切合拼音和英文时所有的状态都清除」)。
     *
     * 此前只清组词(经 [flushComposition] → [clearComposition]:raw / candidates / page /
     * highlight)与大小写(经 [dev.hwkbd.ime.strategy.ChineseLanguage.onEnter])。
     * 漏掉的是这两样,它们都会**跨语言残留**,读起来像"没切干净":
     * - **层状态**:锁着的 L2(符号)、一次性层、长按按住 —— 切到英文还在符号层,
     *   敲字母敲不出来;
     * - **[ModifierGestures] 的计时器**:连击计数、按住时刻、解锁吸收窗 —— 双击锁了 L3
     *   再切语言,紧接着那一下同键会被算成新连击的第一击,把锁重新武装上。
     *
     * **数字类字段的 L2 锁是例外**:那把锁是**字段给的**(见 [setFieldKind],聚焦即锁,
     * 免按 Alt),不是用户选的,清掉会让数字框打不出数字(层回到 L1,而字母又过不了
     * 数字白名单)。实际上数字框里也切不了语言(语言项与全/半开关都藏了,
     * Shift+空格那条路 `!digitsOnly` 挡着),这里留这道分支纯为防御 ——
     * 状态机不该依赖"入口恰好进不来"这种外部条件。
     */
    private fun resetTransientState() {
        state.layerState =
            if (fieldProfile().digitsOnly) LayerState.Locked(Layer.L2) else LayerState.L1
        state.shiftMode = ShiftMode.OFF
        gestures.resetAll()
    }

    private fun layerKeyTap(mod: Layer) {
        // 数字类的 L2 锁是**字段给的**(聚焦即锁,免按 Alt):解不掉,否则 L1 全被吞没法打字。
        // 触摸切换已下线,这条守卫现在只挡物理 Alt 键。
        if (mod == Layer.L2 && state.lockedLayer == Layer.L2 && fieldProfile().digitsOnly) {
            state.layerState = LayerState.Locked(Layer.L2)
            host.render()
            return
        }
        val key = mod.keyMod()
        // 解锁/取消后的短窗内吸收同键连击(双击解除应保持解除,不重新武装)
        if (gestures.consumeSuppressed(key)) {
            gestures.resetTaps(key)
            return
        }
        val n = gestures.tap(key)
        // 敲击判定统一在 keyUp 做([endHold] 已把 Hold 撤掉),所以这里直接读写顶层状态。
        // 判定的**主轴是敲击次数**:锁定只由双击产生;单击是**借用**一个层,用完恢复底下那份。
        val cur = state.layerState
        val next = when {
            // 双击 = 锁定该层(锁在别的层时 = 把锁搬过去)。**唯一**能产生锁的入口。
            // 必须排在"取消"那些分支**前面**:第二击的可见层正是这个键,否则会被当成"取消借用"。
            // 锁成之后**清掉这一键的连击计数**:下一击重新算单击,否则窗口内的第三击仍是 n>=2,
            // "锁着再敲一下 = 解锁"就只在窗口外成立(2026-09-30 测试钉住的)
            n >= 2 -> {
                gestures.resetTaps(key)
                LayerState.Locked(mod)
            }
            // 锁着这个层再敲一下 = 解锁
            cur is LayerState.Locked && cur.layer == mod -> {
                gestures.resetTaps(key)
                gestures.suppress(key)
                LayerState.L1
            }
            // 临时层(借用/按住中)上敲的正是**当前那层**或**底下锁着的那层** = 收掉临时层,
            // 恢复底下那份:借 Sym 时敲 Sym = 取消借用;借 Sym 时敲 Alt = 回到锁着的 Alt
            cur !is LayerState.Locked && (cur.layer == mod || cur.base.layer == mod) -> {
                gestures.resetTaps(key)
                gestures.suppress(key)
                cur.base
            }
            // 单击 = 借用一个键的这个层,用完恢复底下那份:有锁回锁,没锁回初始(L1)
            else -> LayerState.borrow(mod, cur)
        }
        state.layerState = next
        // 只在"锁定态有/无"发生变化时震:双击锁上、再敲解锁这两个确认点。
        // 借一层(单击)不震 —— 那是打字过程中的常态(2026-09-30)。
        if ((cur is LayerState.Locked) != (next is LayerState.Locked)) {
            haptics?.lock()
            // 锁上/解锁是**看得见的状态变化**,但状态行不在 uiautomator 的 dump 里
            // (输入法是另一个窗口),端到端脚本没法断言「到底锁上没有」。
            // 这行日志就是给那种断言用的(见 tools/aitest/shots.mjs 的 07-alt-layer)。
            android.util.Log.i("ImeController", "layer lock: ${next is LayerState.Locked}")
        }
    }

    // ------------------------------------------------------------------ 候选与组词

    /**
     * 选字时的**表现回调**:上屏之前叫一次,告诉视图层「哪个显示位的词要飞走」。
     *
     * 2026-10-02 用户「按键选词也需要飞字动画」。此前**只有上滑手势那一条路**有飞字
     * (在 service 的 `commitFly` 里),按键选字(Shift / Sym / Alt / Ctrl / 方向键 /
     * 空格 / 候选栏点选)全是词直接冒出来 —— 同一个动作两套表现,像是两个输入法。
     * 挂在这里而不是去 9 个调用点各补一遍:调用点有 9 处,漏一处就又出现一条没动画的路。
     *
     * **为什么不用 [ImeHost]**:那个接口刻意不含任何视图概念,它是状态机与 Android 层
     * 之间的唯一通道,单测靠它跑 JVM。往里加「飞一下」就把它变成 Android 依赖。
     * 这里用可空回调:状态机只负责「该通知」,放什么动画由 service 决定。
     *
     * @param displayIndex 显示位(左→右 = 4 2 1 3 5)
     * @param word 即将上屏的词 —— 与真正提交的是同一个,视图照它起动画不会飞错
     */
    var onCandidateSelected: ((displayIndex: Int, word: String) -> Unit)? = null

    /**
     * 选显示位 i 的候选上屏。显示位与物理五键对应(左→右 = 4 2 1 3 5):
     * 0=Shift(候选4) 1=Sym(候选2) 2=空格(候选1,首选) 3=Ctrl(候选3) 4=Alt(候选5)。
     * 候选栏点选/上滑 5 区/五键选字/高亮确认都用显示位;Ctrl+QWERT 不选字(原样转发)。
     */
    fun selectCandidate(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = state.displayOrder(logical.size).getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return

        // **先问引擎:这个候选吃掉了多少输入字符**(2026-10-04 用户「拼音的消费也要
        // 根据实际选词消费,不能一次清空」;2026-10-05 修「给引擎的是页内下标」)。
        //
        // 旧路径无条件 `clearComposition()`,那等于**假设一个候选吃掉全部拼音**。
        // 单音节下成立,多段输入下不成立:`ni'hao` 选「你」之后 `hao` 被一起清掉。
        //
        // ## 下标必须是**全局**的,不是页内的
        //
        // [li] 是**当前页内**的下标(0..4),而 librime 的 `Select(index)` 索引的是
        // **整份菜单**(0..44)。两者只在第 0 页相等 —— 翻到第 2 页按 Shift,
        // 界面上是 `candidates[13]`,而引擎选的是 `candidates[3]`。
        //
        // 症状不是「上屏错字」(上屏用的是我们自己的 [c],永远是对的),而是
        // **缓冲被截到别的地方**:引擎报的是另一个词吃掉的长度,剩下的拼音就此错位。
        // 这也解释了为什么第 0 页一切正常、翻页后开始出错。
        val globalIndex = state.page * state.pageSize + li
        val sel = inputMethod().selectAndRemain(globalIndex, c.word)

        // 埋点:「选的候选与上屏不一致 / 选完缓冲不对」时,这一行就是判据
        // (2026-10-05 用户报「出现选的候选词和实际上屏不同」时加的)。
        //
        // 上屏与飞字动画用的是**同一个** `c.word`,所以两者不可能互相不同。
        // 真正的故障在**更早**:引擎选中的是不是同一个候选(`globalIndex` 那一环),
        // 以及它吃掉的长度对不对(下一行的 `consumed`)。
        //
        // `global` 与 `li` 并排打出来:两者相等就是**在第 0 页**。
        // 真实故障下会看到 `li=3 global=3`(第 0 页,正常)与 `li=0 global=10`
        // (翻过页)并存 —— 只看 `li` 永远看不出错。
        android.util.Log.i(
            "ImeService",
            "select: zone=$displayIndex li=$li global=$globalIndex word=\"${c.word}\" " +
                "page=${state.page}/${state.totalPages} raw=\"${state.raw}\" " +
                "order=${state.displayOrder(logical.size).joinToString()} " +
                "pageWords=${logical.joinToString("|") { it.word }} " +
                "consumed=${sel?.consumed}"
        )

        val consumed = sel?.consumed

        // ── 引擎的菜单**必须在 commitText 之前读** ─────────────────────────────────
        //
        // 上屏会改 App 的组词区(`setComposingText` + `finishComposingText`),
        // 系统随即回调 `onUpdateSelection`,而 `ImeService` 的对账一旦认为组词区
        // 被外力破坏就 `abandonComposition()` —— **整个引擎被清掉**。
        // ⇒ 引擎的读只能发生在上屏之前。
        //
        // 这一条是**探针实测**逼出来的,不是推理(`tools/rimeprobe/run.sh nihao`):
        // 刚 `Select` 完引擎给出 consumed=2、剩下一段 `[2,5)` 与它的 45 个候选。
        // 两次旧修复(2026-10-05 `refreshQuery`、2026-10-06 `currentCandidates`)
        // **都栽在同一个位置** —— 不是读法有问题,是都在上屏之后读。
        // 完整经过,连同那两次的错误推导,见 brain 页 `rime-candidate-consumption`。
        val remainder = readRemainder(consumed)

        // **先通知再上屏**:视图要趁候选行还在屏上把词画走,顺序反了就没什么可飞的了。
        // 放在两个 `?: return` 之后 —— 选不中就不该有动画。
        onCandidateSelected?.invoke(displayIndex, c.word)
        host.commitText(c.word)
        inputMethod().onCommitted(c.word)

        val rem = remainder
        if (rem == null) {
            // `consumed == 0`(整串吃干净)、`consumed == null`(引擎不参与/没选成)
            // 都归到这里 —— 动作相同,但含义不同,埋点那一行分得开。
            clearComposition()
        } else {
            state.consumeRaw(rem.raw, consumed!!) // 引擎已吃掉的绝对量 = 新 raw 的起点
            state.page = 0
            state.highlight = -1
            state.candidates = rem.candidates
            // 显示拼写这时才刷:raw 与 page 都已是剩余部分的新值(2026-10-14 评审,
            // 从 readRemainder 里挪出来 —— 在那里刷用的是旧 raw + 旧 page)。
            // 读的是引擎当前菜单(剩余部分),index=0 = 剩余部分的第一页中央槽。
            // 时序上它在 commitText 之后,若 onUpdateSelection 已把引擎清掉,
            // topSpelling 返回空 ⇒ 回退显示原始拼写,只是没有分隔符,不是错误显示。
            refreshSpelling()
            querySeq++
            android.util.Log.i(
                "ImeService",
                "query#$querySeq from=选词后剩余 raw=\"${rem.raw}\" n=${state.candidates.size}"
            )
        }
        host.render()
    }

    /**
     * 选完候选之后,引擎那边「还剩什么」。
     *
     * ## **必须在 [ImeHost.commitText] 之前调用**
     *
     * 上屏会改 App 的组词区,系统随即回调 `onUpdateSelection`,`ImeService`
     * 的对账一旦不通过就 `abandonComposition()` —— 引擎整个消失,菜单读出来是空的。
     * 调用点就在上面三行,理由与两次旧修复的经过写在 [selectCandidate] 里。
     *
     * ## 为什么 `raw` 与 `candidates` 打包在一起
     *
     * 原来这两样是**各算一遍**的:`rest` 在一处用 `rawAfterConsuming` 算,
     * `remainCandidates` 在另一处再算一次同样的 `rawAfterConsuming`。
     * 两份独立推导同一个值,早晚会漂 —— 而漂了以后症状是
     * 「缓冲里是 hao、候选栏却是 hao 别的段」,极难查。
     * 打包成一份就只有一个真相来源。
     *
     * @param consumed 引擎给的消费长度(**绝对坐标** = 相对引擎当前输入串开头的下标,含 `'`);
     *   `null` 或 `<= 0` ⇒ `null`。与当前 `state.raw` 之间差一个 [ImeState.rawBase]。
     */
    private fun readRemainder(consumed: Int?): Remainder? {
        if (consumed == null || consumed <= 0) return null
        // 被吃掉的永远是**开头**那一段(librime 的候选从当前段起点开始吃),所以留的是**尾巴**。
        // **但 consumed 是绝对坐标**(相对引擎当前输入串起点),不是相对当前 raw —— 连续消费时
        // raw 已是尾巴,必须先减 [ImeState.rawBase](= 引擎此前吃掉的绝对量)换成相对量,否则
        // 第二次消费起会多截、把剩余拼音丢光(2026-10-16 真 bug;探针 chain 实测见
        // `tools/rimeprobe/probe.cc`)。切点与撇口径见 [dev.hwkbd.ime.rime.rawAfterConsuming]。
        val rel = consumed - state.rawBase
        val rest = dev.hwkbd.ime.rime.rawAfterConsuming(state.raw, rel)
        // 不用 [refreshQuery] 的理由**不是**「重建算不出 hao 的候选」——
        // 那条判断是错的:重建是 `clear_composition()` + 逐字重喂,纯函数,
        // `hao` 的候选它算得出来。错的是**读的时候引擎可能已经没了**,
        // 重建同样得先问引擎,所以两条路一起空。
        val candidates = inputMethod().currentCandidates(rest, CANDIDATE_LIMIT)
        // **不在这里刷 spelling**(2026-10-14 评审挪走):此刻 `state.raw` 还是选词前
        // 的整串、`state.page` 还是选词前的页 —— 拿旧 raw 配新菜单的候选拼出来的
        // 显示串是错的(如 "nihao" 选「你」会得到 "haoo")。改由调用方在把
        // `raw/page` 换成剩余值**之后**刷(见 selectCandidate 的 remainder 分支)。
        // **这里不做「没候选就当全吃掉」的夹取** —— 试过,撤回(2026-10-06)。
        //
        // 起因是我以为「librime 切在词中间会留下一字母碎片,而碎片没有候选」。
        // **那两条都验错了:**
        //   · `zhongguo` 选「中古」确实留下 `o`,但 `o` **有 7 个候选**
        //     (探针 `run.sh o`:menu n=7)—— 不是死碎片;
        //   · 真正没有候选的是 `i`(与 `v` 之类),而那是**合法的用户输入**。
        //
        // 加上夹取会把那种输入**静默丢掉** —— 用户敲的字符无故消失,
        // 比「候选栏空着」糟糕得多。空候选栏是诚实的:它说的是
        // 「这几个字母确实没有对应词」,而丢掉输入是骗人。
        //
        // E2E 场景 `consume-partial` 的断言 ③(剩余必须有候选)留着 ——
        // 它是那个场景的验收条件(`nihao` 选槽 0 → `hao`,确实有候选),
        // 不是一条产品规则。
        return Remainder(rest, candidates)
    }

    /** [readRemainder] 的结果:剩下的拼音 + 它的候选。 */
    private data class Remainder(val raw: String, val candidates: List<Candidate>)

    /** 长按候选(显示位):删除该词的用户频次/词条(可可同款),刷新当前候选。 */
    fun removeUserWord(displayIndex: Int) {
        val logical = state.currentPageLogical()
        val li = state.displayOrder(logical.size).getOrNull(displayIndex) ?: return
        val c = logical.getOrNull(li) ?: return
        inputMethod().removeUserWord(c.word)
        refreshQuery(from = "删词条")
    }

    /**
     * 剪贴板条目**直接上屏**(2026-10-16 剪贴板 tab)。
     *
     * 与 [commitL4Key] 的文本键不同:那是拼音补差键(可能进组词缓冲),剪贴板是「粘贴」——
     * 纯字母内容也该原样上屏,不能当拼音(L6 定案「面板上点键 = 直接上屏」的前提是
     * 符号盘里没有 a-z,剪贴板不满足)。上屏后**面板留原地**([ImeState.panelMode] 不变)。
     */
    fun commitClipboardText(text: String) {
        if (text.isEmpty()) return
        host.commitText(text)
        host.render()
    }

    /**
     * 虚拟面板按键输出(L4 中区、L5 尾巴行、L6 虚拟键盘**共用一条**通路)。文本键**原样上屏**
     * (配置即所见);语言策略判定为组词字符的键跟着组词(拼音分隔符 `'`:组词中追加分节、
     * 不打断组词),功能键转发给目标应用。
     *
     * **三块面板不各写一份**的理由见 [L6Ui] 类注释 —— 「这个键干什么」是 [KeyOut] 的事,
     * 不是「它长在哪块面板」的事;多一条分支就多一处语义会漂移的地方。
     */
    fun commitL4Key(out: KeyOut) {
        when (out) {
            is KeyOut.Text -> {
                // 策略先改写按键文本,再决定进缓冲还是上屏
                // (拼音策略**不改写字母** —— 理由见 Language.mapText)
                val text = language().mapText(out.text, state)
                if (language().composes(text, state)) {
                    state.appendRaw(text)
                    refreshQuery(from = "L4 文本键")
                } else {
                    flushComposition()
                    host.commitText(text)
                }
            }
            is KeyOut.Special -> {
                // 面板开着时,L4/L5 行上的 Esc 也收面板(与物理 Esc 一致)
                if (out.name == "esc" && l6Open) {
                    state.closePanels()
                    host.render()
                    return
                }
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
     * ([Language.layerMap]),L4 那把去重筛("一次物理键就能敲出就不上状态行")必须用同一份表,
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
            nowDigit -> state.layerState = LayerState.Locked(Layer.L2)
            wasDigit && state.lockedLayer == Layer.L2 -> state.layerState = LayerState.L1
        }
        host.render()
    }

    /** 词的用户使用频次(测试/调试用)。 */
    fun userCountOf(word: String): Int = inputMethod().userCount(word)

    /**
     * 翻页一步。
     *
     * ## 为什么打埋点(2026-10-05)
     *
     * 此函数此前**完全静默**,而它是「翻页到底发不发生」的唯一落地点 ——
     * 于是 logcat 里「翻页成功」与「翻页手势压根没进来」**同形**(都没有痕迹)。
     *
     * 代价具体落在 2026-10-05 那个查不下去的现场:E2E `select-global-index`
     * 打出 `page begin 0→1` 与 `end: 提交 commit=true`,之后页码却是 0。
     * 能确定的是「view 说它提交了」,不能确定的是「controller 有没有真的翻」——
     * 中间那一步没有记录,于是两种解释都能自圆其说,谁也排除不掉。
     *
     * `before→after` 两个数都打,而不是只打 `after`:只打后者的话
     * 「被夹回原页」与「压根没调用」长得一样(夹取后页码没变)。
     * `delta` 也打 —— 重复调用(比如 +1 打两次)只有它能看出来。
     *
     * **夹到边界时 `after == before` 是正常的**(到头不循环,见 `pageStep` 的语义),
     * 所以 `after != before` 才说明真的翻了。
     */
    fun pageStep(delta: Int) {
        val before = state.page
        if (state.totalPages == 0) {
            android.util.Log.i("ImeService", "pageStep: delta=$delta 忽略(总页数=0)")
            return
        }
        state.page = (state.page + delta).coerceIn(0, state.totalPages - 1)
        state.highlight = -1
        // 翻页后中央槽换了词:拼音显示的切分要跟着新中央词走(2026-10-14 评审补,
        // 需求原文「以**当前**候选词栏中央……为准」)。放在 render 前,
        // 这一帧的画面与显示串一起出去,不会出现「词换了、拼音慢一帧」。
        refreshSpelling()
        android.util.Log.i(
            "ImeService",
            "pageStep: delta=$delta page=$before→${state.page}/${state.totalPages}",
        )
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

    /**
     * 候选列表的写入计数(埋点,见 [refreshQuery])。
     *
     * 「候选行自己交替闪烁」意味着**两个来源在轮流写候选列表** —— 一次按键只应
     * 写一次。数出来就知道是不是。
     */
    private var querySeq = 0

    /** 词库异步载入完成后主动刷新当前查询。 */
    fun refreshQuery(from: String = "?") {
        // **组词一开始就把 L6 收掉**:面板只在空闲态存在(2026-10-15 定案),
        // 而「空闲 → 有组词」这一步的所有路径(字符键 / L4 文本键 / L6 文本键 / 退格)
        // 都汇到这里 —— 在这一个口子上关一次就够,不必每条路径各写一遍。
        //
        // 收面板只改字段不 render(本函数末尾自己会 render,嵌套渲染会把同一帧画两遍)。
        if (state.hasComposition) state.closePanels()
        // **引擎没就绪时不去问它**(2026-10-04 用户报候选行自己交替闪烁)。
        //
        // 没就绪时 `query()` 只能给空表,而空表 = 「这个拼音没有候选」。
        // 于是每按一次键候选行就被清空一次,等部署完成的回调到了再填满 ——
        // 用户看到的就是「空 ↔ 有词」交替。埋点实测:
        //     query#1 from=onKeyDown           raw="n" n=0
        //     query#2 from=onCreate$1$1.invoke raw="n" n=45
        //
        // 这里**只跳过查询,渲染照旧**:候选不去动它(留着上一次的内容 = 不闪),
        // 但组词缓冲必须画出来 —— 否则这几秒里用户打的拼音**一个字符都看不见**,
        // 那是比闪烁更糟的退化。等部署完成的回调再问一次,那时一次就填上:
        // **一次跳变代替「清空↔填满」的抖动**。
        //
        // 窗口只存在于首次启动那几秒;之后就绪了,走正常路径。
        if (!inputMethod().isReady) {
            android.util.Log.i("ImeService", "query: 引擎未就绪,不查询(raw=\"${state.raw}\")")
            host.render()
            return
        }
        if (state.raw.isNotEmpty()) {
            state.candidates = inputMethod().query(state.raw, CANDIDATE_LIMIT)
            state.rebuildRaw(state.raw) // 引擎以 raw 重建,坐标从 raw 头算起
            state.page = 0
            state.highlight = -1
            // 显示拼写跟着新菜单走(新查询恒回第 0 页,故中央槽 index=0)。
            // 必须在 page 复位**之后**刷:索引 = page * pageSize。
            refreshSpelling()
        } else {
            state.candidates = emptyList()
            state.spelling = ""
            state.page = 0
            state.highlight = -1
        }
        logCandidates("refresh")
        // ---- 谁在写候选列表(埋点)------------------------------------
        //
        // 用户报「候选行自己交替闪烁」——「交替」意味着**两个来源在轮流写**。
        // 一次按键只应写一次;这里把序号、调用者、raw、候选数一起打出来:
        // 序号不连续或调用者成对出现,就是重复刷新。
        //
        // `from` 由**调用方显式传**,不是这里 `Throwable().stackTrace` 走栈 ——
        // 走栈能自动得到调用者,但每次按键都要爬一遍调用栈,而这是输入法的热路径
        // (每个字母都走一遍)。显式传是零成本,而且中文标签比 `类名.方法名` 好读。
        querySeq++
        android.util.Log.i(
            "ImeService",
            "query#$querySeq from=$from raw=\"${state.raw}\" n=${state.candidates.size}"
        )
        // ----------------------------------------------------------------
        host.render()
    }

    /**
     * 刷新 [ImeState.spelling](拼音的显示串:带自动分隔符)。
     *
     * 2026-10-12 用户「拼音显示需要自动加入分隔符,以当前候选词栏中央的最高优先级
     * 候选词为准」。与候选查询同源同时机:紧跟 [InputMethod.query] /
     * [InputMethod.currentCandidates] / 翻页 —— 它读的是引擎**此刻**的菜单,晚一步
     * 就是两份不同的状态(见 brain `unverified-as-fact`:判据要和权威量对齐)。
     *
     * 2026-10-14 评审补:**跟着当前页的中央槽走** —— 索引 = `page * pageSize`。
     * 固定传 0 时,翻页后显示的切分属于第 0 页那个词,与用户实际看到的中央词脱节。
     */
    private fun refreshSpelling() {
        val raw = state.raw
        if (raw.isEmpty()) {
            state.spelling = ""
            return
        }
        // 中央槽 = 当前页的逻辑第 0 项 = 全局下标 page * pageSize(displayOrder 钉死:
        // 显示槽 2 = 逻辑 0,见 ImeState.displayOrder 与 select 埋点的 zone=2/li=0)。
        val centerIndex = state.page * state.pageSize
        val sp = inputMethod().topSpelling(centerIndex)
        state.spelling = if (sp == null) "" else displaySpelling(raw, sp)
    }

    /**
     * 候选数变化时打一行。
     *
     * ## 为什么必须有
     *
     * 2026-10-03 E2E:`compose-space` 空格后拿到 `editText="ni"` —— 原始字母上屏了。
     * 顺着 `handleSpace()` 读下来是 `candidates` 为空 → 走 `commitRaw()`,
     * 而**「为什么候选是空的」在 logcat 里一个字都没有**:
     * `updateSelection … comp=0..2 raw=ni` 说明组词在、拼音在,
     * 但候选栏那边**完全静默**。于是「librime 还没部署完」与
     * 「查了但查不到」两件事长得一模一样。
     *
     * 只在**数量变化**时打:候选数每个按键都在变,每次都打会把 logcat 冲掉
     * (而 `takeLog` 只取 `tail -20` —— 之前正是被这个坑过)。
     */
    private var lastCandidateSig = ""

    private fun logCandidates(why: String) {
        val n = state.candidates.size
        // 判据是 **(raw, n) 这一对**,不是只看 n。
        //
        // 只看数量会踩这个坑:E2E 探针每次都是「清空 → 敲一个 n」,
        // 于是每次的数量都是同一个值,第二次开始 `n` 没变 ⇒ **一行都不打** ⇒
        // 探针等不到自己要的信号,判定成「引擎没就绪」。
        // 带上 raw 之后,「清空(n=0, raw=空)」与「敲了 n(n=5, raw=n)」是两件事。
        val sig = "${state.raw}|$n"
        if (sig == lastCandidateSig) return
        lastCandidateSig = sig
        android.util.Log.i(
            "ImeService",
            "candidates[$why]: raw=\"${state.raw}\" n=$n pages=${state.totalPages}"
        )
    }

    fun clearComposition() {
        state.clearRaw()
        state.candidates = emptyList()
        // 清空本身不调 [logCandidates],所以在这里把签名失效 ——
        // 否则「清空后再敲同一个键」因为 (raw,n) 与上一次相同而**不打日志**。
        lastCandidateSig = ""
        state.page = 0
        state.highlight = -1
        // **显示串必须跟着 raw 一起清**(2026-10-13 评审补):否则 ESC / abandon /
        // 上屏之后 spelling 留着旧值,而 `ImeService.syncComposing` 的 shown 取
        // `spelling.ifEmpty { raw }` —— 陈旧拼写会被当成"还有组词",
        // `finishComposingText()` 不再触发,编辑框里的拼音不清除。
        // 症状(评审时推出来的,单测钉住):组词后按 ESC,拼音留在输入框里。
        state.spelling = ""
    }

    /**
     * 切换输入框/外部改动组词区:丢弃当前组词,**层也归零**。
     *
     * 2026-10-12 用户「每次调出输入法需要恢复到初始状态」:调出(换框聚焦)即复位,
     * 不再保留上一框手动选的层 —— 那条"换输入框不该丢用户选的层"的旧设计
     * 连同层复位与 [setFieldKind] 的顺序约束一起作废(复位必须在 setFieldKind
     * 之前,数字类聚焦锁 L2 由后者重建)。窗口隐藏那一下照旧 [resetLayers]。
     */
    fun resetForNewInput() {
        clearComposition()
        symDown = false
        state.closePanels() // 换输入框 = 一次新的空闲态,面板不留(与层/大写一起归零)
        host.render()
    }

    /**
     * **大写 / Alt(L2) / Sym(L3) 全部回到初始态**(2026-09-29 用户定案,输入法隐藏时调用):
     * L1、无锁定、无一次性、长按层解除、大写关,连同长按/连击那套内部状态一起清干净。
     *
     * 与 [resetForNewInput] 的分工:换输入框现在也全量复位了(2026-10-12 调出即初始态),
     * 这个函数保留给"窗口都收起来了"那一刻 —— 同样全回初始,调用时机不同,内部态
     * (hold/suppress 那些字段)一起清,理由是"下一次长按 Alt 不该恢复过期快照"。
     */
    fun resetLayers() {
        // 层状态一次归零:锁定/一次性/按住中都在这一个字段里(含 [LayerState.Hold] 里的
        // snap/pending),不用再逐个清
        state.layerState = LayerState.L1
        state.shiftMode = ShiftMode.OFF
        shiftBeforeHold = ShiftMode.OFF
        holdMod = null
        holdCode = 0
        holdUsed = false
        shiftHolding = false
        shiftHeldCode = 0
        shiftUsedAsModifier = false
        symDown = false
        symProducedKey = false
        state.closePanels() // 窗口收起/调出 = 一次新的空闲态(与层、大写一起归零)
        // 敲击计数 / 敲击窗口 / 按住时长 / 吸收窗:全在一处,一次清干净
        gestures.resetAll()
        // 长按字母键的待判定一起取消:窗口都收了,还挂着个 300ms 定时器会莫名出符号
        lp.clear()
        host.cancelLongPress()
        host.render()
    }

    /** onUpdateSelection 发现组词区被外部破坏时调用。 */
    fun abandonComposition() {
        clearComposition()
        host.render()
    }

    // ------------------------------------------------------------- L6 虚拟键盘
    //
    // 2026-10-15 用户「空闲状态 sym+space 打开虚拟键盘,这是 L6 键盘,支持多个
    // tab 页和上下滑动虚拟键盘翻页」。四条定案(同一次对话里问定的):
    //   · 形态 = **条上方浮层**(不拉全屏,键盘表面照常用);
    //   · 内容 = **通用容器 + 出厂页**(见 [L6Ui]);
    //   · 开启 = **仅空闲态**,再按一次 Sym+Space 或 Esc 关闭,关闭不写任何字符;
    //   · 翻页 = **tab 内**上下翻(方向键与上下滑动),不跨 tab。
    //
    // ## 为什么 `Sym+Space` 要抢一把「本来是空格」的键
    //
    // L3(Sym)层是照可可的扩展字符表逐键定的(commit 7a906b5),其中明确写着
    // 「空格/回车/删除保持原功能」,并有单测 `L3 空格保持原功能` 钉住。
    // 用户 2026-10-15 定案:**空闲态抢走它**(组词中仍是空格),与可可对齐的那条
    // 就此打破 —— 所以那条单测与本段注释一起留着,别当成「忘了改回去」。

    /** L6 面板开着吗。 */
    val l6Open: Boolean get() = state.l6 != null

    /**
     * 三态循环浮层面板:[OFF]→[L5]→[L6]→[OFF](2026-10-16 用户「L5/L6/关闭三态切换」)。
     *
     * **仅空闲态**:组词中调用无效并返回 false —— 那时 `Sym+Space` 是「上屏空格」,
     * 面板不该抢。`Sym+Space` 与状态行右区键盘图标走**同一通路**(都调本方法)。
     *
     * L6 态切换时 [ImeState.l6] 重建为出厂内容(回 tab 0);非 L6 态 `l6` 置 null。
     * [ImeState.panelMode] 与 `l6` 始终同步(见该字段注释)。
     *
     * @return 是否真的切了状态
     */
    fun cyclePanel(): Boolean {
        if (state.hasComposition) return false
        state.panelMode = when (state.panelMode) {
            PanelMode.OFF -> PanelMode.L5
            PanelMode.L5 -> PanelMode.L6
            PanelMode.L6 -> PanelMode.OFF
        }
        state.l6 = if (state.panelMode == PanelMode.L6) L6Ui.factory(l6ClipItems) else null
        android.util.Log.i(
            "ImeService",
            "panel: ${state.panelMode}${if (state.panelMode == PanelMode.L6) " tab=0" else ""} raw=\"${state.raw}\"",
        )
        host.render()
        return true
    }

    /** 剪贴板快照(供 L6 剪贴板 tab)。由 [dev.hwkbd.ime.service.ImeService] 在监听回调里喂。 */
    private var l6ClipItems: List<ClipItem> = emptyList()

    /**
     * 喂剪贴板快照。剪贴板一变 ImeService 就调;内容没变则空操作。
     * L6 开着时立即原地刷新(保留 tab/页 —— 用户可能正停在剪贴板 tab 看新复制的那条)。
     */
    fun setL6Clipboard(items: List<ClipItem>) {
        if (items == l6ClipItems) return
        l6ClipItems = items
        if (state.panelMode == PanelMode.L6) {
            state.l6 = state.l6?.withClipboard(items)
            host.render()
        }
    }

    /** 上下翻页(tab 内)。@return 是否真的翻了 —— 到头不循环,返回 false 让面板不给反馈。 */
    fun l6StepPage(delta: Int): Boolean {
        val ui = state.l6 ?: return false
        val moved = ui.stepPage(delta)
        if (moved) {
            android.util.Log.i("ImeService", "l6: 翻页 tab=${ui.tabIndex} page=${ui.pageIndex}/${ui.pageCount}")
            host.render()
        }
        return moved
    }

    /** 切 tab(页码归零,见 [L6Ui.selectTab])。 */
    fun l6SelectTab(index: Int) {
        val ui = state.l6 ?: return
        if (index == ui.tabIndex) return
        ui.selectTab(index)
        android.util.Log.i("ImeService", "l6: 切 tab=$index(${ui.tab.id}) page=0")
        host.render()
    }

    // ------------------------------------------------------------------ 内部处理

    /** 当前输入框的表现策略(键位套 / Alt 文字 / 数字限定)。 */
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
        // 数字类锁着数字层,没有语言可切(状态项已隐藏),维持排除。
        if (shift && code == KeyEvent.KEYCODE_SPACE && !digitsOnly) {
            cycleLanguage()
            return true
        }

        // Sym+Space = 开/关 L6 虚拟键盘(2026-10-15 用户「空闲状态 sym+space 打开
        // 虚拟键盘」)。**仅空闲态**:组词中这把键仍旧上屏空格 —— 空闲才抢,
        // 见本节开头「为什么 Sym+Space 要抢一把本来是空格的键」。
        //
        // `layer` 取的是 effectiveLayer,所以单击 Sym(一次性 L3)、长按 Sym
        // (按住 L3)、双击 Sym(锁定 L3)三种按法都算「Sym+Space」。
        // 借来的 L3 被这一下用掉(与别的键一样「用完就还」):不还的话开关完面板那一层
        // 还挂着,下一颗字母键会被当成 L3 符号敲出去(实测:开完面板敲 n 出的是 L3 符号,
        // 组词起不来)。按住/锁定两种按法不受影响(它们不是一次性层)。
        if (code == KeyEvent.KEYCODE_SPACE && layer == Layer.L3 && !state.hasComposition) {
            consumeOneShot()
            return cyclePanel()
        }

        // L6 面板开着时,方向键归面板:上下 = 本 tab 内翻页,左右 = 切 tab
        // (2026-10-15 定案「翻页 = tab 内上下翻(方向键与上下滑动),不跨 tab」;
        // 左右切 tab 是同一次对话里从「不跨 tab」推出来的 —— 翻页这条路上 tab 不动,
        // 切 tab 就得另有一条,**面板开着时左右对候选无意义**(空闲态无候选),拿来切最顺)。
        // 放在候选键分支之前:面板只在空闲态存在,此刻 ↑↓←→ 不该落到"翻候选页"那条老路上。
        if (l6Open) {
            when (code) {
                KeyEvent.KEYCODE_DPAD_UP -> { l6StepPage(-1); consumeOneShot(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { l6StepPage(1); consumeOneShot(); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    state.l6?.let { l6SelectTab(it.tabIndex - 1) }
                    consumeOneShot(); return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    state.l6?.let { l6SelectTab(it.tabIndex + 1) }
                    consumeOneShot(); return true
                }
            }
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
                    state.dropLastRaw()
                    refreshQuery(from = "退格")
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
        // ASCII L2 的槽位**定的(数字位 + `- . + * # / :` 各占一键),状态项上的 `0-9-/:`
        // 也是照它标的。中文表把那些槽位换成了《》（》,再套上去日期框就输不出 `/`、电话框
        // 输不出 `+`,状态项也跟着说谎。数字框本来就没有"语言"可言,走键位表出厂值。
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
                    state.appendRaw(text)
                    refreshQuery(from = "字符键")
                } else {
                    // 语言策略决定上屏形态:英文跟大写、中文按开关全角;**数字类放行的字符强制半角**
                    // (全角 `－` `。` `：` 进不了日期/小数/电话/签名的合法格式,数字本身本就半角)
                    flushComposition()
                    // 大写一次用掉即回 OFF;[HELD] 是物理修饰键,不由这里消耗
                    val upper = shift || caps || state.shiftMode != ShiftMode.OFF
                    val full = fullWidthSymbolsInCn && !digitsOnly
                    host.commitText(lang.commitText(text, layer, upper, full))
                }
                consumeShiftOnce()
                consumeOneShot()
                host.render()
                return true
            }
            is KeyOut.Special -> {
                // 面板开着时 Esc 收面板,不转发给应用(2026-10-15 开关对称)。
                // 走的是**物理键**这条路(Sym+Q 是出厂 Esc);面板上那颗 Esc 键走
                // [commitL4Key]。两条都收面板,但这里要把一次性层/大写照常消耗掉 ——
                // Sym 是被当修饰键用掉的,不消耗会留下一层没人消的 L3。
                if (out.name == "esc" && l6Open) {
                    state.closePanels()
                    consumeShiftOnce()
                    consumeOneShot()
                    host.render()
                    return true
                }
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

    /**
     * 借来的层被下一个键用掉:恢复底下那份([LayerState.OneShot.snap])——
     * 锁着 Alt 借 Sym 时,敲完那一个 Sym 键就**回到锁着的 Alt**;没锁时回 L1(初始)。
     * 按住中不动(松手才恢复,见 [endHold])。
     */
    private fun consumeOneShot() {
        val s = state.layerState
        if (s is LayerState.OneShot) state.layerState = s.snap
    }

    /** 大写一次用掉即回 OFF(`HELD` 是物理修饰键、松手自然消失,不由这里消耗)。 */
    private fun consumeShiftOnce() {
        if (state.shiftMode == ShiftMode.ONCE) state.shiftMode = ShiftMode.OFF
    }

    companion object {
        private const val CANDIDATE_LIMIT = 45

        /** 首选(逻辑第 1 候选)的显示位 = 中间(空格键)。 */
        const val TOP_DISPLAY = 2

        /**
         * 长按修饰键判定:按住 Alt/Sym/Shift 超过此时长算"长按"(松开即取消),不到则按敲击。
         * 家在 [ModifierGestures.DEFAULT_HOLD_MS](它连敲击窗口一起管),这里只做转发别名,
         * 免得同一时长在两处各留一份常量。
         */
        const val HOLD_MS = ModifierGestures.DEFAULT_HOLD_MS

        /**
         * 组装拼音的**显示串**:首选候选的音节拼写([SyllableSpelling.text],带 `'`)
         * 拼回 [raw]。
         *
         * ## 为什么要拼,而不是直接显示 preedit
         *
         * 首选候选**不一定覆盖整串**:翻到后面的页,中央槽那个候选可能只覆盖开头
         * 几个音节(`nihao` 翻页后中央槽是「你」= 只覆盖 "ni")。它的音节拼写
         * 只该替换它覆盖的那一段,剩下的 raw 尾巴原样接在后面。
         *
         * ## 坐标系:引擎坐标**不含撇号**
         *
         * [SyllableSpelling.start] / [SyllableSpelling.end] 是喂进引擎那串里的下标,
         * 而 `RimeEngine.query` 重建时**跳过 `'`**(分节符不喂引擎)。
         * 所以映射回 raw 时数的是**非撇字符** —— 这一点与
         * [ImeController] 的 `readRemainder` 注释(写的是「含 '」)相反;
         * 以代码为准(2026-10-12 发现,已记 brain `unverified-as-fact`)。
         *
         * ## 每一段边界都补撇(2026-10-14 用户定)
         *
         * 候选覆盖段的终点/起点都是引擎的**音节边界**(候选永远覆盖整音节),
         * 覆盖段与剩下的 raw 是**两段**,接缝必须补撇 —— 「倪」只覆盖 `ni` 时
         * 显示 `ni'hao` 而不是 `nihao`:`ni` 后面需要拆分,整串的音节结构
         * 不随中央候选覆盖到哪儿而丢掉。只补覆盖段两侧的接缝;preedit 内部的
         * 分节由引擎给(空格分节在 rime 层已换成撇)。
         *
         * @return 拼好的显示串;引擎给不出音节拼写时由调用方传 null 走回退。
         */
        internal fun displaySpelling(raw: String, sp: SyllableSpelling): String {
            if (raw.isEmpty()) return ""
            // 非撇字符在 raw 里的下标表:rawStart[k] = 第 k 个(0 起)非撇字符的下标。
            val rawStart = ArrayList<Int>(raw.length)
            for ((i, c) in raw.withIndex()) if (c != '\'') rawStart += i
            val s = sp.start.coerceIn(0, rawStart.size)
            val e = sp.end.coerceIn(s, rawStart.size)
            val prefix = if (s == 0) "" else raw.substring(0, rawStart[s])
            val tail = if (e >= rawStart.size) "" else raw.substring(rawStart[e])
            return buildString {
                if (prefix.isNotEmpty()) append(prefix)
                if (sp.text.isNotEmpty()) {
                    if (isNotEmpty() && last() != '\'') append('\'')
                    append(sp.text)
                }
                if (tail.isNotEmpty()) {
                    if (isNotEmpty() && last() != '\'') append('\'')
                    append(tail)
                }
            }
        }
    }
}
