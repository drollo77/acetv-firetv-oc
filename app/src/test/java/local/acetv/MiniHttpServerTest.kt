package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL
import java.util.concurrent.Executors

class MiniHttpServerTest {
    private fun echo(req: HttpRequest) = HttpResponse.json(
        200,
        "{\"method\":" + JsonOut.str(req.method) + ",\"path\":" + JsonOut.str(req.path) +
            ",\"q\":" + JsonOut.str(req.query["q"] ?: "") + ",\"len\":" + req.body.size +
            ",\"h\":" + JsonOut.str(req.headers["x-prueba"] ?: "") + "}"
    )

    private fun <T> withServer(maxBody: Int, f: (Int) -> T): T {
        val s = MiniHttpServer(0, maxBody) { echo(it) }
        s.start()
        try {
            return f(s.boundPort)
        } finally {
            s.stop()
        }
    }

    private fun open(port: Int, path: String): HttpURLConnection {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000
        c.readTimeout = 5000
        return c
    }

    private fun bodyOf(c: HttpURLConnection): String {
        val st = if (c.responseCode >= 400) c.errorStream else c.inputStream
        return st.bufferedReader().use { it.readText() }
    }

    @Test
    fun getConParametrosCodificados() = withServer(1000) { port ->
        val c = open(port, "/s/abc/api?q=f%C3%BAtbol%20a&n=2")
        c.setRequestProperty("X-Prueba", "hola")
        assertEquals(200, c.responseCode)
        val r = MiniJson.parse(bodyOf(c)) as Map<*, *>
        assertEquals("GET", r["method"])
        assertEquals("/s/abc/api", r["path"])
        assertEquals("fútbol a", r["q"])
        assertEquals("hola", r["h"])
    }

    @Test
    fun postGrande() = withServer(8 * 1024 * 1024) { port ->
        val data = ByteArray(2 * 1024 * 1024) { 'x'.code.toByte() }
        val c = open(port, "/s/abc/up")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        assertEquals(200, c.responseCode)
        assertEquals(data.size.toLong(), (MiniJson.parse(bodyOf(c)) as Map<*, *>)["len"])
    }

    @Test
    fun cuerpoDemasiadoGrande() = withServer(1000) { port ->
        val data = ByteArray(5000)
        val c = open(port, "/s/abc/up")
        c.requestMethod = "POST"
        c.doOutput = true
        c.setFixedLengthStreamingMode(data.size)
        c.outputStream.use { it.write(data) }
        assertEquals(413, c.responseCode)
    }

    @Test
    fun peticionIncorrecta() = withServer(1000) { port ->
        Socket("127.0.0.1", port).use { s ->
            s.soTimeout = 5000
            s.getOutputStream().write("BASURA\r\n\r\n".toByteArray())
            s.getOutputStream().flush()
            val line = s.getInputStream().bufferedReader().readLine()
            assertTrue(line, line.startsWith("HTTP/1.1 400"))
        }
    }

    @Test
    fun variasPeticionesAlaVez() = withServer(1000) { port ->
        val pool = Executors.newFixedThreadPool(8)
        val futures = (1..24).map { pool.submit<Int> { open(port, "/s/abc/x$it").responseCode } }
        assertTrue(futures.all { it.get() == 200 })
        pool.shutdown()
    }

    @Test
    fun alPararYaNoAcepta() {
        val s = MiniHttpServer(0, 1000) { echo(it) }
        s.start()
        val port = s.boundPort
        assertEquals(200, open(port, "/s/abc/").responseCode)
        s.stop()
        var fallo = false
        try {
            Socket("127.0.0.1", port).use { }
        } catch (e: Exception) {
            fallo = true
        }
        assertTrue(fallo)
    }
}
