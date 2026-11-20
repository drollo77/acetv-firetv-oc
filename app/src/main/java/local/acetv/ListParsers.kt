package local.acetv

/** Lectura de listas de canales en JSON y M3U (sin dependencias de Android). */
object ListParsers {
    private val URL_HASH = Regex("(?i)(?:acestream://|[?&](?:id|infohash|content_id)=)([0-9a-f]{40})")
    private val PLAIN_HASH = Regex("(?i)^[0-9a-f]{40}$")
    private const val NO_GROUP = "Sin grupo"
    private const val ORIGIN_SEP = " --> "

    /** Hash de 40 hex a partir de acestream://HASH, ...?id=HASH o el propio hash; null si no hay. */
    fun hashFrom(s: String): String? {
        val t = s.trim()
        if (PLAIN_HASH.matches(t)) return t.lowercase()
        val m = URL_HASH.find(t) ?: return null
        return m.groupValues[1].lowercase()
    }

    /** "Canal --> ORIGEN ..." pasa a (nombre, origen). */
    fun splitOrigin(title: String): Pair<String, String> {
        val i = title.indexOf(ORIGIN_SEP)
        if (i < 0) return Pair(title.trim(), "")
        return Pair(title.substring(0, i).trim(), title.substring(i + ORIGIN_SEP.length).trim())
    }

    private fun str(v: Any?): String = if (v is String) v.trim() else ""

    /** Lista JSON {generated,count,hashes:[{title,hash,group,logo,tvg_id}]} o una lista directa de entradas. */
    fun parseJson(text: String): List<Channel> {
        val root = MiniJson.parse(text)
        val items: List<*> = when (root) {
            is Map<*, *> -> (root["hashes"] as? List<*>) ?: throw IllegalArgumentException("falta la lista 'hashes'")
            is List<*> -> root
            else -> throw IllegalArgumentException("no es una lista de canales")
        }
        val out = ArrayList<Channel>()
        for (item in items) {
            val m = item as? Map<*, *> ?: continue
            val hash = hashFrom(str(m["hash"])) ?: continue
            val (name, origin0) = splitOrigin(str(m["title"]))
            out.add(
                Channel(
                    name.ifBlank { hash },
                    hash,
                    str(m["group"]).ifBlank { NO_GROUP },
                    str(m["logo"]),
                    str(m["tvg_id"]),
                    if (origin0.isNotEmpty()) origin0 else str(m["origin"])
                )
            )
        }
        return out
    }

    /** Serializa una lista al formato JSON de las listas ({count,hashes:[...]}). */
    fun toJson(list: List<Channel>): String {
        val sb = StringBuilder()
        sb.append("{\"count\":").append(list.size).append(",\"hashes\":[")
        for ((i, c) in list.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append("{\"title\":").append(JsonOut.str(c.title))
            sb.append(",\"hash\":").append(JsonOut.str(c.hash))
            sb.append(",\"group\":").append(JsonOut.str(c.group))
            sb.append(",\"logo\":").append(JsonOut.str(c.logo))
            sb.append(",\"tvg_id\":").append(JsonOut.str(c.tvgId))
            sb.append(",\"origin\":").append(JsonOut.str(c.origin)).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    /** Separa "#EXTINF:-1 a=\"b\",Titulo" en (atributos, titulo) por la primera coma fuera de comillas. */
    private fun splitExtinf(line: String): Pair<String, String> {
        val colon = line.indexOf(':')
        val rest = if (colon >= 0) line.substring(colon + 1) else ""
        var inQuotes = false
        for (i in rest.indices) {
            val c = rest[i]
            if (c == '"') inQuotes = !inQuotes
            else if (c == ',' && !inQuotes) return Pair(rest.substring(0, i), rest.substring(i + 1).trim())
        }
        return Pair(rest, "")
    }

    private fun attr(attrs: String, key: String): String {
        val m = Regex("(?i)(?:^|\\s)" + Regex.escape(key) + "\\s*=\\s*\"([^\"]*)\"").find(attrs)
        return m?.groupValues?.get(1)?.trim() ?: ""
    }

    /** Lista M3U/M3U8 con entradas acestream://HASH o URL con ?id=HASH. */
    fun parseM3u(text: String): List<Channel> {
        val out = ArrayList<Channel>()
        var title = ""
        var group = ""
        var logo = ""
        var tvg = ""
        var extGrp = ""
        for (raw in text.removePrefix("\uFEFF").lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF", ignoreCase = true)) {
                val (attrs, name) = splitExtinf(line)
                title = name
                group = attr(attrs, "group-title")
                logo = attr(attrs, "tvg-logo")
                tvg = attr(attrs, "tvg-id")
            } else if (line.startsWith("#EXTGRP:", ignoreCase = true)) {
                extGrp = line.substring(8).trim()
            } else if (line.startsWith("#")) {
                continue
            } else {
                val hash = hashFrom(line)
                if (hash != null) {
                    val (name, origin) = splitOrigin(title)
                    out.add(
                        Channel(
                            name.ifBlank { hash },
                            hash,
                            group.ifBlank { extGrp }.ifBlank { NO_GROUP },
                            logo,
                            tvg,
                            origin
                        )
                    )
                }
                title = ""
                group = ""
                logo = ""
                tvg = ""
                extGrp = ""
            }
        }
        return out
    }

    /** Une listas conservando el orden y quedandose con la primera aparicion de cada hash. */
    fun mergeUnique(vararg lists: List<Channel>): List<Channel> =
        ChannelLogic.dedupe(lists.toList().flatten())
}
