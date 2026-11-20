package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QrAndPanelTest {
    @Test
    fun etiquetasDeGrupos() {
        assertEquals(
            listOf(ChannelLogic.RECENT_LABEL, "A", "B", ChannelLogic.QR_LABEL),
            ChannelLogic.groupLabels(listOf("A", "B"))
        )
        assertEquals(listOf(ChannelLogic.RECENT_LABEL, ChannelLogic.QR_LABEL), ChannelLogic.groupLabels(emptyList()))
    }

    @Test
    fun estadoDeSesionNoConsumeActividad() {
        var now = 1000L
        val s = RemoteSession(ttlMs = 300000L, maxBad = 30, clock = { now }, newCode = { "abcd1234" })
        assertEquals(RemoteSession.Result.OK, s.status())
        now += 299000L
        assertEquals(RemoteSession.Result.OK, s.status())
        now += 2000L
        // status() no renueva la actividad: sigue caducada aunque se haya consultado antes
        assertEquals(RemoteSession.Result.EXPIRED, s.status())
        s.renew()
        assertEquals(RemoteSession.Result.OK, s.status())
        for (i in 1..30) s.check("mal$i")
        assertEquals(RemoteSession.Result.LOCKED, s.status())
        s.renew()
        assertEquals(RemoteSession.Result.OK, s.status())
    }

    @Test
    fun qrConZonaBlancaYBuscadores() {
        val m = QrRender.modules("http://192.168.1.127:8765/s/178eed75/")
        val n = m.size
        assertTrue(n >= 29 + 8)
        assertTrue(m.all { it.size == n })
        // zona blanca de 4 modulos
        for (i in 0 until n) {
            for (b in 0 until 4) {
                assertFalse(m[b][i])
                assertFalse(m[n - 1 - b][i])
                assertFalse(m[i][b])
                assertFalse(m[i][n - 1 - b])
            }
        }
        // buscador superior izquierdo: anillo oscuro 7x7, anillo claro, centro 3x3 oscuro
        for (k in 0..6) {
            assertTrue(m[4][4 + k])
            assertTrue(m[10][4 + k])
            assertTrue(m[4 + k][4])
            assertTrue(m[4 + k][10])
        }
        assertFalse(m[5][5])
        assertFalse(m[9][9])
        assertTrue(m[7][7])
        assertTrue(m[6][6])
        assertTrue(m[8][8])
    }

    @Test
    fun urlsDistintasDanQrDistintos() {
        val a = QrRender.modules("http://192.168.1.127:8765/s/178eed75/")
        val b = QrRender.modules("http://192.168.1.127:8765/s/178eed76/")
        assertEquals(a.size, b.size)
        var distintos = 0
        for (y in a.indices) for (x in a.indices) if (a[y][x] != b[y][x]) distintos++
        assertTrue(distintos > 0)
    }

    @Test
    fun pixelesEscalados() {
        val m = QrRender.modules("http://10.0.0.5:8765/s/00000000/")
        val (size, px) = QrRender.pixels(m, 3)
        assertEquals(m.size * 3, size)
        assertEquals(size * size, px.size)
        assertEquals(0xFFFFFFFF.toInt(), px[0])
        // el modulo (4,4) es oscuro: ocupa los pixeles 12..14 de las filas 12..14
        assertEquals(0xFF000000.toInt(), px[12 * size + 12])
        assertEquals(0xFF000000.toInt(), px[14 * size + 14])
    }
}
