package local.acetv

import java.text.Normalizer

/** Escritura de JSON a mano (sin dependencias de Android). */
object JsonOut {
    fun str(s: String): String {
        val sb = StringBuilder(s.length + 2)
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }
}

/** Busqueda de canales: sin mayusculas ni tildes y con todas las palabras presentes. */
object Search {
    private val MARKS = Regex("\\p{Mn}+")
    private val SPACES = Regex("\\s+")

    fun normalize(s: String): String =
        MARKS.replace(Normalizer.normalize(s, Normalizer.Form.NFD), "").lowercase()

    /** Devuelve (total de coincidencias, primeras `limit`). Con consulta vacia, todos los canales. */
    fun filter(all: List<Channel>, query: String, limit: Int): Pair<Int, List<Channel>> {
        val words = normalize(query).split(SPACES).filter { it.isNotEmpty() }
        if (words.isEmpty()) return Pair(all.size, all.take(limit))
        val matched = all.filter { c ->
            val t = normalize(c.title + " " + c.group)
            words.all { t.contains(it) }
        }
        return Pair(matched.size, matched.take(limit))
    }
}

/** Comprobacion de que una direccion es un motor Acestream. */
object EngineCheck {
    private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")
    private val HOST = Regex("^[A-Za-z0-9]([A-Za-z0-9.-]{0,251}[A-Za-z0-9])?$")
    private val VERSION = Regex("\"version\"\\s*:\\s*\"([^\"]*)\"")

    fun validHost(h: String): Boolean {
        if (IPV4.matches(h)) return h.split(".").all { it.toInt() in 0..255 }
        return HOST.matches(h)
    }

    /** `get` devuelve el cuerpo de la URL (lanza si no hay conexion). Resultado: (es motor, mensaje). */
    fun check(host: String, get: (String) -> String): Pair<Boolean, String> {
        val base = "http://$host:6878"
        try {
            val body = get("$base/webui/api/service?method=get_version&format=jsonp&callback=x")
            val m = VERSION.find(body)
            if (m != null) return Pair(true, "Motor Acestream " + m.groupValues[1] + " en " + host)
        } catch (e: Exception) {
            // se prueba la segunda comprobacion
        }
        return try {
            val body = get("$base/ace/getstream?format=json")
            if (body.contains("\"response\"") && body.contains("\"error\"")) {
                Pair(true, "Motor Acestream en $host")
            } else {
                Pair(false, "La respuesta de $host no parece la de un motor Acestream")
            }
        } catch (e: Exception) {
            Pair(false, "No se pudo conectar con $host: " + (e.message ?: "sin respuesta"))
        }
    }
}
