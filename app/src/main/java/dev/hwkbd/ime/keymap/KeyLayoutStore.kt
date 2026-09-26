package dev.hwkbd.ime.keymap

import android.content.Context

/** 层映射的持久化:filesDir/keymap.txt,不存在时用内置默认。 */
object KeyLayoutStore {

    private const val FILE = "keymap.txt"

    fun load(context: Context): KeyLayout {
        val text = loadTextOrDefault(context)
        return KeyLayout.parseText(text).layout ?: KeyLayout.default()
    }

    fun loadTextOrDefault(context: Context): String {
        val f = context.getFileStreamPath(FILE) ?: return KeyLayout.default().formatText()
        if (!f.exists()) return KeyLayout.default().formatText()
        return runCatching { f.readText() }.getOrElse { KeyLayout.default().formatText() }
    }

    fun save(context: Context, text: String): Boolean {
        if (KeyLayout.parseText(text).layout == null) return false
        context.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(text.toByteArray()) }
        return true
    }

    fun reset(context: Context) {
        context.getFileStreamPath(FILE)?.delete()
    }
}
