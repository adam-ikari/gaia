package dev.hwkbd.ime.strategy

import android.text.InputType

/**
 * 输入框类型:由 [android.view.inputmethod.EditorInfo.inputType] 推导,**输入类型决定上下文表现**——
 * 网址框换 http:// 键位套、数字框把 Alt 芯片显示成 0-9(见 [FieldProfile] / [ImeRegistry])。
 *
 * 只认三类:纯文本 / 网址 / 数字(电话并入数字)。新增类型要同时在 [from] 里识别并注册一份 [FieldProfile]。
 */
enum class FieldKind {
    TEXT,
    URL,
    NUMBER;

    companion object {
        fun from(inputType: Int): FieldKind {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when {
                // 数字类含电话(TYPE_CLASS_PHONE):一并算数字框
                cls == InputType.TYPE_CLASS_NUMBER || cls == InputType.TYPE_CLASS_PHONE -> NUMBER
                cls == InputType.TYPE_CLASS_TEXT && variation == InputType.TYPE_TEXT_VARIATION_URI -> URL
                else -> TEXT
            }
        }
    }
}
