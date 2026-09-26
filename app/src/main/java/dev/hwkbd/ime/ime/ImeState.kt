package dev.hwkbd.ime.ime

import dev.hwkbd.ime.engine.Candidate
import dev.hwkbd.ime.keymap.Layer

enum class ImeMode { CN, EN }

enum class ShiftState { OFF, ONCE, CAPS }

/**
 * 输入法全部可渲染状态。ImeController 持有并修改它,UI(service/视图)只读。
 */
class ImeState {
    var mode = ImeMode.CN
    var shift = ShiftState.OFF

    /** 锁定层(双击 Alt/Sym 进入),null 表示未锁定。 */
    var lock: Layer? = null

    /** 一次性层(单击 Alt/Sym),下一个键输出后自动回 L1。 */
    var oneShot: Layer? = null

    val effectiveLayer: Layer
        get() = lock ?: oneShot ?: Layer.L1

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

    fun pageItems(): List<Candidate> {
        if (candidates.isEmpty() || totalPages == 0) return emptyList()
        val p = page.coerceIn(0, totalPages - 1)
        val from = p * pageSize
        val to = minOf((p + 1) * pageSize, candidates.size)
        return candidates.subList(from, to)
    }
}
