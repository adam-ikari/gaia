package dev.hwkbd.ime.keymap

import android.view.KeyEvent

/** 键名 ↔ keycode。层映射文本文件里用这些名字指代物理键。 */
object KeyNames {

    val NAME_TO_CODE: Map<String, Int> = buildMap {
        for (i in 0..25) put(('a' + i).toString(), KeyEvent.KEYCODE_A + i)
        for (i in 0..9) put(i.toString(), KeyEvent.KEYCODE_0 + i)
        put("space", KeyEvent.KEYCODE_SPACE)
        put("comma", KeyEvent.KEYCODE_COMMA)
        put("period", KeyEvent.KEYCODE_PERIOD)
        put("semicolon", KeyEvent.KEYCODE_SEMICOLON)
        put("apostrophe", KeyEvent.KEYCODE_APOSTROPHE)
        put("slash", KeyEvent.KEYCODE_SLASH)
        put("grave", KeyEvent.KEYCODE_GRAVE)
        put("minus", KeyEvent.KEYCODE_MINUS)
        put("equals", KeyEvent.KEYCODE_EQUALS)
        put("lbracket", KeyEvent.KEYCODE_LEFT_BRACKET)
        put("rbracket", KeyEvent.KEYCODE_RIGHT_BRACKET)
        put("bkslash", KeyEvent.KEYCODE_BACKSLASH)
        put("enter", KeyEvent.KEYCODE_ENTER)
        put("tab", KeyEvent.KEYCODE_TAB)
        put("del", KeyEvent.KEYCODE_DEL)
        put("esc", KeyEvent.KEYCODE_ESCAPE)
        put("up", KeyEvent.KEYCODE_DPAD_UP)
        put("down", KeyEvent.KEYCODE_DPAD_DOWN)
        put("left", KeyEvent.KEYCODE_DPAD_LEFT)
        put("right", KeyEvent.KEYCODE_DPAD_RIGHT)
        put("move_home", KeyEvent.KEYCODE_MOVE_HOME)
        put("move_end", KeyEvent.KEYCODE_MOVE_END)
        put("pgup", KeyEvent.KEYCODE_PAGE_UP)
        put("pgdn", KeyEvent.KEYCODE_PAGE_DOWN)
        for (i in 1..12) put("f$i", KeyEvent.KEYCODE_F1 + i - 1)
    }

    val CODE_TO_NAME: Map<Int, String> = NAME_TO_CODE.entries.associate { it.value to it.key }
}
