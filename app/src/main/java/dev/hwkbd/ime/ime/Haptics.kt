package dev.hwkbd.ime.ime

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * 触感反馈的**唯一入口**:所有"这件事成了"的确认点都从这里出。
 *
 * 2026-09-30 用户陆续加了四处震动:飞字过阈值、翻页提交、大写锁定、层锁定(Alt/Sym)。
 * 它们的共同点是"用户刚做了一个**有后果**的操作",而手不用离开键盘、眼不用去找状态行 ——
 * 触觉是这条路径上唯一"零视觉成本"的反馈通道。
 *
 * 三条原则(照这三条挑震动点,别到处撒):
 * 1. **只在状态真的改变时震**,滑动过程中不震(一路嗡嗡比不震更糟);
 * 2. **短促一下**(12ms),像"咔",不是持续嗡 —— 输入法常驻,持续震动会耗电且招人烦;
 * 3. **可关**(设置 → 常规 → 震动反馈),关掉之后这条通道完全静默,不影响任何状态机判定。
 *
 * 震动器不存在(部分设备无马达)或系统关了触感时静默失败:反馈是锦上添花,不是功能依赖。
 */
class Haptics(context: Context) {

    /** 总开关:设置项 `haptic` ,默认开。 */
    @Volatile
    var enabled: Boolean = true

    private val vibrator: Vibrator? = runCatching {
        context.applicationContext
            .getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }.getOrNull()?.takeIf { it.hasVibrator() }

    /** 锁定类反馈(Caps / Alt 层 / Sym 层):比确认类稍重一点,让"锁住了"有实感。 */
    fun lock() = buzz(14L, if (vibrator?.hasAmplitudeControl() == true) 120 else VibrationEffect.DEFAULT_AMPLITUDE)

    /** 确认类反馈(飞字过阈值、翻页提交):轻,一下就够。 */
    fun confirm() = buzz(12L, VibrationEffect.DEFAULT_AMPLITUDE)

    private fun buzz(ms: Long, amplitude: Int) {
        if (!enabled) return
        val v = vibrator ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        }
    }
}
