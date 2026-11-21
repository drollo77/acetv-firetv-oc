package local.acetv

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.util.LruCache
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import java.io.File
import java.util.concurrent.Executors

/** Tamano de la casilla del logo en la lista de canales. */
const val LOGO_W = 96
const val LOGO_H = 54

/** Descarga, guarda en disco y decodifica (en pequeno) los logos de los canales. */
class LogoLoader(private val dir: File, private val onUpdate: () -> Unit) {
    private val tag = "AceTV"
    private val mem = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()
    private val pool = Executors.newFixedThreadPool(2)

    init {
        dir.mkdirs()
        pool.execute { trimDisk() }
    }

    fun shutdown() {
        pool.shutdownNow()
    }

    /** Logo ya cargado en memoria, o null (y lo pide en segundo plano para la proxima vez). */
    fun get(url: String): Bitmap? {
        if (url.isEmpty()) return null
        val b: Bitmap? = mem.get(url)
        if (b != null) return b
        synchronized(this) {
            if (failed.contains(url) || pending.contains(url)) return null
            pending.add(url)
        }
        pool.execute { load(url) }
        return null
    }

    private fun fail(url: String, why: String) {
        synchronized(this) { failed.add(url) }
        Log.w(tag, "logo: $why <- $url")
    }

    private fun load(url: String) {
        try {
            val f = File(dir, LogoCache.fileName(url))
            if (!f.exists() || f.length() == 0L) {
                var err = "sin respuesta"
                val bytes = LogoFetch.fetch(url, 400 * 1024, 6000) { err = it }
                if (bytes == null) {
                    fail(url, err)
                    return
                }
                f.writeBytes(bytes)
            } else {
                f.setLastModified(System.currentTimeMillis()) // asi el borrado respeta los que se usan
            }
            val bmp = decode(f)
            if (bmp == null) {
                f.delete()
                fail(url, "formato de imagen no valido")
                return
            }
            mem.put(url, bmp)
            onUpdate()
        } catch (e: OutOfMemoryError) {
            fail(url, "sin memoria")
        } catch (e: Exception) {
            fail(url, e.message ?: "error")
        } finally {
            synchronized(this) { pending.remove(url) }
        }
    }

    private fun decode(f: File): Bitmap? {
        val o = BitmapFactory.Options()
        o.inJustDecodeBounds = true
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        val d = BitmapFactory.Options()
        d.inSampleSize = LogoCache.sampleSize(o.outWidth, o.outHeight, LOGO_W, LOGO_H)
        val bmp = BitmapFactory.decodeFile(f.path, d) ?: return null
        val sc = LogoCache.fitScale(bmp.width, bmp.height, LOGO_W, LOGO_H)
        if (sc >= 1f) return bmp
        val w = maxOf(1, (bmp.width * sc).toInt())
        val h = maxOf(1, (bmp.height * sc).toInt())
        val small = Bitmap.createScaledBitmap(bmp, w, h, true)
        if (small !== bmp) bmp.recycle()
        return small
    }

    private fun trimDisk() {
        val files = dir.listFiles() ?: return
        val entries = files.map { LogoCache.Entry(it.name, it.length(), it.lastModified()) }
        for (name in LogoCache.toEvict(entries, 10L * 1024 * 1024)) File(dir, name).delete()
    }
}

/** Logo centrado sobre una placa clara de tamano fijo (si no hay imagen, deja el hueco vacio). */
class LogoDrawable(private val bmp: Bitmap?) : Drawable() {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val plate = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        plate.color = 0xFFF0F0F0.toInt()
    }

    override fun draw(canvas: Canvas) {
        val b = bmp ?: return
        val r = bounds
        canvas.drawRoundRect(RectF(r), 8f, 8f, plate)
        val left = r.left + (r.width() - b.width) / 2f
        val top = r.top + (r.height() - b.height) / 2f
        canvas.drawBitmap(b, left, top, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
        plate.alpha = alpha
    }

    override fun setColorFilter(cf: ColorFilter?) {
        paint.colorFilter = cf
    }

    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT
    override fun getIntrinsicWidth(): Int = LOGO_W
    override fun getIntrinsicHeight(): Int = LOGO_H
}

/** Lista de canales: nombre y, a la izquierda, su logo (se va rellenando segun llega). */
class ChannelRows(
    ctx: Context,
    titles: List<String>,
    private val logoUrls: List<String>,
    private val loader: LogoLoader
) : ArrayAdapter<String>(ctx, android.R.layout.simple_list_item_activated_1, titles) {
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val v = super.getView(position, convertView, parent)
        val tv = v as TextView
        tv.textSize = 22f
        tv.setPadding(24, 8, 24, 8)
        tv.compoundDrawablePadding = 20
        tv.setCompoundDrawablesWithIntrinsicBounds(LogoDrawable(loader.get(logoUrls[position])), null, null, null)
        return v
    }
}
