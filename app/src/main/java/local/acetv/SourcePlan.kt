package local.acetv

/**
 * Orden de intentos y reintentos de las fuentes de un canal: empieza por la que funciono la ultima vez, luego
 * las demas de mayor a menor resolucion (las que superan el limite del dispositivo, al final).
 * Cada fuente tiene 1 reintento (3 la ultima) antes de pasar a la siguiente.
 */
class SourcePlan(sources: List<Source>, preferredHash: String?, maxResolution: Int) {
    enum class Decision { RETRY, NEXT, GIVE_UP }

    private val attempts: List<Source>
    var index = 0
        private set
    private var retries = 0

    val size: Int get() = attempts.size

    init {
        val within = sources.filter { it.resolution <= maxResolution }.sortedByDescending { it.resolution }
        val over = sources.filter { it.resolution > maxResolution }.sortedByDescending { it.resolution }
        val order = within + over
        val pref = if (preferredHash == null) null else order.firstOrNull { it.hash == preferredHash }
        attempts = if (pref != null) listOf(pref) + order.filter { it.hash != pref.hash } else order
    }

    fun current(): Source = attempts[index]

    fun isLast(): Boolean = index >= attempts.size - 1

    fun onSuccess() {
        retries = 0
    }

    /** Que hacer tras un fallo de la fuente actual. */
    fun onFailure(): Decision {
        val limit = if (isLast()) 3 else 1
        if (retries < limit) {
            retries++
            return Decision.RETRY
        }
        if (!isLast()) {
            index++
            retries = 0
            return Decision.NEXT
        }
        return Decision.GIVE_UP
    }

    /** Cambio manual de fuente (circular). */
    fun jump(delta: Int) {
        index = ((index + delta) % size + size) % size
        retries = 0
    }

    /** Tiempo maximo para arrancar: 45 s, o 25 s si quedan fuentes por probar. */
    fun loadTimeoutMs(): Long = if (isLast()) 45000L else 25000L

    fun labels(): List<String> = attempts.map { it.label }
}
