package com.ky7ra.fototeks

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Titik pada segi empat (tl,tr,br,bl) lewat interpolasi bilinear; s,t di luar 0..1 berarti ekstrapolasi. */
fun quadPoint(q: FloatArray, s: Float, t: Float, out: FloatArray, i: Int) {
    val ex = q[4] - q[2] - q[6] + q[0]
    val ey = q[5] - q[3] - q[7] + q[1]
    out[i] = q[0] + s * (q[2] - q[0]) + t * (q[6] - q[0]) + s * t * ex
    out[i + 1] = q[1] + s * (q[3] - q[1]) + t * (q[7] - q[1]) + s * t * ey
}

/** Geometri satu baris teks: segi empat (kiri-atas, kanan-atas, kanan-bawah, kiri-bawah). */
class Geo(val quad: FloatArray) {
    val cx: Float = (quad[0] + quad[2] + quad[4] + quad[6]) / 4f
    val cy: Float = (quad[1] + quad[3] + quad[5] + quad[7]) / 4f
    val w: Float = max(1f, hypot(quad[2] - quad[0], quad[3] - quad[1]))
    val h: Float = max(1f, hypot(quad[6] - quad[0], quad[7] - quad[1]))
    val angle: Float

    init {
        var a = Math.toDegrees(
            atan2((quad[3] - quad[1]).toDouble(), (quad[2] - quad[0]).toDouble()),
        ).toFloat()
        if (abs(a) < 1.5f) a = 0f
        angle = a
    }

    /** Segi empat yang diperbesar [pad] piksel ke semua sisi (untuk gambar kotak di layar). */
    fun padded(pad: Float): FloatArray {
        val out = FloatArray(8)
        val sp = pad / w
        val tp = pad / h
        quadPoint(quad, -sp, -tp, out, 0)
        quadPoint(quad, 1f + sp, -tp, out, 2)
        quadPoint(quad, 1f + sp, 1f + tp, out, 4)
        quadPoint(quad, -sp, 1f + tp, out, 6)
        return out
    }

    fun contains(x: Float, y: Float, slop: Float = 0f): Boolean {
        val rad = Math.toRadians(-angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dx = x - cx
        val dy = y - cy
        val lx = dx * c - dy * s
        val ly = dx * s + dy * c
        return abs(lx) <= w / 2f + slop && abs(ly) <= h / 2f + slop
    }

    companion object {
        fun fromRect(l: Float, t: Float, r: Float, b: Float) =
            Geo(floatArrayOf(l, t, r, t, r, b, l, b))
    }
}

/**
 * Gaya teks hasil analisis satu baris. Semua koordinat "L" adalah koordinat lokal
 * sepanjang baris (piksel foto asli), berpusat di sudut kiri-atas kotak OCR:
 * u ke arah kanan (sepanjang teks), v ke arah bawah.
 */
class LineStyle(
    val faceId: String,
    val sizeL: Float,
    val sx: Float,
    val blurL: Float,
    val inkLeftL: Float,
    val inkRightL: Float,
    val baselineL: Float,
    val color: Int,
    val bgColor: Int,
    /** Deviasi standar noise (skala 0..255). */
    val noise: Float,
    /** 0 = sangat cocok, 2 = tidak cocok. */
    val error: Float,
    /** Id font terdekat, yang paling mirip lebih dulu. */
    val alternatives: List<String>,
    val reliable: Boolean,
)

/** Hasil analisis OCR untuk satu baris teks pada foto. */
class TextLine(
    val id: Int,
    val text: String,
    val geo: Geo,
    val wordCount: Int,
    val language: String,
    val style: LineStyle,
    /** 0 = kiri, 1 = tengah, 2 = kanan. */
    val defaultAlign: Int,
) {
    val textColor: Int get() = style.color
    val bgColor: Int get() = style.bgColor
}

/** Hasil edit pengguna untuk satu baris teks. */
data class LineEdit(
    val text: String,
    /** null = pakai font yang terdeteksi. */
    val faceId: String?,
    /** 0 = kiri, 1 = tengah, 2 = kanan. */
    val align: Int,
    /** null = pakai warna asli yang terdeteksi. */
    val color: Int?,
    val sizeScale: Float = 1f,
)
