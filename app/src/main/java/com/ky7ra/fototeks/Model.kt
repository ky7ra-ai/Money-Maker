package com.ky7ra.fototeks

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Geometri satu baris teks: titik tengah, lebar, tinggi, dan sudut rotasi (derajat). */
class Geo(
    val cx: Float,
    val cy: Float,
    val w: Float,
    val h: Float,
    val angle: Float,
) {
    /** Empat sudut (x0,y0,x1,y1,...) berurutan kiri-atas, kanan-atas, kanan-bawah, kiri-bawah. */
    fun quad(pad: Float): FloatArray {
        val hw = w / 2f + pad
        val hh = h / 2f + pad
        val rad = Math.toRadians(angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val lx = floatArrayOf(-hw, hw, hw, -hw)
        val ly = floatArrayOf(-hh, -hh, hh, hh)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            out[i * 2] = cx + lx[i] * c - ly[i] * s
            out[i * 2 + 1] = cy + lx[i] * s + ly[i] * c
        }
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
}

/** Hasil analisis OCR untuk satu baris teks pada foto. */
data class TextLine(
    val id: Int,
    val text: String,
    val geo: Geo,
    val wordCount: Int,
    val language: String,
    /** Warna teks yang diperkirakan (ARGB). */
    val textColor: Int,
    /** Warna latar yang diperkirakan (ARGB). */
    val bgColor: Int,
    /** 0 = kiri, 1 = tengah, 2 = kanan. */
    val defaultAlign: Int,
)

/** Hasil edit pengguna untuk satu baris teks. */
data class LineEdit(
    val text: String,
    val bold: Boolean,
    /** 0 = kiri, 1 = tengah, 2 = kanan. */
    val align: Int,
    /** null = pakai warna asli yang terdeteksi. */
    val color: Int?,
)
