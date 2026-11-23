package com.ky7ra.fototeks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.max

object Imaging {

    private fun kernel(sigma: Float): FloatArray {
        val r = max(1, ceil(sigma * 3f).toInt())
        val k = FloatArray(2 * r + 1)
        var sum = 0f
        for (i in -r..r) {
            val v = exp(-(i * i) / (2f * sigma * sigma))
            k[i + r] = v
            sum += v
        }
        for (i in k.indices) k[i] /= sum
        return k
    }

    /** Blur Gaussian terpisah pada bidang w*h (tepi diperpanjang). [tmp] harus seukuran [a]. */
    fun blur(a: FloatArray, w: Int, h: Int, sigma: Float, tmp: FloatArray) {
        if (sigma < 0.15f || w < 1 || h < 1) return
        val k = kernel(sigma)
        val r = k.size / 2
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                var s = 0f
                if (x >= r && x < w - r) {
                    for (i in -r..r) s += a[row + x + i] * k[i + r]
                } else {
                    for (i in -r..r) {
                        var xx = x + i
                        if (xx < 0) xx = 0 else if (xx > w - 1) xx = w - 1
                        s += a[row + xx] * k[i + r]
                    }
                }
                tmp[row + x] = s
            }
        }
        for (y in 0 until h) {
            for (x in 0 until w) {
                var s = 0f
                if (y >= r && y < h - r) {
                    for (i in -r..r) s += tmp[(y + i) * w + x] * k[i + r]
                } else {
                    for (i in -r..r) {
                        var yy = y + i
                        if (yy < 0) yy = 0 else if (yy > h - 1) yy = h - 1
                        s += tmp[yy * w + x] * k[i + r]
                    }
                }
                a[y * w + x] = s
            }
        }
    }

    /** Pelebaran (dilasi) topeng dengan elemen persegi berjari-jari [r]. */
    fun dilate(m: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        if (r <= 0) return m.copyOf()
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var last = -1000000
            // sapuan maju: tandai piksel dalam jarak r di kanan piksel aktif
            for (x in 0 until w) {
                if (m[row + x]) last = x
                if (x - last <= r) tmp[row + x] = true
            }
            last = 1000000
            for (x in w - 1 downTo 0) {
                if (m[row + x]) last = x
                if (last - x <= r) tmp[row + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -1000000
            for (y in 0 until h) {
                if (tmp[y * w + x]) last = y
                if (y - last <= r) out[y * w + x] = true
            }
            last = 1000000
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) last = y
                if (last - y <= r) out[y * w + x] = true
            }
        }
        return out
    }
}

/** Merender teks putih pada kanvas transparan dan mengembalikannya sebagai peta cakupan 0..1. */
class MaskRenderer(val w: Int, val h: Int) {
    private val bmp: Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    private val canvas = Canvas(bmp)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
    }
    private val buf = IntArray(w * h)

    fun render(
        text: String,
        face: android.graphics.Typeface,
        size: Float,
        sx: Float,
        originX: Float,
        baseline: Float,
        out: FloatArray,
    ) {
        bmp.eraseColor(0)
        paint.typeface = face
        paint.textSize = size
        paint.textScaleX = sx
        canvas.drawText(text, originX, baseline, paint)
        bmp.getPixels(buf, 0, w, 0, 0, w, h)
        for (i in 0 until w * h) out[i] = (buf[i] ushr 24) / 255f
    }

    fun release() {
        bmp.recycle()
    }
}
