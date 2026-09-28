package dev.hwkbd.ime.ime

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.keymap.Layer
import dev.hwkbd.ime.strategy.ChineseLanguage
import dev.hwkbd.ime.strategy.FieldKind

enum class ShiftState { OFF, ONCE, CAPS }

/**
 * 输入法全部可渲染状态。ImeController 持有并修改它,UI(service/视图)只读。
 */
class ImeState {
    /** 当前语言 id:由 [dev.hwkbd.ime.strategy.ImeRegistry] 注册表解析成策略,枚举语言已退役。 */
    var languageId: String = ChineseLanguage.id

    /** 当前输入框类型:决定 L4 键位套与 Alt 芯片文字(输入类型决定)。 */
    var fieldKind: FieldKind = FieldKind.TEXT

    /**
     * 密码框标志(服务在 onStartInput 按 [dev.hwkbd.ime.strategy.FieldKind.isPassword] 置位):
     * 为 true 时**禁止切换语言**(只能停留在安全语言),避免拼音组词混入密码。
     */
    var secure = false

    var shift = ShiftState.OFF

    /** 物理 Shift 正被按住(长按大写:松手即取消),用于状态行大写芯片提示。 */
    var shiftHeld = false

    /** 锁定层(双击 Alt/Sym 进入),null 表示未锁定。 */
    var lock: Layer? = null

    /** 一次性层(单击 Alt/Sym),下一个键输出后自动回 L1。 */
    var oneShot: Layer? = null

    /** 长按 Alt/Sym 激活的临时层:按住期间生效,松开立即取消(优先级最高)。 */
    var hold: Layer? = null

    val effectiveLayer: Layer
        get() = hold ?: lock ?: oneShot ?: Layer.L1

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
    fun currentPageLogical(): List<Candidate> {
        if (candidates.isEmpty() || totalPages == 0) return emptyList()
        val p = page.coerceIn(0, totalPages - 1)
        val from = p * pageSize
        val to = minOf((p + 1) * pageSize, candidates.size)
        return candidates.subList(from, to)
    }

    /** 页内显示槽(固定 5 槽;空槽为 null)。满 5 用 4-2-1-3-5 排列,不足时同规则从中位向两侧紧凑补位。 */
    fun pageSlots(): List<Candidate?> {
        val pageList = currentPageLogical()
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
