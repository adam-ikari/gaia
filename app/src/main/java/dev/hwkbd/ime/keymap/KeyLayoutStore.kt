package dev.hwkbd.ime.keymap

import android.content.Context

/**
 * L4 自定义键的持久化:`filesDir/keymap.txt`,不存在时用出厂值。
 *
 * ## 只读:L1/L2/L3 永远走出厂表(2026-10-02 用户定案「L1-L3 不支持编辑」)
 *
 * 键位编辑器(层映射编辑器 Activity)已经下线。2026-10-16 清扫死代码时,它剩下的写入侧
 * (`save`/`reset`,零调用方)连同设置页那行没人处理的 `layer_reset` 一起删掉 —— 从此
 * **没有任何代码路径能写出 `filesDir/keymap.txt`**,[load] 只为了读旧机器上遗留的那份文件。
 *
 * 那为什么读的时候还要明确"只取 L4":因为**早年用过编辑器的机器,`filesDir/keymap.txt` 里
 * 还留着 L1/L2/L3 段**,直接 `parseText` 会拿那份旧快照盖掉出厂表 ——
 * 改了对出厂表的代码,那些机器上却看不到,是个查起来很费劲的差别。
 *
 * 把规则写进 [load] 而不是"反正没人改",是为了让"L1-L3 不支持编辑"成为**结构事实**:
 * 旧文件删不删都不影响三层映射 —— 想改键位表只能改 [KeyLayout.default] 并重新安装。
 */
object KeyLayoutStore {

    private const val FILE = "keymap.txt"

    fun load(context: Context): KeyLayout {
        val factory = KeyLayout.default()
        val text = loadTextOrDefault(context)
        val parsed = KeyLayout.parseText(text).layout ?: return factory
        // **只要 L4**:物理三层永远是出厂表,文件里残留的 [L1]/[L2]/[L3] 段一律忽略。
        return factory.withL4From(parsed)
    }

    private fun loadTextOrDefault(context: Context): String {
        val f = context.getFileStreamPath(FILE) ?: return KeyLayout.default().formatText()
        if (!f.exists()) return KeyLayout.default().formatText()
        return runCatching { f.readText() }.getOrElse { KeyLayout.default().formatText() }
    }
}
