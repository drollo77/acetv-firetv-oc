package local.acetv

/** Lector JSON minimo (sin dependencias de Android) para listas de canales. */
object MiniJson {
    class JsonError(message: String) : Exception(message)

    /** Devuelve Map<String, Any?>, List<Any?>, String, Long, Double, Boolean o null. */
    fun parse(text: String): Any? {
        val p = Parser(text.removePrefix("\uFEFF"))
        val v = p.value()
        p.skipWs()
        if (p.pos() < p.length()) throw JsonError("texto sobrante (posicion ${p.pos()})")
        return v
    }

    private class Parser(private val s: String) {
        private var i = 0

        fun pos() = i
        fun length() = s.length

        fun skipWs() {
            while (i < s.length && s[i].isWhitespace()) i++
        }

        private fun fail(msg: String): Nothing = throw JsonError("$msg (posicion $i)")

        private fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun value(): Any? {
            skipWs()
            if (i >= s.length) fail("fin inesperado")
            val c = s[i]
            return when {
                c == '{' -> obj()
                c == '[' -> arr()
                c == '"' -> str()
                c == 't' -> lit("true", true)
                c == 'f' -> lit("false", false)
                c == 'n' -> lit("null", null)
                c == '-' || c.isDigit() -> num()
                else -> fail("caracter inesperado '$c'")
            }
        }

        private fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) fail("valor no valido")
            i += word.length
            return v
        }

        private fun num(): Any {
            val st = i
            while (i < s.length && (s[i].isDigit() || s[i] == '+' || s[i] == '-' || s[i] == '.' || s[i] == 'e' || s[i] == 'E')) i++
            val t = s.substring(st, i)
            val isFloat = t.any { it == '.' || it == 'e' || it == 'E' }
            if (!isFloat) {
                val l = t.toLongOrNull()
                if (l != null) return l
            }
            return t.toDoubleOrNull() ?: fail("numero no valido")
        }

        private fun str(): String {
            i++ // comilla inicial
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) fail("cadena sin cerrar")
                val c = s[i++]
                if (c == '"') return sb.toString()
                if (c != '\\') {
                    sb.append(c)
                    continue
                }
                if (i >= s.length) fail("escape incompleto")
                val e = s[i++]
                when (e) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append('\u000C')
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        if (i + 4 > s.length) fail("escape unicode incompleto")
                        val code = s.substring(i, i + 4).toIntOrNull(16) ?: fail("escape unicode no valido")
                        sb.append(code.toChar())
                        i += 4
                    }
                    else -> fail("escape no valido")
                }
            }
        }

        private fun arr(): List<Any?> {
            i++ // [
            val out = ArrayList<Any?>()
            skipWs()
            if (peek() == ']') {
                i++
                return out
            }
            while (true) {
                out.add(value())
                skipWs()
                when (peek()) {
                    ',' -> i++
                    ']' -> {
                        i++
                        return out
                    }
                    else -> fail("se esperaba ',' o ']'")
                }
            }
        }

        private fun obj(): Map<String, Any?> {
            i++ // {
            val out = LinkedHashMap<String, Any?>()
            skipWs()
            if (peek() == '}') {
                i++
                return out
            }
            while (true) {
                skipWs()
                if (peek() != '"') fail("se esperaba una clave")
                val k = str()
                skipWs()
                if (peek() != ':') fail("se esperaba ':'")
                i++
                out[k] = value()
                skipWs()
                when (peek()) {
                    ',' -> i++
                    '}' -> {
                        i++
                        return out
                    }
                    else -> fail("se esperaba ',' o '}'")
                }
            }
        }
    }
}
