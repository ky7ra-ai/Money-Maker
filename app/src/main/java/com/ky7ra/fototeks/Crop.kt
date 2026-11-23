package com.ky7ra.fototeks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Pemetaan antara foto dan "crop tegak" satu baris teks: baris diluruskan (rotasi dan perspektif
 * dikoreksi) lalu diberi bantalan (pad) di sekelilingnya. Koordinat lokal (u,v) berpusat
 * di sudut kiri-atas kotak OCR; crop = ((u + padL) * kx, (v + padT) * ky).
 */
class CropSpec private constructor(
    val geo: Geo,
    val padL: Float,
    val padR: Float,
    val padT: Float,
    val padB: Float,
    val wc: Int,
    val hc: Int,
    val kx: Float,
    val ky: Float,
    val aligned: Boolean,
    val cropToImg: Matrix,
    val imgToCrop: Matrix,
    /** Empat sudut crop di dalam foto (tl,tr,br,bl). */
    val corners: FloatArray,
) {
    /** Kotak teks OCR di dalam crop: x0,y0,x1,y1 (x1,y1 eksklusif). */
    fun box(): IntArray = intArrayOf(
        (padL * kx).roundToInt(),
        (padT * ky).roundToInt(),
        ((padL + geo.w) * kx).roundToInt(),
        ((padT + geo.h) * ky).roundToInt(),
    )

    companion object {
        fun make(geo: Geo, padL: Float, padR: Float, padT: Float, padB: Float, k: Float): CropSpec {
            val q = geo.quad
            val aligned = abs(q[3] - q[1]) < 0.6f && abs(q[6] - q[0]) < 0.6f &&
                abs(q[2] - q[4]) < 0.6f && abs(q[7] - q[5]) < 0.6f
            val toImg = Matrix()
            val toCrop = Matrix()
            if (aligned) {
                val x0 = floor(q[0] - padL)
                val y0 = floor(q[1] - padT)
                val x1 = ceil(q[2] + padR)
                val y1 = ceil(q[7] + padB)
                val wc = max(2, (x1 - x0).roundToInt())
                val hc = max(2, (y1 - y0).roundToInt())
                toImg.setTranslate(x0, y0)
                toImg.invert(toCrop)
                return CropSpec(
                    geo, q[0] - x0, x1 - q[2], q[1] - y0, y1 - q[7], wc, hc, 1f, 1f, true,
                    toImg, toCrop, floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1),
                )
            }
            val w = geo.w
            val h = geo.h
            val totW = w + padL + padR
            val totH = h + padT + padB
            val wc = max(2, ceil(totW * k).toInt())
            val hc = max(2, ceil(totH * k).toInt())
            val dst = FloatArray(8)
            quadPoint(q, -padL / w, -padT / h, dst, 0)
            quadPoint(q, 1f + padR / w, -padT / h, dst, 2)
            quadPoint(q, 1f + padR / w, 1f + padB / h, dst, 4)
            quadPoint(q, -padL / w, 1f + padB / h, dst, 6)
            val src = floatArrayOf(0f, 0f, wc.toFloat(), 0f, wc.toFloat(), hc.toFloat(), 0f, hc.toFloat())
            toImg.setPolyToPoly(src, 0, dst, 0, 4)
            toImg.invert(toCrop)
            return CropSpec(
                geo, padL, padR, padT, padB, wc, hc, wc / totW, hc / totH, false, toImg, toCrop, dst,
            )
        }
    }
}

/** Piksel crop dalam bentuk float per kanal; [v] menandai piksel yang berada di dalam foto. */
class Crop(
    val spec: CropSpec,
    val w: Int,
    val h: Int,
    val r: FloatArray,
    val g: FloatArray,
    val b: FloatArray,
    val v: BooleanArray,
)

fun buildCrop(src: Bitmap, spec: CropSpec): Crop {
    val bmp = Bitmap.createBitmap(spec.wc, spec.hc, Bitmap.Config.ARGB_8888)
    Canvas(bmp).drawBitmap(src, spec.imgToCrop, Paint(Paint.FILTER_BITMAP_FLAG))
    val n = spec.wc * spec.hc
    val px = IntArray(n)
    bmp.getPixels(px, 0, spec.wc, 0, 0, spec.wc, spec.hc)
    bmp.recycle()
    val r = FloatArray(n)
    val g = FloatArray(n)
    val b = FloatArray(n)
    val v = BooleanArray(n)
    for (i in 0 until n) {
        val c = px[i]
        v[i] = (c ushr 24) >= 128
        r[i] = ((c shr 16) and 255).toFloat()
        g[i] = ((c shr 8) and 255).toFloat()
        b[i] = (c and 255).toFloat()
    }
    return Crop(spec, spec.wc, spec.hc, r, g, b, v)
}
