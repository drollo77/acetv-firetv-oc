package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChannelLogicTest {
    private fun h(n: Int) = n.toString().padStart(40, '0')
    private fun ch(n: Int, g: String) = Channel("canal$n", h(n), g)

    @Test
    fun dedupeQuitaRepetidosEInvalidos() {
        val c = ch(1, "G")
        val bad = Channel("x", "123", "G")
        assertEquals(listOf(c), ChannelLogic.dedupe(listOf(c, c, bad)))
    }

    @Test
    fun gruposEnOrdenDeAparicion() {
        val l = listOf(ch(1, "B"), ch(2, "A"), ch(3, "B"), ch(4, "C"))
        assertEquals(listOf("B", "A", "C"), ChannelLogic.groupsOf(l))
        assertEquals(2, ChannelLogic.inGroup(l, "B").size)
    }

    @Test
    fun recientesMaximoOchoYOrden() {
        var r: List<String> = emptyList()
        for (i in 1..10) r = ChannelLogic.pushRecent(r, h(i))
        assertEquals(8, r.size)
        assertEquals(h(10), r[0])
        assertEquals(h(3), r.last())
    }

    @Test
    fun recienteRepetidoSubeSinDuplicar() {
        var r: List<String> = emptyList()
        for (i in 1..5) r = ChannelLogic.pushRecent(r, h(i))
        r = ChannelLogic.pushRecent(r, h(2))
        assertEquals(h(2), r[0])
        assertEquals(5, r.size)
        assertEquals(1, r.count { it == h(2) })
    }

    @Test
    fun textoDeRecientesIdaYVuelta() {
        val r = listOf(h(1), h(2), h(3))
        assertEquals(r, ChannelLogic.parseRecents(ChannelLogic.joinRecents(r)))
        assertTrue(ChannelLogic.parseRecents(null).isEmpty())
        assertEquals(1, ChannelLogic.parseRecents("abc,,${h(9)}").size)
    }

    @Test
    fun recientesIgnoraHashesAusentes() {
        val all = listOf(ch(1, "G"), ch(2, "G"), ch(3, "G"))
        val rc = ChannelLogic.recentChannels(all, listOf(h(3), h(99), h(1)))
        assertEquals(listOf(all[2], all[0]), rc)
    }

    @Test
    fun zapping() {
        assertEquals(2, ChannelLogic.zapIndex(5, 1, 1))
        assertEquals(0, ChannelLogic.zapIndex(5, 1, -1))
        assertEquals(0, ChannelLogic.zapIndex(5, 4, 1))
        assertEquals(4, ChannelLogic.zapIndex(5, 0, -1))
        assertEquals(-1, ChannelLogic.zapIndex(0, -1, 1))
        assertEquals(0, ChannelLogic.zapIndex(5, -1, 1))
        assertEquals(4, ChannelLogic.zapIndex(5, -1, -1))
        assertEquals(0, ChannelLogic.zapIndex(1, 0, 1))
    }

    @Test
    fun variasPulsacionesSeguidas() {
        var i = 3
        repeat(3) { i = ChannelLogic.zapIndex(5, i, 1) }
        assertEquals(1, i)
        repeat(4) { i = ChannelLogic.zapIndex(5, i, -1) }
        assertEquals(2, i)
    }
}
