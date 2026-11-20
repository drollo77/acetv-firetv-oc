package local.acetv

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class HttpRequest(
    val method: String,
    val path: String,
    val query: Map<String, String>,
    val headers: Map<String, String>,
    val body: ByteArray
)

class HttpResponse(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, text: String) =
            HttpResponse(status, "application/json; charset=utf-8", text.toByteArray(Charsets.UTF_8))

        fun html(text: String) =
            HttpResponse(200, "text/html; charset=utf-8", text.toByteArray(Charsets.UTF_8))
    }
}

class HttpError(val status: Int, message: String) : Exception(message)

/** Servidor HTTP/1.1 minimo (una peticion por conexion), sin dependencias de Android. */
class MiniHttpServer(
    private val port: Int,
    private val maxBody: Int,
    private val handler: (HttpRequest) -> HttpResponse
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false

    /** Puerto realmente abierto (util con port = 0). */
    val boundPort: Int get() = server?.localPort ?: -1

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port))
        server = ss
        pool = Executors.newFixedThreadPool(4)
        running = true
        val t = Thread({ acceptLoop(ss) }, "acetv-http")
        t.isDaemon = true
        acceptThread = t
        t.start()
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (e: IOException) {
            // nada que hacer
        }
        pool?.shutdownNow()
        // espera a que el hilo de aceptacion termine: asi el puerto queda cerrado al volver de stop()
        try {
            acceptThread?.join(2000)
        } catch (e: InterruptedException) {
            // seguimos
        }
        acceptThread = null
        server = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            try {
                val s = ss.accept()
                pool?.execute { serve(s) }
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    private fun serve(sock: Socket) {
        try {
            sock.use {
                it.soTimeout = 10000
                var rejected = false
                val resp = try {
                    handler(readRequest(BufferedInputStream(it.getInputStream())))
                } catch (e: HttpError) {
                    rejected = true
                    HttpResponse.json(e.status, "{\"ok\":false,\"error\":" + JsonOut.str(e.message ?: "error") + "}")
                } catch (e: IOException) {
                    return
                } catch (e: Exception) {
                    HttpResponse.json(500, "{\"ok\":false,\"error\":\"error interno\"}")
                }
                write(it, resp)
                if (rejected) drain(it)
            }
        } catch (e: Exception) {
            // conexion cerrada por el cliente
        }
    }

    // Tras rechazar una peticion se vacia lo que el cliente siga enviando (hasta 1 MB) para que
    // cierre la conexion sin perder la respuesta de error.
    private fun drain(sock: Socket) {
        try {
            sock.shutdownOutput()
            sock.soTimeout = 1000
            val buf = ByteArray(8192)
            var total = 0
            val inp = sock.getInputStream()
            while (total < 1024 * 1024) {
                val n = inp.read(buf)
                if (n < 0) break
                total += n
            }
        } catch (e: Exception) {
            // el cliente ya cerro
        }
    }

    private fun readLine(inp: InputStream): String? {
        val sb = ByteArrayOutputStream()
        while (true) {
            val b = inp.read()
            if (b < 0) return if (sb.size() == 0) null else sb.toString("ISO-8859-1")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.write(b)
            if (sb.size() > 8192) throw HttpError(431, "cabecera demasiado larga")
        }
        return sb.toString("ISO-8859-1")
    }

    private fun dec(s: String): String =
        try {
            URLDecoder.decode(s, "UTF-8")
        } catch (e: Exception) {
            s
        }

    private fun readRequest(inp: InputStream): HttpRequest {
        val first = readLine(inp) ?: throw HttpError(400, "peticion vacia")
        val parts = first.split(" ")
        if (parts.size < 3 || !parts[2].startsWith("HTTP/")) throw HttpError(400, "peticion no valida")
        val method = parts[0].uppercase()
        val target = parts[1]

        val headers = HashMap<String, String>()
        var n = 0
        while (true) {
            val line = readLine(inp) ?: throw HttpError(400, "cabeceras incompletas")
            if (line.isEmpty()) break
            if (++n > 64) throw HttpError(431, "demasiadas cabeceras")
            val i = line.indexOf(':')
            if (i <= 0) throw HttpError(400, "cabecera no valida")
            headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }
        if (headers.containsKey("transfer-encoding")) throw HttpError(411, "se necesita Content-Length")
        val clh = headers["content-length"]
        val len = if (clh == null) 0 else (clh.toIntOrNull() ?: throw HttpError(400, "Content-Length no valido"))
        if (len < 0) throw HttpError(400, "Content-Length no valido")
        if (len > maxBody) throw HttpError(413, "cuerpo demasiado grande")
        val body = ByteArray(len)
        var off = 0
        while (off < len) {
            val r = inp.read(body, off, len - off)
            if (r < 0) throw HttpError(400, "cuerpo incompleto")
            off += r
        }

        val q = target.indexOf('?')
        val path = if (q < 0) target else target.substring(0, q)
        val query = HashMap<String, String>()
        if (q >= 0) {
            for (pair in target.substring(q + 1).split("&")) {
                if (pair.isEmpty()) continue
                val e = pair.indexOf('=')
                val k = if (e < 0) pair else pair.substring(0, e)
                val v = if (e < 0) "" else pair.substring(e + 1)
                query[dec(k)] = dec(v)
            }
        }
        return HttpRequest(method, path, query, headers, body)
    }

    private fun write(sock: Socket, r: HttpResponse) {
        val reason = when (r.status) {
            200 -> "OK"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            410 -> "Gone"
            411 -> "Length Required"
            413 -> "Payload Too Large"
            431 -> "Request Header Fields Too Large"
            else -> "Error"
        }
        val head = "HTTP/1.1 ${r.status} $reason\r\n" +
            "Content-Type: ${r.contentType}\r\n" +
            "Content-Length: ${r.body.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n"
        val out = sock.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(r.body)
        out.flush()
    }
}
