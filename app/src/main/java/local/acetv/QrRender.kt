package local.acetv

import io.nayuki.qrcodegen.QrCode

/** Generacion de QR sin dependencias externas (usa la biblioteca de Project Nayuki, licencia MIT). */
object QrRender {
    /** Matriz de modulos (true = oscuro) con zona blanca de `border` modulos alrededor. */
    fun modules(text: String, border: Int = 4): Array<BooleanArray> {
        val qr = QrCode.encodeText(text, QrCode.Ecc.MEDIUM)
        val n = qr.size + 2 * border
        return Array(n) { y -> BooleanArray(n) { x -> qr.getModule(x - border, y - border) } }
    }

    /** Pixeles ARGB (negro/blanco) de la matriz ampliada `scale` veces; devuelve (ancho, pixeles). */
    fun pixels(m: Array<BooleanArray>, scale: Int): Pair<Int, IntArray> {
        val size = m.size * scale
        val px = IntArray(size * size)
        for (y in 0 until size) {
            val row = m[y / scale]
            for (x in 0 until size) {
                px[y * size + x] = if (row[x / scale]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Pair(size, px)
    }
}
