package dev.hwkbd.ime.keymap

import android.view.KeyEvent

/**
 * 单个物理键在某层的输出:要么是字面文本,要么是功能键(以名字表示,层映射文件里写作 key:esc)。
 * 功能键名到 keycode 的解析集中在 [SPECIAL_CODES],便于 JVM 单测直接使用。
 */
sealed class KeyOut {

    data class Text(val text: String) : KeyOut()
    data class Special(val name: String) : KeyOut()

    fun format(): String = when (this) {
        is Text -> text
        is Special -> "key:$name"
    }

    companion object {
        val SPECIAL_CODES: Map<String, Int> = mapOf(
            "enter" to KeyEvent.KEYCODE_ENTER,
            "space" to KeyEvent.KEYCODE_SPACE,
            "del" to KeyEvent.KEYCODE_DEL,
            "tab" to KeyEvent.KEYCODE_TAB,
            "esc" to KeyEvent.KEYCODE_ESCAPE,
            "insert" to KeyEvent.KEYCODE_INSERT,
            "left" to KeyEvent.KEYCODE_DPAD_LEFT,
            "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
            "up" to KeyEvent.KEYCODE_DPAD_UP,
            "down" to KeyEvent.KEYCODE_DPAD_DOWN,
            "move_home" to KeyEvent.KEYCODE_MOVE_HOME,
            "move_end" to KeyEvent.KEYCODE_MOVE_END,
            "pgup" to KeyEvent.KEYCODE_PAGE_UP,
            "pgdn" to KeyEvent.KEYCODE_PAGE_DOWN,
            "f1" to KeyEvent.KEYCODE_F1,
            "f2" to KeyEvent.KEYCODE_F2,
            "f3" to KeyEvent.KEYCODE_F3,
            "f4" to KeyEvent.KEYCODE_F4,
            "f5" to KeyEvent.KEYCODE_F5,
            "f6" to KeyEvent.KEYCODE_F6,
            "f7" to KeyEvent.KEYCODE_F7,
            "f8" to KeyEvent.KEYCODE_F8,
            "f9" to KeyEvent.KEYCODE_F9,
            "f10" to KeyEvent.KEYCODE_F10,
            "f11" to KeyEvent.KEYCODE_F11,
            "f12" to KeyEvent.KEYCODE_F12,
        )

        fun parse(s: String): KeyOut? {
            if (s.isEmpty()) return null
            if (s.startsWith("key:")) {
                val name = s.removePrefix("key:")
                return if (name in SPECIAL_CODES) Special(name) else null
            }
            return Text(s)
        }
    }
}
