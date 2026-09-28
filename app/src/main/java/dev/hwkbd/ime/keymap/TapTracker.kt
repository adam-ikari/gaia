package dev.hwkbd.ime.keymap

/**
 * 单击/双击/三击判定:同一修饰键在 [windowMs] 内的连续敲击计数。
 * 窗口外的新敲击从 1 重新开始。
 */
class TapTracker(
    private val now: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = 300L,
) {
    private var last = 0L
    private var count = 0

    /** 记录一次敲击,返回窗口内的连续次数(1,2,3…)。 */
    fun tap(): Int {
        val t = now()
        count = if (t - last <= windowMs) count + 1 else 1
        last = t
        return count
    }

    /** 清空计数(解锁等状态跃迁后,吸收紧随的连击用)。 */
    fun reset() {
        count = 0
        last = 0
    }

    /** 当前窗口内已积累的次数(不记录新敲击)。 */
    fun peek(): Int = count
}
