package local.acetv

import java.text.Normalizer

/** Una fuente (hash) de un canal: calidad/origen y resolucion para ordenarlas. */
data class Source(val hash: String, val label: String, val resolution: Int)

/** Un canal de la lista: varias fuentes del mismo canal fusionadas en una sola fila. */
data class Row(
    val key: String,
    val name: String,
    val group: String,
    val logo: String,
    val tvgId: String,
    val sources: List<Source>
) {
    /** Hash de la primera fuente (la de mayor resolucion). */
    val hash: String get() = sources[0].hash
}

/** Fusion de entradas con el mismo canal (mismo nombre sin calidad ni notas, mismo pais). */
object ChannelMerge {
    private val COUNTRY = Regex("\\[([A-Za-z]{2,3})\\]")
    private val NOTES = Regex("\\([^)]*\\)")
    private val QUALITY = Regex("(?i)\\b(?:4k|uhd|fhd|hd|sd|2160p|1080p|1080i|720p|576p|480p|360p)\\b")
    private val SPAIN = Regex("(?i)\\bspain\\b")
    private val STARS = Regex("\\*+")
    private val SPACES = Regex("\\s+")
    private val MARKS = Regex("\\p{Mn}+")
    private val MOVISTAR_PLUS = Regex("movistar\\s*plus\\s*\\+?")
    private val MOVISTAR_PLUS_SIGN = Regex("movistar\\s*\\+")
    private val M_PLUS = Regex("\\bm\\s*\\+")
    private val M_DOT = Regex("\\bm\\.\\s")
    private val MOVISTAR_WORD = Regex("^movistar\\s+")
    private val LA_TVE = Regex("\\bla\\s*([12])\\s+tve\\b")
    private val LA_LIGA = Regex("\\bla\\s+liga\\b")

    /** Codigo de pais entre corchetes, p. ej. "[ES]" -> "ES"; "" si no hay. */
    fun country(title: String): String = COUNTRY.find(title)?.groupValues?.get(1)?.uppercase() ?: ""

    /** Nombre limpio para mostrar: sin pais, notas entre parentesis, calidad, "Spain" ni asteriscos. */
    fun displayName(title: String): String {
        var t = COUNTRY.replace(title, " ")
        t = NOTES.replace(t, " ")
        t = QUALITY.replace(t, " ")
        t = SPAIN.replace(t, " ")
        t = STARS.replace(t, " ")
        val out = SPACES.replace(t, " ").trim()
        return if (out.isEmpty()) title.trim() else out
    }

    /** Clave de fusion: pais + nombre normalizado (sin tildes, mayusculas, espacios ni signos salvo '+'). */
    fun key(title: String): String {
        var n = Normalizer.normalize(displayName(title), Normalizer.Form.NFD)
        n = MARKS.replace(n, "").lowercase()
        n = MOVISTAR_PLUS.replace(n, "m+ ")
        n = MOVISTAR_PLUS_SIGN.replace(n, "m+ ")
        n = M_DOT.replace(n, "m+ ")
        n = MOVISTAR_WORD.replace(n, "m+ ")
        n = M_PLUS.replace(n, "m+")
        n = LA_LIGA.replace(n, "laliga")
        n = LA_TVE.replace(n, "la$1")
        val compact = n.filter { it.isLetterOrDigit() || it == '+' }
        return country(title) + "|" + compact
    }

    /** Mayor resolucion indicada en el titulo (4K=2160, FHD/1080p=1080, HD/720p=720...); 0 si no dice. */
    fun resolution(title: String): Int {
        var best = 0
        for (m in QUALITY.findAll(title)) {
            val r = when (m.value.lowercase()) {
                "4k", "uhd", "2160p" -> 2160
                "fhd", "1080p", "1080i" -> 1080
                "hd", "720p" -> 720
                "576p" -> 576
                "sd", "480p" -> 480
                "360p" -> 360
                else -> 0
            }
            if (r > best) best = r
        }
        return best
    }

    /** Etiqueta de la fuente: calidad y asteriscos del titulo, mas el origen si lo hay. */
    fun sourceLabel(title: String, origin: String): String {
        val q = QUALITY.findAll(title).joinToString(" ") { it.value }
        val stars = STARS.findAll(title).joinToString("") { it.value }
        var s = (q + " " + stars).trim()
        if (s.isEmpty()) s = "fuente"
        if (origin.isNotEmpty()) s += " \u00b7 " + origin.take(24)
        return s
    }

    /**
     * Agrupa las entradas por canal. Las filas salen en orden de primera aparicion, con el grupo de la primera
     * entrada, y las fuentes de cada una ordenadas por resolucion (de mayor a menor, estable).
     */
    fun merge(list: List<Channel>): List<Row> {
        val order = ArrayList<String>()
        val first = HashMap<String, Channel>()
        val sources = HashMap<String, MutableList<Source>>()
        val logos = HashMap<String, String>()
        val tvgs = HashMap<String, String>()
        for (c in list) {
            val k = key(c.title)
            if (!first.containsKey(k)) {
                first[k] = c
                order.add(k)
                sources[k] = ArrayList()
            }
            sources[k]!!.add(Source(c.hash, sourceLabel(c.title, c.origin), resolution(c.title)))
            if (c.logo.isNotEmpty() && !logos.containsKey(k)) logos[k] = c.logo
            if (c.tvgId.isNotEmpty() && !tvgs.containsKey(k)) tvgs[k] = c.tvgId
        }
        return order.map { k ->
            val f = first[k]!!
            val cc = country(f.title)
            val name = displayName(f.title) + if (cc.isNotEmpty()) " [$cc]" else ""
            Row(k, name, f.group, logos[k] ?: "", tvgs[k] ?: "", sources[k]!!.sortedByDescending { it.resolution })
        }
    }

    /** Cada hash apunta a la fila que lo contiene. */
    fun byHash(rows: List<Row>): Map<String, Row> {
        val m = HashMap<String, Row>()
        for (r in rows) for (s in r.sources) m[s.hash] = r
        return m
    }

    fun groupsOf(rows: List<Row>): List<String> = rows.map { it.group }.distinct()

    /** Filas de los hashes recientes (el mas reciente primero), sin repetir fila ni usar hashes que ya no existen. */
    fun recentRows(rows: List<Row>, hashes: List<String>): List<Row> {
        val m = byHash(rows)
        val seen = HashSet<String>()
        val out = ArrayList<Row>()
        for (h in hashes) {
            val r = m[h] ?: continue
            if (seen.add(r.key)) out.add(r)
        }
        return out
    }

    /** Quita de los hashes recientes todos los que pertenecen a la fila `key`. */
    fun removeRecent(rows: List<Row>, hashes: List<String>, key: String): List<String> {
        val m = byHash(rows)
        return hashes.filter { m[it]?.key != key }
    }

    /** Fila de un ID escrito a mano, que no esta en la lista. */
    fun manualRow(hash: String, name: String = "ID manual"): Row =
        Row(hash, name, "", "", "", listOf(Source(hash, "", 0)))
}
