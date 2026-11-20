package local.acetv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StallDetectorTest {
    @Test
    fun primeraMedidaNoEsParada() {
        assertFalse(StallDetector(15000).update(1000, 0))
    }

    @Test
    fun posicionQueAvanzaNuncaEsParada() {
        val d = StallDetector(15000)
        for (i in 0..60) assertFalse(d.update(1000L + i * 1000, i * 1000L))
    }

    @Test
    fun saltaALos15Segundos() {
        val d = StallDetector(15000)
        d.update(5000, 0)
        assertFalse(d.update(5000, 14900))
        assertTrue(d.update(5000, 15000))
        assertTrue(d.update(5000, 20000))
    }

    @Test
    fun seRecuperaAlAvanzarYVuelveAContar() {
        val d = StallDetector(15000)
        d.update(5000, 0)
        assertTrue(d.update(5000, 15000))
        assertFalse(d.update(5100, 21000))
        assertFalse(d.update(5100, 35900))
        assertTrue(d.update(5100, 36000))
    }

    @Test
    fun resetEmpiezaDeCero() {
        val d = StallDetector(15000)
        d.update(7000, 0)
        d.reset()
        assertFalse(d.update(7000, 100000))
        assertFalse(d.update(7000, 114999))
        assertTrue(d.update(7000, 115000))
    }
}
