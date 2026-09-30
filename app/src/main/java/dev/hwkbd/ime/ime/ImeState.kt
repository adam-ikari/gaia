package dev.hwkbd.ime.ime

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.ChineseLanguage
import dev.hwkbd.ime.strategy.FieldKind

/**
 * 大写状态:**单一取值**,不拆成"落定的状态 + 是不是按着"两个字段
 * (2026-09-30 收拢:原先 `shift: ShiftState` + `shiftHeld: Boolean` 两个字段能同时表达
 * `ONCE + 按住中`、`CAPS + 按住中` 这类组合,渲染器只好自己写 `capsLock || once` 定优先级——
 * 一个概念一个字段,组合就无从产生)。
 *
 * - [OFF]:没有大写;
 * - [ONCE]:大写一次(敲一下 Shift,输入一个字母后自动取消);
 * - [CAPS]:Caps 大写锁定(双击 Shift);
 * - [HELD]:物理 Shift 正按住,**长按大写**——大写来自物理修饰键本身,松手即消失。
 *
 * [HELD] 与 [ONCE]/[CAPS] 不共存:按下时先记住按前的值([ImeController] 的
 * `shiftBeforeHold`),松手恢复它——所以"Caps 开着时长按 Shift 再松手"仍是 Caps,
 * "空状态时长按再松手"回到空状态,和拆分前的行为逐条一致。
 */
enum class ShiftMode { OFF, ONCE, CAPS, HELD }

/**
 * 当前层状态:**一个持续态 + 最多一层临时覆盖**(2026-09-30):取代原先 `lock` / `oneShot` / `hold`
 * 三个可空字段。
 *
 * - [L1]:不在任何层上(= 初始状态);
 * - [Locked]:双击 Alt/Sym —— 常驻,再按一次同层键解除;
 * - [OneShot]:单击 Alt/Sym —— **借用**一个层,用一次(下一个键)或再敲一下就恢复 [snap];
 * - [Hold]:长按 Alt/Sym —— 按住期间生效,松手恢复 [snap]。
 *
 * ## 为什么临时层都带一个 [Hold.snap] / [OneShot.snap]
 *
 * 覆盖层要恢复"底下那份",所以它必须把底下那份**收进自己内部**。原先这两个值分别是
 * controller 的快照字段与 `state.oneShot` —— `hold` 与 `oneShot` 能同时非空,而"谁赢"只写在
 * `effectiveLayer` 那个 getter 里;后来收成了单值和类型,**代价是表达不了"锁着 Alt 借一次
 * Sym"**(锁会被顶掉),真机报障:「锁着 Alt 时单击 Sym,Sym 也是锁定的」⇒ 改成单击借一次,
 * 而用户要的是「**用完返回上一个锁定状态,没有锁定状态就返回初始状态**」。
 *
 * 现在的形状:顶层永远是**一个**状态(不是三个可空字段),并存由类型自己承载 ——
 * 借用 = [OneShot](借的层, 底下那份),底下那份可以是 [Locked]。**非法组合写不出来**,
 * 优先级也由类型承载(覆盖层赢,[base] 见下)。
 */
sealed interface LayerState {
    val layer: Layer

    /** 不在层上(= 初始状态)。 */
    data object L1 : LayerState {
        override val layer: Layer get() = Layer.L1
    }

    data class Locked(override val layer: Layer) : LayerState

    /**
     * 一次性层:**借用** [layer] 一个键,结束条件满足就恢复 [snap](下一个键消费掉,或再敲一下)。
     *
     * [snap] = 恢复目标,一律是**持续态**([L1] 或某个 [Locked]),用 [borrow] 造,别手写:
     * 锁着 Alt 时单击 Sym ⇒ `OneShot(L3, Locked(L2))` —— 敲一个 Sym 键,然后回到**锁着的 Alt**;
     * 没有锁时 `snap` 是 [L1],也就是回初始状态。**没被用掉的旧借用会被丢掉**(见 [borrow]):
     * 一次性层的有效期只有"下一个键",在用掉它之前又去按另一个修饰键,它就该作废;锁不会。
     */
    data class OneShot(
        override val layer: Layer,
        val snap: LayerState,
    ) : LayerState

    /**
     * 按住中。[snap] = 按下前的层状态,松手时恢复它(长按即取消;敲击则恢复后另落定新状态)。
     * **只有一个快照**:敲击判定统一在 keyUp 做([ImeController] 的迁移总表),所以按住期间
     * 不存在"已经落定了什么"——2026-09-30 整条梳理时删掉了原先的 pending(它只是因为 Alt
     * 曾在 keyDown 计数才需要)。
     *
     * **呈现上按住中与敲击落定的结果长得一样**(同一层、锁定样式取自 [snap]),所以状态行直接
     * 画它、不藏——真机报障「在 alt 状态下长按 alt 会有 alt 消失 0.5s 左右」就是这么来的:
     * 那时状态行还在按"敲击窗口"藏暂定态,而长按并没有产生任何需要藏的东西。
     */
    data class Hold(
        override val layer: Layer,
        val snap: LayerState,
    ) : LayerState

    companion object {
        /** 按层号造一个状态:[locked] 为真给 [Locked],否则给 [OneShot](L1 归 [L1])。 */
        fun of(layer: Layer, locked: Boolean): LayerState =
            if (layer == Layer.L1) L1 else if (locked) Locked(layer) else borrow(layer, L1)

        /**
         * 造一次**借用**:`over` 里的锁定留着当恢复目标,其余临时层(没被用掉的旧借用)丢掉。
         *
         * 这就是「**借用一次,回上一个锁定;没有锁定就回初始**」这条规则的**唯一实现点** ——
         * 2026-09-30 用户定答。丢旧借用是有意的:一次性层的有效期只有"下一个键",用户没在
         * 下一个键上用它、而是去按了另一个修饰键,那次借用已经过期;而锁是用户明确要的持续态。
         */
        fun borrow(layer: Layer, over: LayerState): OneShot = OneShot(layer, over.base)
    }
}

/**
 * **持续态**:剥掉所有临时层([Hold] / [OneShot]),剩下 [L1] 或某个 [Locked]。
 *
 * 两个读法都走它:
 * - [ImeState.lockedLayer] —— 所以"按住 Alt 期间"与"借 Sym 期间"都仍然读得到底下的锁
 *   (与拆分前 `state.lock` 在按住期间照样有值一致;借用期间则是 2026-09-30 新增的读法);
 * - [LayerState.borrow] —— 借用时锁定要留下来。
 */
val LayerState.base: LayerState
    get() = when (this) {
        is LayerState.Hold -> snap.base
        is LayerState.OneShot -> snap.base
        else -> this
    }

/**
 * 输入法全部可渲染状态。ImeController 持有并修改它,UI(service/视图)只读。
 */
class ImeState {
    /** 当前语言 id:由 [dev.hwkbd.ime.strategy.ImeRegistry] 注册表解析成策略,枚举语言已退役。 */
    var languageId: String = ChineseLanguage.id

    /** 当前输入框类型:决定 L4 键位套与 Alt 那一项的文字(输入类型决定)。 */
    var fieldKind: FieldKind = FieldKind.TEXT

    /**
     * 密码框标志(服务在 onStartInput 按 [dev.hwkbd.ime.strategy.FieldKind.isPassword] 置位):
     * 为 true 时**禁止切换语言**(只能停留在安全语言),避免拼音组词混入密码。
     */
    var secure = false

    /** 大写状态(单一取值,见 [ShiftMode])。 */
    var shiftMode: ShiftMode = ShiftMode.OFF

    /**
     * 当前层状态:**一个持续态 + 最多一层临时覆盖**(2026-09-30)。见 [LayerState] 的类注释。
     *
     * 原先是 `lock` / `oneShot` / `hold` 三个可空字段,靠 `hold ?: lock ?: oneShot` 拼出优先级 ——
     * 那样"谁赢"只存在于那个 getter 里,谁再加一种组合都得记得补优先级。收成和类型后非法组合
     * 写不出来,优先级由类型本身承载;后来发现"锁着 Alt 借一次 Sym"这种**合法**的并存也表达不了
     * (锁会被顶掉),于是把"恢复目标"收进临时层内部([LayerState.OneShot.snap])——并存重新
     * 表达了,但这次是**类型自己说明的并存**,不是三个字段的巧合。
     */
    var layerState: LayerState = LayerState.L1

    /** 当前生效的层(从 [layerState] 派生,UI 与出字都读它)。 */
    val effectiveLayer: Layer get() = layerState.layer

    /**
     * 锁定层(双击 Alt/Sym 进入)的层号,没锁定为 null。
     * **看得穿临时层**(走 [base]):按住 Alt 期间、借用 Sym 期间,底下那个锁都仍然算数 ——
     * 状态行就是靠这个区分"锁定(带下划线)"和"借用/按住(无下划线)"。
     */
    val lockedLayer: Layer? get() = (layerState.base as? LayerState.Locked)?.layer

    /**
     * 待用的一次性层(单击 Alt/Sym 借来的那个),没有为 null。顶层就是它;按住中时它在底下
     * ([Hold] 是透明的,松手后那个借用还在)。
     */
    val oneShotLayer: Layer?
        get() = when (val s = layerState) {
            is LayerState.OneShot -> s.layer
            is LayerState.Hold -> (s.snap as? LayerState.OneShot)?.layer
            else -> null
        }

    /** 拼音缓冲(中文态),也是编辑框里的组词区内容。 */
    var raw = ""

    var candidates: List<Candidate> = emptyList()
    var page = 0
    var pageSize = 5

    /** 页内高亮下标(DPAD_DOWN 移动、DPAD_UP 确认),-1 表示无高亮。 */
    var highlight = -1

    val hasComposition: Boolean get() = raw.isNotEmpty()

    val totalPages: Int
        get() = if (candidates.isEmpty()) 0 else (candidates.size + pageSize - 1) / pageSize

    /**
     * 页内显示顺序:**4 2 1 3 5**——第一候选固定在中间(空格键位),2/3 紧邻两侧,4/5 在最外:
     * 显示 [0]=候选4(Shift) [1]=候选2(Sym) [2]=候选1(空格) [3]=候选3(Ctrl) [4]=候选5(Alt)。
     * 五键 Shift/Sym/空格/Ctrl/Alt 对应显示槽 0..4——紧邻空格的 Sym/Ctrl 拿 2/3,外侧拿 4/5。
     * (2026-09-28 用户定案,取代此前的"可可实测排列 2 5 1 4 3"。)
     */
    fun currentPageLogical(): List<Candidate> = logicalAt(page)

    /** 指定页号的候选(页号自动夹到有效范围)。 */
    fun logicalAt(p: Int): List<Candidate> {
        if (candidates.isEmpty() || totalPages == 0) return emptyList()
        val pc = p.coerceIn(0, totalPages - 1)
        val from = pc * pageSize
        val to = minOf((pc + 1) * pageSize, candidates.size)
        return candidates.subList(from, to)
    }

    /**
     * 页内显示槽(固定 5 槽;空槽为 null)。满 5 用 4-2-1-3-5 排列,不足时同规则从中位向两侧紧凑补位。
     *
     * **页号要夹到有效范围**(走 [currentPageLogical]):候选集变小(又敲了一个字母)时 `page` 可能
     * 暂时越界,这里必须兜住——直接越界返回空表会让候选行画出**五个空槽**而翻页箭头还亮着。
     * 预览相邻页的 [pageSlotsAt] 才是"越界即没有那一页"的严格语义,两者别混。
     */
    fun pageSlots(): List<Candidate?> {
        val pageList = currentPageLogical()
        if (pageList.isEmpty()) return emptyList()
        val order = displayOrder(pageList.size)
        return Array(pageSize) { d ->
            pageList.getOrElse(order[d]) { null }
        }.toList()
    }

    /**
     * **指定页**的显示槽——翻页拖动预览相邻页用(跟手翻页要提前把下一页/上一页的词摆出来,
     * 见 [dev.hwkbd.ime.ui.CandidateBarView] 的翻页拖动);越界返回**空表**(那一侧就没有页)。
     */
    fun pageSlotsAt(p: Int): List<Candidate?> {
        if (p < 0 || p >= totalPages) return emptyList()
        val pageList = logicalAt(p)
        if (pageList.isEmpty()) return emptyList()
        val order = displayOrder(pageList.size)
        return Array(pageSize) { d ->
            pageList.getOrElse(order[d]) { null }
        }.toList()
    }

    /** 显示槽 → 逻辑序映射:满 5 用 4-2-1-3-5,不足时同规则紧凑(中→左内→右内→左外→右外),避免空洞。 */
    fun displayOrder(logicalSize: Int): IntArray =
        if (logicalSize >= pageSize) DISPLAY_ORDER else COMPACT_ORDER

    /** 有效显示位(有候选的槽)。 */
    fun validDisplays(): List<Int> = pageSlots().mapIndexed { i, c -> if (c != null) i else -1 }.filter { it >= 0 }

    /** 页内显示序(紧凑,用于计数等)。 */
    fun pageItems(): List<Candidate> = pageSlots().filterNotNull()

    companion object {
        /** 满 5 候选:4 2 1 3 5(槽=Shift/Sym/空格/Ctrl/Alt → 候选4/2/1/3/5,首选恒在中槽)。 */
        val DISPLAY_ORDER = intArrayOf(3, 1, 0, 2, 4)

        /** 不足 5 候选:同规则紧凑(槽2=首选,槽1=候选2,槽3=候选3,槽0=候选4…),从中间往两侧补。 */
        val COMPACT_ORDER = intArrayOf(3, 1, 0, 2, 4)
    }
}
