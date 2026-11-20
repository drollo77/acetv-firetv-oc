package local.acetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcePlanTest {
    private fun s(n: Int, res: Int) = Source(n.toString().padStart(40, '0'), "r$res#$n", res)
    private val a = s(1, 720)
    private val b = s(2, 1080)
    private val c = s(3, 1080)
    private val d = s(4, 2160)

    @Test
    fun ordenPorResolucionConLimiteDelDispositivo() {
        val p = SourcePlan(listOf(a, b, c, d), null, 1080)
        assertEquals(listOf("r1080#2", "r1080#3", "r720#1", "r2160#4"), p.labels())
        assertEquals(b, p.current())
        assertEquals(4, p.size)
    }

    @Test
    fun empiezaPorLaQueFuncionoLaUltimaVez() {
        val p = SourcePlan(listOf(a, b, c, d), a.hash, 1080)
        assertEquals(listOf("r720#1", "r1080#2", "r1080#3", "r2160#4"), p.labels())
        val q = SourcePlan(listOf(a, b, c, d), "no-existe", 1080)
        assertEquals(b, q.current())
    }

    @Test
    fun unReintentoPorFuenteYTresEnLaUltima() {
        val p = SourcePlan(listOf(a, b), null, 1080)
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
        assertEquals(SourcePlan.Decision.NEXT, p.onFailure())
        assertEquals(0, p.index - 1)
        assertTrue(p.isLast())
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
        assertEquals(SourcePlan.Decision.GIVE_UP, p.onFailure())
        assertEquals(SourcePlan.Decision.GIVE_UP, p.onFailure())
    }

    @Test
    fun elExitoReiniciaLosReintentos() {
        val p = SourcePlan(listOf(a, b), null, 1080)
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
        p.onSuccess()
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
        assertEquals(SourcePlan.Decision.NEXT, p.onFailure())
    }

    @Test
    fun unaSolaFuenteTieneTresReintentos() {
        val p = SourcePlan(listOf(a), null, 1080)
        assertTrue(p.isLast())
        repeat(3) { assertEquals(SourcePlan.Decision.RETRY, p.onFailure()) }
        assertEquals(SourcePlan.Decision.GIVE_UP, p.onFailure())
    }

    @Test
    fun cambioManualCircularYTiempos() {
        val p = SourcePlan(listOf(a, b, c), null, 1080)
        assertEquals(0, p.index)
        p.jump(-1)
        assertEquals(2, p.index)
        assertTrue(p.isLast())
        p.jump(1)
        assertEquals(0, p.index)
        assertFalse(p.isLast())
        assertEquals(25000L, p.loadTimeoutMs())
        p.jump(2)
        assertEquals(45000L, p.loadTimeoutMs())
        p.jump(1)
        assertEquals(SourcePlan.Decision.RETRY, p.onFailure())
    }
}
