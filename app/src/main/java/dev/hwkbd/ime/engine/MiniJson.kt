package dev.hwkbd.ime.engine

/**
 * 极小 JSON 读写器,只为 [UserDict] 的持久化 schema 服务
 * (对象/数组/字符串/整数/布尔,null 之外的浮点不支持)。
 * Android 的 org.json 在 JVM 单测里是 stub,所以自己实现,保证可测。
 */
internal object MiniJson {

    fun write(value: Any?): String = StringBuilder().also { writeTo(value, it) }.toString()

    private fun writeTo(value: Any?, sb: StringBuilder) {
        when (value) {
            null -> sb.append("null")
            is String -> writeString(value, sb)
            is Boolean -> sb.append(value)
            is Int, is Long -> sb.append(value.toString())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeString(k.toString(), sb)
                    sb.append(':')
                    writeTo(v, sb)
                }
                sb.append('}')
            }
            is List<*> -> {
                sb.append('[')
                var first = true
                for (v in value) {
                    if (!first) sb.append(',')
                    first = false
                    writeTo(v, sb)
                }
                sb.append(']')
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c.code < 0x20) sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }

    fun parse(text: String): Any? = Parser(text).run {
        val v = parseValue()
        skipWs()
        require(pos >= text.length) { "trailing content at $pos" }
        v
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun parseValue(): Any? {
            skipWs()
            require(pos < s.length) { "unexpected end" }
            return when (s[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> parseString()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> parseNumber()
            }
        }

        private fun lit(word: String, v: Boolean?): Boolean? {
            require(s.regionMatches(pos, word, 0, word.length)) { "bad literal at $pos" }
            pos += word.length
            return v
        }

        private fun parseObject(): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            pos++ // {
            skipWs()
            if (pos < s.length && s[pos] == '}') { pos++; return m }
            while (true) {
                skipWs()
                require(pos < s.length && s[pos] == '"') { "expected key at $pos" }
                val k = parseString()
                skipWs()
                require(pos < s.length && s[pos] == ':') { "expected ':' at $pos" }
                pos++
                m[k] = parseValue()
                skipWs()
                require(pos < s.length) { "unexpected end in object" }
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return m }
                    else -> throw IllegalArgumentException("bad object at $pos")
                }
            }
        }

        private fun parseArray(): List<Any?> {
            val l = ArrayList<Any?>()
            pos++ // [
            skipWs()
            if (pos < s.length && s[pos] == ']') { pos++; return l }
            while (true) {
                l += parseValue()
                skipWs()
                require(pos < s.length) { "unexpected end in array" }
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return l }
                    else -> throw IllegalArgumentException("bad array at $pos")
                }
            }
        }

        private fun parseString(): String {
            pos++ // "
            val sb = StringBuilder()
            while (true) {
                require(pos < s.length) { "unterminated string" }
                when (val c = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        require(pos < s.length) { "unterminated escape" }
                        when (val e = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('')
                            'u' -> {
                                require(pos + 4 < s.length) { "bad unicode escape" }
                                sb.append(s.substring(pos + 1, pos + 5).toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw IllegalArgumentException("bad escape \\$e at $pos")
                        }
                        pos++
                    }
                    else -> { sb.append(c); pos++ }
                }
            }
        }

        private fun parseNumber(): Long {
            val start = pos
            if (pos < s.length && (s[pos] == '-' || s[pos] == '+')) pos++
            require(pos < s.length && s[pos].isDigit()) { "bad number at $pos" }
            while (pos < s.length && (s[pos].isDigit())) pos++
            return s.substring(start, pos).toLong()
        }
    }
}
