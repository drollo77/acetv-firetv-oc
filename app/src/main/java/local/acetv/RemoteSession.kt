package local.acetv

import java.security.SecureRandom

/**
 * Sesion de emparejado por QR: un codigo aleatorio que caduca tras ttlMs sin actividad
 * y se bloquea tras maxBad intentos con un codigo erroneo.
 */
class RemoteSession(
    private val ttlMs: Long = 5 * 60 * 1000L,
    private val maxBad: Int = 30,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val newCode: () -> String = { randomCode() }
) {
    enum class Result { OK, BAD, EXPIRED, LOCKED }

    var code: String = newCode()
        private set
    private var lastActivity = clock()
    private var bad = 0
    private var locked = false

    @Synchronized
    fun renew(): String {
        code = newCode()
        lastActivity = clock()
        bad = 0
        locked = false
        return code
    }

    @Synchronized
    fun check(candidate: String): Result {
        if (locked) return Result.LOCKED
        if (candidate != code) {
            bad++
            if (bad >= maxBad) {
                locked = true
                return Result.LOCKED
            }
            return Result.BAD
        }
        val now = clock()
        if (now - lastActivity > ttlMs) return Result.EXPIRED
        lastActivity = now
        return Result.OK
    }

    /** Estado actual de la sesion, sin contar como intento ni renovar la actividad. */
    @Synchronized
    fun status(): Result {
        if (locked) return Result.LOCKED
        return if (clock() - lastActivity > ttlMs) Result.EXPIRED else Result.OK
    }

    @Synchronized
    fun secondsLeft(): Long {
        if (locked) return 0
        return maxOf(0L, (ttlMs - (clock() - lastActivity)) / 1000)
    }

    companion object {
        fun randomCode(): String {
            val b = ByteArray(4)
            SecureRandom().nextBytes(b)
            return b.joinToString("") { String.format("%02x", it) }
        }
    }
}
