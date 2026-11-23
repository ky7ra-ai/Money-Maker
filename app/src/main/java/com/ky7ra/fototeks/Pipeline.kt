package com.ky7ra.fototeks

import android.graphics.Bitmap
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlin.math.abs

private class RawLine(val line: Text.Line, val geo: Geo)

object Pipeline {

    private fun geoOf(line: Text.Line): Geo? {
        val cp = line.cornerPoints
        if (cp != null && cp.size == 4) {
            val q = floatArrayOf(
                cp[0].x.toFloat(), cp[0].y.toFloat(),
                cp[1].x.toFloat(), cp[1].y.toFloat(),
                cp[2].x.toFloat(), cp[2].y.toFloat(),
                cp[3].x.toFloat(), cp[3].y.toFloat(),
            )
            val g = Geo(q)
            if (g.w > 2f && g.h > 2f) return g
        }
        val r = line.boundingBox ?: return null
        return Geo.fromRect(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat())
    }

    /** Menganalisis semua baris teks hasil OCR: geometri, font, warna, blur, noise. */
    suspend fun analyze(bmp: Bitmap, text: Text, faces: List<FontFace>): List<TextLine> = coroutineScope {
        val raws = ArrayList<RawLine>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                if (line.text.isBlank()) continue
                val g = geoOf(line) ?: continue
                raws += RawLine(line, g)
            }
        }
        val sem = Semaphore(4)
        raws.mapIndexed { idx, r ->
            async(Dispatchers.Default) {
                sem.withPermit {
                    val style = TextAnalyzer.analyze(bmp, r.line.text, r.geo, faces)
                    val nearCenter = abs(r.geo.cx - bmp.width / 2f) < bmp.width * 0.03f &&
                        r.geo.w < bmp.width * 0.9f
                    TextLine(
                        id = idx,
                        text = r.line.text,
                        geo = r.geo,
                        wordCount = r.line.elements.size,
                        language = r.line.recognizedLanguage,
                        style = style,
                        defaultAlign = if (nearCenter) 1 else 0,
                    )
                }
            }
        }.awaitAll()
    }

    /** Gaya untuk baris tertentu bila pengguna memaksa font lain (dicocokkan ulang hanya untuk font itu). */
    fun styleFor(src: Bitmap, line: TextLine, faceId: String?, faces: List<FontFace>): LineStyle {
        if (faceId == null || faceId == line.style.faceId) return line.style
        val face = faces.firstOrNull { it.id == faceId } ?: return line.style
        return TextAnalyzer.analyze(src, line.text, line.geo, faces, face)
    }
}
