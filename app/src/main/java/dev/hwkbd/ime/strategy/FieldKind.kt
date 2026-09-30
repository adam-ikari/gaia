package dev.hwkbd.ime.strategy

import android.text.InputType

/**
 * 输入框类型:由 [android.view.inputmethod.EditorInfo.inputType] 推导,**输入类型决定上下文表现**——
 * 网址/邮件框换 L4 键位套,数字类锁定数字层并只放行该类合法字符(见 [FieldProfile] / [ImeRegistry])。
 *
 * 合法格式调查(2026-09-28,用户点名"其他输入框类型也要调查"):
 * - **NUMBER**:合法字符就是 `0-9`;SIGNED/DECIMAL 是 **flag**(0x1000/0x2000)不是 variation,
 *   命中则额外放行 `-` / `.`(两者可同时出现);
 * - **PHONE**:Android `PhoneKeyListener` 的集合 = `0-9 + * #`;
 * - **DATETIME**:日期/时间字面量 `2026-09-28 12:30` → `0-9 - / : .`;
 * - **TEXT 变体**:只有 URI(网址)与 EMAIL_ADDRESS(邮箱)值得专属键位套,其余
 *   (短信/姓名/地址/主题…)走默认套即可;密码变体(含**数字密码**)见 [isPassword]。
 *
 * 数字类(digitsOnly)的表现:聚焦即锁 L2 数字层、只放行 [FieldProfile.digitChars]+数字,
 * 状态行只留标着实际字符集的 Alt(`0-9` + 各类型放行字符);**L4 不隐藏**(2026-09-29 用户定案):
 * 滤掉全非法的核心键,键位套里只显**L1-L3 敲不出**的键(数字态 = 短语组合 `+86` 这类),
 * 白名单另并入 L4 显示的键输出。
 * 新增类型要同时在 [from] 里识别并注册一份 [FieldProfile]。
 */
enum class FieldKind {
    TEXT,
    URL,
    EMAIL,
    NUMBER,
    NUMBER_SIGNED,
    NUMBER_DECIMAL,
    NUMBER_SIGNED_DECIMAL,
    PHONE,
    DATETIME;

    companion object {
        fun from(inputType: Int): FieldKind {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            return when (cls) {
                InputType.TYPE_CLASS_NUMBER -> {
                    val signed = inputType and InputType.TYPE_NUMBER_FLAG_SIGNED != 0
                    val decimal = inputType and InputType.TYPE_NUMBER_FLAG_DECIMAL != 0
                    when {
                        signed && decimal -> NUMBER_SIGNED_DECIMAL
                        signed -> NUMBER_SIGNED
                        decimal -> NUMBER_DECIMAL
                        else -> NUMBER
                    }
                }
                InputType.TYPE_CLASS_PHONE -> PHONE
                InputType.TYPE_CLASS_DATETIME -> DATETIME
                InputType.TYPE_CLASS_TEXT -> when (variation) {
                    InputType.TYPE_TEXT_VARIATION_URI -> URL
                    InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS -> EMAIL
                    else -> TEXT
                }
                else -> TEXT
            }
        }

        /**
         * 密码框判定(强制安全语言用):文本的密码变体(含 WEB/可见密码),**以及数字密码**
         * (`TYPE_NUMBER_VARIATION_PASSWORD`,以前漏了——同理调查补上)。
         */
        fun isPassword(inputType: Int): Boolean {
            val cls = inputType and InputType.TYPE_MASK_CLASS
            val variation = inputType and InputType.TYPE_MASK_VARIATION
            if (cls == InputType.TYPE_CLASS_NUMBER) {
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
            }
            if (cls != InputType.TYPE_CLASS_TEXT) return false
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        }
    }
}
