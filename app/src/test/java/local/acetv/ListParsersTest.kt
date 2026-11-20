package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ListParsersTest {
    private val h1 = "0123456789abcdef0123456789abcdef01234567"
    private val h2 = "fedcba9876543210fedcba9876543210fedcba98"
    private val h3 = "aaaaaaaaaabbbbbbbbbbccccccccccdddddddddd"

    private fun falla(f: () -> Unit): Boolean = try { f(); false } catch (e: Exception) { true }

    @Test
    fun jsonBasico() {
        assertEquals(1L, (MiniJson.parse("""{"a":1,"b":[true,false,null]}""") as Map<*, *>)["a"])
        assertEquals(listOf(1.5, -2000.0), MiniJson.parse("[1.5, -2e3]"))
        assertEquals("a\"b\\c\n\u00f1/", MiniJson.parse(""""a\"b\\c\n\u00f1\/""""))
        assertEquals(listOf(1L), MiniJson.parse("\uFEFF[1]"))
        assertTrue(falla { MiniJson.parse("[1,]") })
        assertTrue(falla { MiniJson.parse("{\"a\":1") })
        assertTrue(falla { MiniJson.parse("[1] x") })
        assertTrue(falla { MiniJson.parse("\"abc") })
    }

    @Test
    fun parseJsonDeListas() {
        assertEquals(1, ListParsers.parseJson("""[{"hash":"${"a".repeat(40)}","title":"T"}]""").size)
        assertTrue(ListParsers.parseJson("""{"hashes":[{"hash":"zz"},{"title":"x"}]}""").isEmpty())
        val c = ListParsers.parseJson("""{"hashes":[{"hash":"${"A".repeat(40)}","title":"La 1 --> ORIGEN ...abc"}]}""")[0]
        assertEquals("a".repeat(40), c.hash)
        assertEquals("La 1", c.title)
        assertEquals("ORIGEN ...abc", c.origin)
        assertEquals("Sin grupo", c.group)
        assertTrue(falla { ListParsers.parseJson("""{"x":[]}""") })
        assertTrue(falla { ListParsers.parseJson("42") })
    }

    @Test
    fun hashDeUrls() {
        assertEquals(h1, ListParsers.hashFrom("acestream://$h1"))
        assertEquals(h1, ListParsers.hashFrom("http://127.0.0.1:6878/ace/getstream?id=$h1"))
        assertEquals(h1, ListParsers.hashFrom("http://x/ace/getstream?format=json&id=${h1.uppercase()}"))
        assertEquals(h1, ListParsers.hashFrom("  $h1  "))
        assertNull(ListParsers.hashFrom("http://x/video.ts"))
        assertNull(ListParsers.hashFrom("acestream://123"))
    }

    @Test
    fun m3u() {
        val txt = "\uFEFF#EXTM3U\r\n" +
            "#EXTINF:-1 tvg-id=\"La1.es\" tvg-logo=\"http://l/logo.png\" group-title=\"TDT, NACIONALES\",La 1 HD --> ORIGEN UNO ...abc\r\n" +
            "acestream://$h1\r\n" +
            "\r\n" +
            "#EXTGRP:DEPORTES\r\n" +
            "#EXTINF:-1,DAZN 1\r\n" +
            "http://127.0.0.1:6878/ace/getstream?id=${h2.uppercase()}\r\n" +
            "#EXTINF:-1 group-title=\"X\",Sin enlace\r\n" +
            "http://servidor/video.ts\r\n" +
            "#EXTINF:-1,Otro\r\n" +
            "#EXTVLCOPT:network-caching=1000\r\n" +
            "$h3\r\n"
        val l = ListParsers.parseM3u(txt)
        assertEquals(3, l.size)
        assertEquals("TDT, NACIONALES", l[0].group)
        assertEquals("La 1 HD", l[0].title)
        assertEquals("ORIGEN UNO ...abc", l[0].origin)
        assertEquals("http://l/logo.png", l[0].logo)
        assertEquals("La1.es", l[0].tvgId)
        assertEquals(h1, l[0].hash)
        assertEquals("DEPORTES", l[1].group)
        assertEquals(h2, l[1].hash)
        assertEquals("DAZN 1", l[1].title)
        assertEquals(h3, l[2].hash)
        assertEquals("Otro", l[2].title)
        assertEquals("Sin grupo", l[2].group)
        assertTrue(ListParsers.parseM3u("").isEmpty())
        assertTrue(ListParsers.parseM3u("#EXTM3U\n").isEmpty())
    }

    @Test
    fun unionConservaLaPrimeraAparicion() {
        val j = listOf(Channel("A", h1, "G1"), Channel("B", h2, "G1"))
        val m = listOf(Channel("A2", h1, "G2"), Channel("C", h3, "G2"))
        val u = ListParsers.mergeUnique(j, m)
        assertEquals(listOf(h1, h2, h3), u.map { it.hash })
        assertEquals("A", u[0].title)
    }

    @Test
    fun listasRealesDelProyecto() {
        val j = File("../lista.json")
        assumeTrue(j.exists())
        val lj = ListParsers.parseJson(j.readText())
        assertTrue(lj.isNotEmpty())
        assertTrue(lj.all { it.hash.length == 40 })
        val m = File("../lista.m3u")
        if (m.exists()) {
            val lm = ListParsers.parseM3u(m.readText())
            assertTrue(lm.isNotEmpty())
            assertTrue(lm.all { it.hash.length == 40 })
        }
    }
}
