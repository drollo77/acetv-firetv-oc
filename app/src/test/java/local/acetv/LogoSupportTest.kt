package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.Executors

class LogoSupportTest {
    private val png = ByteArray(300) { (it % 251).toByte() }

    // Servidor minimo con respuestas fijas por ruta (permite probar redirecciones y errores)
    private fun <T> withServer(f: (Int) -> T): T {
        val ss = ServerSocket(0)
        val pool = Executors.newCachedThreadPool()
        val t = Thread {
            while (!ss.isClosed) {
                try {
                    val s = ss.accept()
                    pool.execute {
                        s.use { sock ->
                            val inp = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                            val first = inp.readLine() ?: return@use
                            while (true) {
                                val l = inp.readLine() ?: break
                                if (l.isEmpty()) break
                            }
                            val path = first.split(" ")[1]
                            val out = sock.getOutputStream()
                            fun send(head: String, body: ByteArray = ByteArray(0)) {
                                out.write((head + "Content-Length: " + body.size + "\r\nConnection: close\r\n\r\n").toByteArray())
                                out.write(body)
                                out.flush()
                            }
                            when (path) {
                                "/ok" -> send("HTTP/1.1 200 OK\r\nContent-Type: image/png\r\n", png)
                                "/redir" -> send("HTTP/1.1 302 Found\r\nLocation: /ok\r\n")
                                "/redir2" -> send("HTTP/1.1 301 Moved\r\nLocation: /redir\r\n")
                                "/loop" -> send("HTTP/1.1 302 Found\r\nLocation: /loop\r\n")
                                "/big" -> send("HTTP/1.1 200 OK\r\n", ByteArray(5000))
                                "/vacio" -> send("HTTP/1.1 200 OK\r\n")
                                else -> send("HTTP/1.1 404 Not Found\r\n", "no".toByteArray())
                            }
                        }
                    }
                } catch (e: Exception) {
                    break
                }
            }
        }
        t.isDaemon = true
        t.start()
        try {
            return f(ss.localPort)
        } finally {
            ss.close()
            pool.shutdownNow()
        }
    }

    @Test
    fun descargaYRedirecciones() = withServer { port ->
        val base = "http://127.0.0.1:$port"
        assertEquals(png.toList(), LogoFetch.fetch("$base/ok", 1000, 3000)?.toList())
        assertEquals(png.toList(), LogoFetch.fetch("$base/redir", 1000, 3000)?.toList())
        assertEquals(png.toList(), LogoFetch.fetch("$base/redir2", 1000, 3000)?.toList())
    }

    @Test
    fun erroresYLimites() = withServer { port ->
        val base = "http://127.0.0.1:$port"
        assertNull(LogoFetch.fetch("$base/nada", 1000, 3000))
        assertNull(LogoFetch.fetch("$base/loop", 1000, 3000))
        assertNull(LogoFetch.fetch("$base/big", 1000, 3000))
        assertNotNull(LogoFetch.fetch("$base/big", 6000, 3000))
        assertNull(LogoFetch.fetch("$base/vacio", 1000, 3000))
        assertNull(LogoFetch.fetch("no es una url", 1000, 3000))
        assertNull(LogoFetch.fetch("http://127.0.0.1:1/x", 1000, 1000))
    }

    @Test
    fun nombreDeFicheroEstable() {
        val a = LogoCache.fileName("https://i.ibb.co/abc/logo.png")
        assertEquals(40, a.length)
        assertEquals(a, LogoCache.fileName("https://i.ibb.co/abc/logo.png"))
        assertTrue(a != LogoCache.fileName("https://i.ibb.co/abc/logo2.png"))
        assertEquals("da39a3ee5e6b4b0d3255bfef95601890afd80709", LogoCache.fileName(""))
    }

    @Test
    fun tamanoDeDecodificacion() {
        assertEquals(1, LogoCache.sampleSize(100, 50, 96, 54))
        assertEquals(1, LogoCache.sampleSize(150, 80, 96, 54))
        assertEquals(4, LogoCache.sampleSize(400, 220, 96, 54))
        assertEquals(16, LogoCache.sampleSize(1600, 900, 96, 54))
        assertEquals(1, LogoCache.sampleSize(0, 0, 96, 54))
        assertEquals(1f, LogoCache.fitScale(50, 30, 96, 54), 0.0001f)
        assertEquals(0.5f, LogoCache.fitScale(192, 100, 96, 54), 0.0001f)
        assertEquals(0.54f, LogoCache.fitScale(100, 100, 96, 54), 0.0001f)
    }

    @Test
    fun borradoDeLosMasAntiguos() {
        val e = listOf(
            LogoCache.Entry("c", 400, 300),
            LogoCache.Entry("a", 400, 100),
            LogoCache.Entry("b", 400, 200)
        )
        assertEquals(emptyList<String>(), LogoCache.toEvict(e, 1200))
        assertEquals(listOf("a"), LogoCache.toEvict(e, 800))
        assertEquals(listOf("a", "b"), LogoCache.toEvict(e, 500))
        assertEquals(listOf("a", "b", "c"), LogoCache.toEvict(e, 0))
    }
}
