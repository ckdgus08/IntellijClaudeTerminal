package com.claudetabs

/**
 * A tiny, dependency-free JSON reader/writer, used for one job: editing the user's
 * `~/.claude/settings.json` in place to register hooks and permissions.
 *
 * The plugin ships with no third-party dependencies, and the rest of the codebase gets by
 * on regex extraction because it only ever *reads* single flat fields out of files it wrote
 * itself. `settings.json` is different — it is the user's file, arbitrarily shaped, and we
 * have to add nested structure to it. Regex surgery on that produces invalid JSON as soon
 * as the file's shape differs from the one the pattern assumed, and an invalid
 * `settings.json` breaks Claude Code entirely. So: parse, edit the tree, re-serialise.
 *
 * Numbers are carried as their original literal text ([Num]) rather than being converted to
 * Double, so re-serialising a file the plugin didn't need to change leaves every value
 * byte-identical instead of turning `"timeout": 5` into `"timeout": 5.0`.
 *
 * Not a general-purpose JSON library: no streaming, no comments, no error recovery. It
 * parses what `JSON.stringify` emits, which is what Claude Code writes.
 */
internal object MiniJson {

    /** A JSON number, preserved verbatim so round-trips don't reformat untouched values. */
    data class Num(val raw: String) {
        override fun toString() = raw
    }

    class ParseException(message: String) : Exception(message)

    // ══════════════════════════════════════════════════════════════
    // PARSE
    // ══════════════════════════════════════════════════════════════

    /**
     * Parse [text] into `Map<String, Any?>` / `List<Any?>` / [String] / [Num] / [Boolean] / null.
     * Throws [ParseException] on malformed input — callers should treat that as
     * "leave the file alone".
     */
    fun parse(text: String): Any? {
        val p = Parser(text)
        p.skipWs()
        val v = p.readValue()
        p.skipWs()
        if (!p.atEnd()) throw ParseException("trailing content at offset ${p.pos}")
        return v
    }

    private class Parser(private val s: String) {
        var pos = 0

        fun atEnd() = pos >= s.length

        fun skipWs() {
            while (pos < s.length && s[pos].isWhitespace()) pos++
        }

        fun readValue(): Any? {
            if (atEnd()) throw ParseException("unexpected end of input")
            return when (val ch = s[pos]) {
                '{' -> readObject()
                '[' -> readArray()
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (ch == '-' || ch.isDigit()) readNumber()
                else throw ParseException("unexpected character '$ch' at offset $pos")
            }
        }

        private fun readLiteral(lit: String, value: Any?): Any? {
            if (!s.startsWith(lit, pos)) throw ParseException("expected $lit at offset $pos")
            pos += lit.length
            return value
        }

        private fun readNumber(): Num {
            val start = pos
            if (pos < s.length && s[pos] == '-') pos++
            while (pos < s.length && (s[pos].isDigit() || s[pos] in ".eE+-")) pos++
            val raw = s.substring(start, pos)
            if (raw.isEmpty()) throw ParseException("empty number at offset $start")
            return Num(raw)
        }

        fun readString(): String {
            if (atEnd()) throw ParseException("expected a string, reached end of input")
            if (s[pos] != '"') throw ParseException("expected '\"' at offset $pos")
            pos++
            val sb = StringBuilder()
            while (true) {
                if (atEnd()) throw ParseException("unterminated string")
                when (val ch = s[pos]) {
                    '"' -> { pos++; return sb.toString() }
                    '\\' -> {
                        pos++
                        if (atEnd()) throw ParseException("unterminated escape")
                        when (val esc = s[pos]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 >= s.length) throw ParseException("truncated \\u escape")
                                val hex = s.substring(pos + 1, pos + 5)
                                sb.append(hex.toInt(16).toChar())
                                pos += 4
                            }
                            else -> throw ParseException("bad escape '\\$esc' at offset $pos")
                        }
                        pos++
                    }
                    else -> { sb.append(ch); pos++ }
                }
            }
        }

        private fun readObject(): MutableMap<String, Any?> {
            pos++ // '{'
            val map = LinkedHashMap<String, Any?>()
            skipWs()
            if (!atEnd() && s[pos] == '}') { pos++; return map }
            while (true) {
                skipWs()
                val key = readString()
                skipWs()
                if (atEnd() || s[pos] != ':') throw ParseException("expected ':' at offset $pos")
                pos++
                skipWs()
                map[key] = readValue()
                skipWs()
                if (atEnd()) throw ParseException("unterminated object")
                when (s[pos]) {
                    ',' -> pos++
                    '}' -> { pos++; return map }
                    else -> throw ParseException("expected ',' or '}' at offset $pos")
                }
            }
        }

        private fun readArray(): MutableList<Any?> {
            pos++ // '['
            val list = mutableListOf<Any?>()
            skipWs()
            if (!atEnd() && s[pos] == ']') { pos++; return list }
            while (true) {
                skipWs()
                list.add(readValue())
                skipWs()
                if (atEnd()) throw ParseException("unterminated array")
                when (s[pos]) {
                    ',' -> pos++
                    ']' -> { pos++; return list }
                    else -> throw ParseException("expected ',' or ']' at offset $pos")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    // SERIALISE
    // ══════════════════════════════════════════════════════════════

    /** Render [value] with 2-space indentation, matching what Claude Code writes. */
    fun write(value: Any?): String = StringBuilder().also { render(value, it, 0) }.toString()

    private fun render(value: Any?, sb: StringBuilder, depth: Int) {
        val pad = "  ".repeat(depth)
        val padInner = "  ".repeat(depth + 1)
        when (value) {
            null -> sb.append("null")
            is Boolean -> sb.append(value.toString())
            is Num -> sb.append(value.raw)
            is Number -> sb.append(value.toString())
            is String -> writeString(value, sb)
            is Map<*, *> -> {
                if (value.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, v) in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padInner)
                    writeString(k.toString(), sb)
                    sb.append(": ")
                    render(v, sb, depth + 1)
                }
                sb.append("\n").append(pad).append("}")
            }
            is List<*> -> {
                if (value.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                var first = true
                for (v in value) {
                    if (!first) sb.append(",\n")
                    first = false
                    sb.append(padInner)
                    render(v, sb, depth + 1)
                }
                sb.append("\n").append(pad).append("]")
            }
            else -> writeString(value.toString(), sb)
        }
    }

    private fun writeString(s: String, sb: StringBuilder) {
        sb.append('"')
        for (ch in s) {
            when (ch) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                else -> if (ch < ' ') sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('"')
    }
}
