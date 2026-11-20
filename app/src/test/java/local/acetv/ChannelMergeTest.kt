package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class ChannelMergeTest {
    private fun h(n: Int) = n.toString().padStart(40, '0')
    private fun ch(n: Int, title: String, group: String = "G", origin: String = "") =
        Channel(title, h(n), group, "", "", origin)

    @Test
    fun nombreLimpio() {
        assertEquals("M+ Deportes", ChannelMerge.displayName("M+ Deportes 1080p **"))
        assertEquals("La 1 TVE", ChannelMerge.displayName("La 1 TVE 4K **"))
        assertEquals("Canal Uno", ChannelMerge.displayName("Canal Uno (SOLO EVENTOS) HD"))
        assertEquals("Canal", ChannelMerge.displayName("Canal Spain FHD [ES]"))
        assertEquals("Paramount+ UFC", ChannelMerge.displayName("Paramount+ UFC *"))
        assertEquals("1080p", ChannelMerge.displayName("1080p"))
    }

    @Test
    fun clavesQueSeUnifican() {
        val m = ChannelMerge.key("MOVISTAR PLUS 720p *")
        assertEquals(m, ChannelMerge.key("Movistar+ HD"))
        assertEquals(m, ChannelMerge.key("Movistar Plus+ HD"))
        assertEquals(m, ChannelMerge.key("M+ 1080p"))
        val liga = ChannelMerge.key("M+ LaLiga HD")
        assertEquals(liga, ChannelMerge.key("M+ La Liga 1080p *"))
        assertEquals(liga, ChannelMerge.key("Movistar Plus LaLiga"))
        assertEquals(ChannelMerge.key("Esport3 720p"), ChannelMerge.key("Esport 3 1080p **"))
        assertEquals(ChannelMerge.key("Teledeporte"), ChannelMerge.key("TELEDEPORTE 1080p (SOLO EVENTOS)"))
        assertEquals(ChannelMerge.key("Canal Spain HD"), ChannelMerge.key("canal"))
        assertEquals(ChannelMerge.key("Fútbol Uno"), ChannelMerge.key("FUTBOL UNO 720p"))
        // nombres de la lista M3U: "M." por "M+", "Movistar X" por "M+ X", "LA 1" por "La 1 TVE"
        assertEquals(ChannelMerge.key("M+ Deportes 2 720p *"), ChannelMerge.key("M. Deportes 2 HD"))
        assertEquals(ChannelMerge.key("M+ VAMOS 2 720p *"), ChannelMerge.key("Movistar Vamos 2"))
        assertEquals(ChannelMerge.key("M+ LaLiga HD"), ChannelMerge.key("MOVISTAR LALIGA"))
        assertEquals(ChannelMerge.key("La 1 TVE 4K **"), ChannelMerge.key("LA 1 Spain"))
        assertEquals(ChannelMerge.key("La 2 TVE 1080p *"), ChannelMerge.key("LA 2 Spain"))
    }

    @Test
    fun clavesQueNoSeUnifican() {
        assertFalse(ChannelMerge.key("M+ Deportes") == ChannelMerge.key("M+ Deportes 2"))
        assertFalse(ChannelMerge.key("M+ 0 1080p") == ChannelMerge.key("MOVISTAR PLUS 1080p"))
        assertFalse(ChannelMerge.key("Sport TV+ 1080p") == ChannelMerge.key("Sport TV 1080p"))
        assertFalse(ChannelMerge.key("Canal [ES]") == ChannelMerge.key("Canal [PT]"))
        assertFalse(ChannelMerge.key("Canal [ES]") == ChannelMerge.key("Canal"))
        assertFalse(ChannelMerge.key("Bein Sports 3 TR 720p *") == ChannelMerge.key("Bein Sports TR 720p *"))
        assertFalse(ChannelMerge.key("M. Deportes 2 HD") == ChannelMerge.key("M+ Deportes 1080p *"))
        assertFalse(ChannelMerge.key("La 1 TVE 4K **") == ChannelMerge.key("La 2 TVE 1080p *"))
    }

    @Test
    fun resolucionYEtiqueta() {
        assertEquals(2160, ChannelMerge.resolution("La 1 TVE 4K **"))
        assertEquals(1080, ChannelMerge.resolution("M+ Deportes 1080p **"))
        assertEquals(1080, ChannelMerge.resolution("Canal FHD"))
        assertEquals(720, ChannelMerge.resolution("Canal HD"))
        assertEquals(0, ChannelMerge.resolution("Paramount+ UFC *"))
        assertEquals("1080p **", ChannelMerge.sourceLabel("M+ Deportes 1080p **", ""))
        assertEquals("*", ChannelMerge.sourceLabel("Paramount+ UFC *", ""))
        assertEquals("fuente", ChannelMerge.sourceLabel("Canal", ""))
        assertEquals("720p * \u00b7 ORIGEN UNO", ChannelMerge.sourceLabel("X 720p *", "ORIGEN UNO"))
    }

    @Test
    fun fusionConGrupoDeLaPrimeraYFuentesPorResolucion() {
        val list = listOf(
            ch(1, "M+ Deportes 720p *", "MOVISTAR DEPORTES"),
            ch(2, "La 1 HD", "TDT"),
            ch(3, "M+ Deportes 1080p **", "OTRO GRUPO"),
            ch(4, "m+ deportes 1080p *", "TDT"),
            ch(5, "La 1 4K", "TDT")
        )
        val rows = ChannelMerge.merge(list)
        assertEquals(2, rows.size)
        assertEquals("M+ Deportes", rows[0].name)
        assertEquals("MOVISTAR DEPORTES", rows[0].group)
        assertEquals(listOf(h(3), h(4), h(1)), rows[0].sources.map { it.hash })
        assertEquals(listOf("1080p **", "1080p *", "720p *"), rows[0].sources.map { it.label })
        assertEquals("La 1", rows[1].name)
        assertEquals(listOf(h(5), h(2)), rows[1].sources.map { it.hash })
        assertEquals(h(3), rows[0].hash)
    }

    @Test
    fun paisEnElNombreYLogoDeLaPrimeraQueLoTenga() {
        val list = listOf(
            Channel("Canal [ES] HD", h(1), "G"),
            Channel("Canal [ES] FHD", h(2), "G", "http://x/logo.png", "canal.es"),
            Channel("Canal [PT] HD", h(3), "G")
        )
        val rows = ChannelMerge.merge(list)
        assertEquals(2, rows.size)
        assertEquals("Canal [ES]", rows[0].name)
        assertEquals("http://x/logo.png", rows[0].logo)
        assertEquals("canal.es", rows[0].tvgId)
        assertEquals("Canal [PT]", rows[1].name)
    }

    @Test
    fun hashApuntaASuFilaYRecientes() {
        val rows = ChannelMerge.merge(listOf(ch(1, "A 720p"), ch(2, "A 1080p"), ch(3, "B")))
        val m = ChannelMerge.byHash(rows)
        assertEquals(2, m.size - 1)
        assertEquals(m[h(1)], m[h(2)])
        val rec = ChannelMerge.recentRows(rows, listOf(h(1), h(2), h(9), h(3)))
        assertEquals(listOf("A", "B"), rec.map { it.name })
        assertEquals(listOf("G"), ChannelMerge.groupsOf(rows))
        assertEquals(1, ChannelMerge.manualRow(h(7)).sources.size)
    }

    @Test
    fun quitarRecientePorFila() {
        val rows = ChannelMerge.merge(listOf(ch(1, "A 720p"), ch(2, "A 1080p"), ch(3, "B")))
        val recents = listOf(h(1), h(3), h(2))
        assertEquals(listOf(h(3)), ChannelMerge.removeRecent(rows, recents, ChannelMerge.key("A")))
        assertEquals(listOf(h(1), h(2)), ChannelMerge.removeRecent(rows, recents, ChannelMerge.key("B")))
        assertEquals(recents, ChannelMerge.removeRecent(rows, recents, "otra"))
    }

    @Test
    fun listaRealDelProyecto() {
        val f = File("../lista.json")
        assumeTrue(f.exists())
        val list = ChannelLogic.dedupe(ListParsers.parseJson(f.readText()))
        val rows = ChannelMerge.merge(list)
        assertTrue(rows.size < list.size)
        assertEquals(list.size, rows.sumOf { it.sources.size })
        assertEquals(list.map { it.hash }.toSet(), rows.flatMap { r -> r.sources.map { it.hash } }.toSet())
        assertTrue(rows.all { it.name.isNotEmpty() && it.sources.isNotEmpty() })
        for (r in rows) {
            val res = r.sources.map { it.resolution }
            assertEquals(res.sortedDescending(), res)
        }
        val dep = rows.first { it.name == "M+ Deportes" }
        assertEquals(4, dep.sources.size)
        assertEquals(1080, dep.sources[0].resolution)
        val la1 = rows.first { it.name == "La 1 TVE" }
        assertEquals(listOf(2160, 1080), la1.sources.map { it.resolution })
        assertEquals(1, rows.count { it.name == "M+ Deportes 2" })
    }
}
