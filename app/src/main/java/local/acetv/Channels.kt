package local.acetv

data class Channel(
    val title: String,
    val hash: String,
    val group: String,
    val logo: String = "",
    val tvgId: String = "",
    val origin: String = ""
)

/** Logica pura (sin Android) de lista de canales, recientes y zapping. */
object ChannelLogic {
    const val RECENT_LABEL = "\u2605 Recientes"
    const val MAX_RECENTS = 8
    const val QR_LABEL = "+ Nuevo QR / ajustes"

    /** Descarta hashes no validos (no 40 caracteres) y repetidos, conservando el orden. */
    fun dedupe(list: List<Channel>): List<Channel> {
        val seen = HashSet<String>()
        return list.filter { it.hash.length == 40 && seen.add(it.hash) }
    }

    /** Grupos en orden de primera aparicion. */
    fun groupsOf(list: List<Channel>): List<String> = list.map { it.group }.distinct()

    fun inGroup(list: List<Channel>, group: String): List<Channel> = list.filter { it.group == group }

    /** Entradas de la columna de grupos: Recientes, los grupos de la lista y la de ajustes/QR. */
    fun groupLabels(groups: List<String>): List<String> = listOf(RECENT_LABEL) + groups + QR_LABEL

    fun parseRecents(s: String?): List<String> =
        (s ?: "").split(",").filter { it.length == 40 }

    fun joinRecents(l: List<String>): String = l.joinToString(",")

    /** Pone el hash el primero, sin repetirlo, con un maximo de MAX_RECENTS. */
    fun pushRecent(current: List<String>, hash: String): List<String> {
        val out = ArrayList<String>()
        out.add(hash)
        for (h in current) if (h != hash) out.add(h)
        return if (out.size > MAX_RECENTS) ArrayList(out.subList(0, MAX_RECENTS)) else out
    }

    /** Indice siguiente/anterior en circular; -1 si la lista esta vacia. */
    fun zapIndex(size: Int, currentIndex: Int, delta: Int): Int {
        if (size <= 0) return -1
        if (currentIndex < 0) return if (delta >= 0) 0 else size - 1
        return ((currentIndex + delta) % size + size) % size
    }

    /** Recientes como canales (ignora los hashes que ya no estan en la lista). */
    fun recentChannels(all: List<Channel>, recents: List<String>): List<Channel> {
        val byHash = HashMap<String, Channel>()
        for (c in all) byHash[c.hash] = c
        return recents.mapNotNull { byHash[it] }
    }
}
