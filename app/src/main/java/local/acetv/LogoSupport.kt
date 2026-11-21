package local.acetv

import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Descarga de logos (sin dependencias de Android): sigue redirecciones y limita el tamano. */
object LogoFetch {
    private const val MAX_REDIRECTS = 4

    /** Bytes de la URL, o null si falla, no es un 200 o supera maxBytes. */
    fun fetch(url: String, maxBytes: Int, timeoutMs: Int, onError: (String) -> Unit = {}): ByteArray? {
        var u = url
        for (i in 0..MAX_REDIRECTS) {
            val c = try {
                URL(u).openConnection() as HttpURLConnection
            } catch (e: Exception) {
                onError("URL no valida")
                return null
            }
            c.instanceFollowRedirects = false
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 5.1) AceTV")
            c.setRequestProperty("Accept", "image/*")
            try {
                val st = c.responseCode
                if (st == 301 || st == 302 || st == 303 || st == 307 || st == 308) {
                    val loc = c.getHeaderField("Location")
                    if (loc == null) {
                        onError("redireccion sin destino")
                        return null
                    }
                    u = URL(URL(u), loc).toString()
                    continue
                }
                if (st != 200) {
                    onError("HTTP $st")
                    return null
                }
                if (c.contentLength > maxBytes) {
                    onError("demasiado grande")
                    return null
                }
                val out = ByteArrayOutputStream()
                val buf = ByteArray(8192)
                c.inputStream.use { ins ->
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        if (out.size() > maxBytes) {
                            onError("demasiado grande")
                            return null
                        }
                    }
                }
                if (out.size() == 0) {
                    onError("respuesta vacia")
                    return null
                }
                return out.toByteArray()
            } catch (e: Exception) {
                onError(e.javaClass.simpleName + ": " + (e.message ?: ""))
                return null
            } finally {
                c.disconnect()
            }
        }
        onError("demasiadas redirecciones")
        return null
    }
}

/** Utilidades de la cache de logos (sin dependencias de Android). */
object LogoCache {
    class Entry(val name: String, val size: Long, val modified: Long)

    /** Nombre de fichero estable para una URL (SHA-1 en hexadecimal). */
    fun fileName(url: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(url.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { String.format("%02x", it) }
    }

    /** Potencia de dos para decodificar una imagen w x h sin bajar de maxW x maxH. */
    fun sampleSize(w: Int, h: Int, maxW: Int, maxH: Int): Int {
        if (w <= 0 || h <= 0) return 1
        var s = 1
        while (w / (s * 2) >= maxW && h / (s * 2) >= maxH) s *= 2
        return s
    }

    /** Escala (<= 1) para que w x h quepa en maxW x maxH conservando la proporcion. */
    fun fitScale(w: Int, h: Int, maxW: Int, maxH: Int): Float {
        if (w <= 0 || h <= 0) return 1f
        return minOf(1f, minOf(maxW.toFloat() / w, maxH.toFloat() / h))
    }

    /** Ficheros a borrar (los mas antiguos primero) para que el total no supere maxBytes. */
    fun toEvict(entries: List<Entry>, maxBytes: Long): List<String> {
        var total = entries.sumOf { it.size }
        val out = ArrayList<String>()
        for (e in entries.sortedBy { it.modified }) {
            if (total <= maxBytes) break
            out.add(e.name)
            total -= e.size
        }
        return out
    }
}
