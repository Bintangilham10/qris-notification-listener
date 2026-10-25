package id.ciganstore.qrislistener.upload

/**
 * A small strict JSON reader for the Worker's short responses. `org.json` would work on a phone but
 * is only a stub in JVM unit tests, and response handling must be unit tested.
 */
internal object MiniJson {
    private const val MAX_DEPTH = 16

    /** The top-level object, or null when [text] is not a single valid JSON object. */
    fun parseObject(text: String?): Map<String, Any?>? {
        if (text.isNullOrBlank()) return null
        return try {
            val reader = Reader(text)
            val value = reader.readValue(depth = 0)
            reader.skipWhitespace()
            if (!reader.isAtEnd()) return null
            @Suppress("UNCHECKED_CAST")
            value as? Map<String, Any?>
        } catch (error: MalformedJsonException) {
            null
        }
    }

    private class MalformedJsonException : Exception()

    private class Reader(private val text: String) {
        private var index = 0

        fun isAtEnd(): Boolean = index >= text.length

        fun skipWhitespace() {
            while (index < text.length && isWhitespace(text[index])) index++
        }

        fun readValue(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw MalformedJsonException()
            skipWhitespace()
            if (isAtEnd()) throw MalformedJsonException()
            return when (val char = text[index]) {
                '{' -> readObject(depth)
                '[' -> readArray(depth)
                '"' -> readString()
                't' -> readLiteral("true", true)
                'f' -> readLiteral("false", false)
                'n' -> readLiteral("null", null)
                else -> if (char == '-' || char in '0'..'9') readNumber() else throw MalformedJsonException()
            }
        }

        private fun readObject(depth: Int): Map<String, Any?> {
            index++ // {
            val result = LinkedHashMap<String, Any?>()
            skipWhitespace()
            if (peek() == '}') {
                index++
                return result
            }
            while (true) {
                skipWhitespace()
                if (peek() != '"') throw MalformedJsonException()
                val key = readString()
                skipWhitespace()
                if (next() != ':') throw MalformedJsonException()
                result[key] = readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> return result
                    else -> throw MalformedJsonException()
                }
            }
        }

        private fun readArray(depth: Int): List<Any?> {
            index++ // [
            val result = ArrayList<Any?>()
            skipWhitespace()
            if (peek() == ']') {
                index++
                return result
            }
            while (true) {
                result += readValue(depth + 1)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    ']' -> return result
                    else -> throw MalformedJsonException()
                }
            }
        }

        private fun readString(): String {
            index++ // opening quote
            val builder = StringBuilder()
            while (true) {
                val char = next()
                when {
                    char == '"' -> return builder.toString()
                    char == '\\' -> builder.append(readEscape())
                    char < ' ' -> throw MalformedJsonException()
                    else -> builder.append(char)
                }
            }
        }

        private fun readEscape(): Char = when (next()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> {
                if (index + 4 > text.length) throw MalformedJsonException()
                val digits = text.substring(index, index + 4)
                // toIntOrNull(16) alone would also accept a sign, such as "-001".
                if (!digits.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) throw MalformedJsonException()
                index += 4
                digits.toInt(16).toChar()
            }
            else -> throw MalformedJsonException()
        }

        private fun readNumber(): Double {
            val start = index
            while (index < text.length && isNumberChar(text[index])) index++
            return text.substring(start, index).toDoubleOrNull() ?: throw MalformedJsonException()
        }

        private fun readLiteral(literal: String, value: Any?): Any? {
            if (!text.startsWith(literal, index)) throw MalformedJsonException()
            index += literal.length
            return value
        }

        private fun peek(): Char? = if (index < text.length) text[index] else null

        private fun next(): Char {
            if (index >= text.length) throw MalformedJsonException()
            return text[index++]
        }

        private fun isWhitespace(char: Char): Boolean = char == ' ' || char == '\t' || char == '\n' || char == '\r'

        private fun isNumberChar(char: Char): Boolean =
            char in '0'..'9' || char == '-' || char == '+' || char == '.' || char == 'e' || char == 'E'
    }
}
