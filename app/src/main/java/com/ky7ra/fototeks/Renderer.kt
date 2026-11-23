package com.ky7ra.fototeks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.Random
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Menggambar hasil edit ke salinan foto asli. Tiap baris diproses pada "crop tegak":
 * teks lama dihapus (hanya piksel tinta yang diganti, tekstur di sekitarnya tetap),
 * teks baru dirender dengan font, ukuran, ketajaman tepi, warna, dan noise yang sama
 * dengan aslinya, lalu hanya piksel yang berubah yang ditempel kembali ke foto.
 */
object Renderer {

    fun render(
        src: Bitmap,
        lines: List<TextLine>,
        edits: Map<Int, LineEdit>,
        styleFor: (TextLine, String?) -> LineStyle,
    ): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (edits.isEmpty()) return out
        val canvas = Canvas(out)
        val byId = lines.associateBy { it.id }
        for ((id, e) in edits) {
            val line = byId[id] ?: continue
            try {
                renderLine(src, canvas, line, e, styleFor(line, e.faceId))
            } catch (_: Exception) {
                // Lewati baris yang gagal; baris lain tetap diproses.
            }
        }
        return out
    }

    private fun fitsInImage(corners: FloatArray, w: Int, h: Int): Boolean {
        for (i in 0 until 4) {
            val x = corners[i * 2]
            val y = corners[i * 2 + 1]
            if (x < -2f || y < -2f || x > w + 2f || y > h + 2f) return false
        }
        return true
    }

    private fun renderLine(src: Bitmap, canvas: Canvas, line: TextLine, e: LineEdit, st: LineStyle) {
        val geo = line.geo
        val face = FontLibrary.byId(st.faceId) ?: FontLibrary.fallback()
        val t = e.text.trim()
        val pad = TextAnalyzer.padFor(geo.h)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = face.typeface
            textSize = 100f
        }
        val nb = Rect()
        if (t.isNotEmpty()) paint.getTextBounds(t, 0, t.length, nb)
        val boundsW = (nb.right - nb.left) / 100f
        var size = st.sizeL * e.sizeScale

        // Batasi lebar agar crop tidak raksasa, lalu pastikan seluruh area crop masih di dalam foto.
        if (t.isNotEmpty() && boundsW * size * st.sx > geo.w * 2.5f) {
            size *= geo.w * 2.5f / (boundsW * size * st.sx)
        }
        var spec = CropSpec.make(geo, pad, pad, pad, pad, 1.5f)
        var inkLeft = st.inkLeftL
        var tries = 0
        while (true) {
            val newW = if (t.isEmpty()) 0f else boundsW * size * st.sx
            inkLeft = when (e.align) {
                1 -> (st.inkLeftL + st.inkRightL) / 2f - newW / 2f
                2 -> st.inkRightL - newW
                else -> st.inkLeftL
            }
            val padL = max(pad, -inkLeft + pad * 0.5f)
            val padR = max(pad, inkLeft + newW - geo.w + pad * 0.5f)
            spec = CropSpec.make(geo, padL, padR, pad, pad, 1.5f)
            if (fitsInImage(spec.corners, src.width, src.height) || tries >= 8 || t.isEmpty()) break
            size *= 0.93f
            tries++
        }

        val crop = buildCrop(src, spec)
        val w = crop.w
        val h = crop.h
        val box = spec.box()
        val strip = TextAnalyzer.stripFor(pad, spec.kx)
        val bg = TextAnalyzer.rowLerpBg(crop, box[0], box[2], strip)

        val origTc = floatArrayOf(Color.red(st.color).toFloat(), Color.green(st.color).toFloat(), Color.blue(st.color).toFloat())
        val ink = TextAnalyzer.analyzeInk(crop, bg, box, strip, origTc)
        val boxH = max(1, box[3] - box[1])
        val dil = max(1, (0.05f * boxH + st.blurL * spec.kx * 1.2f).roundToInt())
        val mask = TextAnalyzer.inkMask(ink, w, h, dil)
        val rnd = Random(line.id * 7919L + 13L)
        val filled = TextAnalyzer.harmonicFill(crop, bg, mask, st.noise, rnd)

        // Teks baru.
        val m = FloatArray(w * h)
        if (t.isNotEmpty()) {
            val sizeC = size * spec.ky
            val scale = sizeC / 100f
            val ox = (inkLeft + spec.padL) * spec.kx - nb.left * scale * st.sx
            val base = (st.baselineL + spec.padT) * spec.ky
            val mr = MaskRenderer(w, h)
            try {
                mr.render(t, face.typeface, sizeC, st.sx, ox, base, m)
            } finally {
                mr.release()
            }
            Imaging.blur(m, w, h, st.blurL * spec.kx, FloatArray(w * h))
        }

        val col = e.color ?: st.color
        val cr = Color.red(col).toFloat()
        val cg = Color.green(col).toFloat()
        val cb = Color.blue(col).toFloat()
        val rnd2 = Random(line.id * 31L + 7L)

        val alpha = FloatArray(w * h)
        val px = IntArray(w * h)
        for (i in 0 until w * h) {
            val mi = m[i]
            var r = filled.r[i]
            var g = filled.g[i]
            var b = filled.b[i]
            if (mi > 0.004f) {
                val nz = if (st.noise > 0.05f) (rnd2.nextGaussian() * st.noise).toFloat() else 0f
                r = r * (1f - mi) + (cr + nz) * mi
                g = g * (1f - mi) + (cg + nz) * mi
                b = b * (1f - mi) + (cb + nz) * mi
            }
            var a = 0f
            if (crop.v[i]) {
                a = if (mask[i]) 1f else 0f
                val ta = min(1f, mi * 4f)
                if (ta > a) a = ta
            }
            alpha[i] = a
            px[i] = Color.rgb(
                r.roundToInt().coerceIn(0, 255),
                g.roundToInt().coerceIn(0, 255),
                b.roundToInt().coerceIn(0, 255),
            )
        }
        if (!spec.aligned) Imaging.blur(alpha, w, h, 0.7f, FloatArray(w * h))
        for (i in 0 until w * h) {
            val a = (alpha[i] * 255f).roundToInt().coerceIn(0, 255)
            px[i] = (a shl 24) or (px[i] and 0x00FFFFFF)
        }

        val patch = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
        canvas.drawBitmap(patch, spec.cropToImg, Paint(Paint.FILTER_BITMAP_FLAG))
        patch.recycle()
    }
}
