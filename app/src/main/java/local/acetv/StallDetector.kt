package local.acetv

/** Detecta un directo parado: la posicion de reproduccion no avanza durante limitMs. */
class StallDetector(private val limitMs: Long) {
    private var lastPos = Long.MIN_VALUE
    private var lastChange = 0L

    fun reset() {
        lastPos = Long.MIN_VALUE
        lastChange = 0L
    }

    /** True si la posicion lleva limitMs sin cambiar. */
    fun update(positionMs: Long, nowMs: Long): Boolean {
        if (lastPos == Long.MIN_VALUE || positionMs != lastPos) {
            lastPos = positionMs
            lastChange = nowMs
            return false
        }
        return nowMs - lastChange >= limitMs
    }
}
