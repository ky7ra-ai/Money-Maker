package com.ky7ra.fototeks

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Menghapus teks lama dengan mengisi area teks memakai warna latar di kiri dan kanan baris
 * (diinterpolasi per baris piksel, jadi gradasi halus ikut tertutup dengan baik),
 * sekaligus memperkirakan warna teks aslinya.
 */
object Inpaint {
    class Result(
        val patch: Bitmap,
        val left: Int,
        val top: Int,
        val textColor: Int,
        val bgColor: Int,
    )

    fun pad(g: Geo): Float = max(3f, g.h * 0.12f)

    fun build(src: Bitmap, g: Geo): Result {
        val q = g.quad(pad(g))
        val x0 = floor(min(min(q[0], q[2]), min(q[4], q[6]))).toInt().coerceIn(0, src.width - 1)
        val x1 = ceil(max(max(q[0], q[2]), max(q[4], q[6]))).toInt().coerceIn(x0, src.width - 1)
        val y0 = floor(min(min(q[1], q[3]), min(q[5], q[7]))).toInt().coerceIn(0, src.height - 1)
        val y1 = ceil(max(max(q[1], q[3]), max(q[5], q[7]))).toInt().coerceIn(y0, src.height - 1)
        val pw = x1 - x0 + 1
        val ph = y1 - y0 + 1

        // Baca area + margin kecil di sekelilingnya untuk mengambil sampel warna latar.
        val m = 4
        val rx0 = max(0, x0 - m)
        val rx1 = min(src.width - 1, x1 + m)
        val ry0 = max(0, y0 - m)
        val ry1 = min(src.height - 1, y1 + m)
        val rw = rx1 - rx0 + 1
        val rh = ry1 - ry0 + 1
        val px = IntArray(rw * rh)
        src.getPixels(px, 0, rw, rx0, ry0, rw, rh)

        fun at(x: Int, y: Int): Int = px[(y - ry0) * rw + (x - rx0)]

        // Warna cadangan: rata-rata piksel di luar kotak teks (atau seluruh area bila tidak ada).
        var fr = 0f
        var fg = 0f
        var fb = 0f
        var fn = 0
        for (y in ry0..ry1) {
            for (x in rx0..rx1) {
                if (x < x0 || x > x1 || y < y0 || y > y1) {
                    val c = at(x, y)
                    fr += Color.red(c)
                    fg += Color.green(c)
                    fb += Color.blue(c)
                    fn++
                }
            }
        }
        if (fn == 0) {
            for (c in px) {
                fr += Color.red(c)
                fg += Color.green(c)
                fb += Color.blue(c)
                fn++
            }
        }
        fr /= fn
        fg /= fn
        fb /= fn

        val lc = FloatArray(ph * 3)
        val rc = FloatArray(ph * 3)

        fun edge(xFrom: Int, xTo: Int, y: Int, dst: FloatArray, row: Int): Boolean {
            var r = 0f
            var gg = 0f
            var b = 0f
            var n = 0
            for (yy in (y - 1)..(y + 1)) {
                if (yy < ry0 || yy > ry1) continue
                for (xx in xFrom..xTo) {
                    if (xx < rx0 || xx > rx1) continue
                    val c = at(xx, yy)
                    r += Color.red(c)
                    gg += Color.green(c)
                    b += Color.blue(c)
                    n++
                }
            }
            if (n == 0) return false
            dst[row * 3] = r / n
            dst[row * 3 + 1] = gg / n
            dst[row * 3 + 2] = b / n
            return true
        }

        for (row in 0 until ph) {
            val y = y0 + row
            val hasL = edge(x0 - m, x0 - 1, y, lc, row)
            val hasR = edge(x1 + 1, x1 + m, y, rc, row)
            if (!hasL && !hasR) {
                lc[row * 3] = fr; lc[row * 3 + 1] = fg; lc[row * 3 + 2] = fb
                rc[row * 3] = fr; rc[row * 3 + 1] = fg; rc[row * 3 + 2] = fb
            } else if (!hasL) {
                for (k in 0 until 3) lc[row * 3 + k] = rc[row * 3 + k]
            } else if (!hasR) {
                for (k in 0 until 3) rc[row * 3 + k] = lc[row * 3 + k]
            }
        }

        // Isi patch: interpolasi horizontal antara warna kiri dan kanan di tiap baris.
        val out = IntArray(pw * ph)
        var sr = 0L
        var sg = 0L
        var sb = 0L
        for (row in 0 until ph) {
            val l0 = lc[row * 3]
            val l1 = lc[row * 3 + 1]
            val l2 = lc[row * 3 + 2]
            val r0 = rc[row * 3]
            val r1 = rc[row * 3 + 1]
            val r2 = rc[row * 3 + 2]
            for (col in 0 until pw) {
                val t = if (pw > 1) col / (pw - 1f) else 0f
                val r = (l0 + (r0 - l0) * t).roundToInt().coerceIn(0, 255)
                val gg = (l1 + (r1 - l1) * t).roundToInt().coerceIn(0, 255)
                val b = (l2 + (r2 - l2) * t).roundToInt().coerceIn(0, 255)
                out[row * pw + col] = Color.rgb(r, gg, b)
                sr += r
                sg += gg
                sb += b
            }
        }
        val total = (pw * ph).toLong()
        val bg = Color.rgb((sr / total).toInt(), (sg / total).toInt(), (sb / total).toInt())

        // Perkirakan warna teks: piksel di dalam kotak yang paling berbeda dari latar.
        val rad = Math.toRadians(-g.angle.toDouble())
        val c = cos(rad).toFloat()
        val s = sin(rad).toFloat()
        val dist = IntArray(pw * ph) { -1 }
        var maxD = 0
        for (row in 0 until ph) {
            for (col in 0 until pw) {
                val x = x0 + col
                val y = y0 + row
                val dx = x + 0.5f - g.cx
                val dy = y + 0.5f - g.cy
                val lx = dx * c - dy * s
                val ly = dx * s + dy * c
                if (abs(lx) > g.w / 2f || abs(ly) > g.h / 2f) continue
                val o = at(x, y)
                val b = out[row * pw + col]
                val d = abs(Color.red(o) - Color.red(b)) +
                    abs(Color.green(o) - Color.green(b)) +
                    abs(Color.blue(o) - Color.blue(b))
                dist[row * pw + col] = d
                if (d > maxD) maxD = d
            }
        }
        var tr = 0L
        var tg = 0L
        var tb = 0L
        var tn = 0
        if (maxD >= 60) {
            val thr = maxD * 0.6f
            for (row in 0 until ph) {
                for (col in 0 until pw) {
                    if (dist[row * pw + col] >= thr) {
                        val o = at(x0 + col, y0 + row)
                        tr += Color.red(o)
                        tg += Color.green(o)
                        tb += Color.blue(o)
                        tn++
                    }
                }
            }
        }
        val textColor = if (tn >= 4) {
            Color.rgb((tr / tn).toInt(), (tg / tn).toInt(), (tb / tn).toInt())
        } else {
            val lum = 0.299 * Color.red(bg) + 0.587 * Color.green(bg) + 0.114 * Color.blue(bg)
            if (lum > 140) Color.BLACK else Color.WHITE
        }

        val patch = Bitmap.createBitmap(out, pw, ph, Bitmap.Config.ARGB_8888)
        return Result(patch, x0, y0, textColor, bg)
    }
}

/** Menggambar hasil edit ke salinan foto asli. */
object Renderer {
    fun render(src: Bitmap, lines: List<TextLine>, edits: Map<Int, LineEdit>): Bitmap {
        val out = src.copy(Bitmap.Config.ARGB_8888, true)
        if (edits.isEmpty()) return out
        val canvas = Canvas(out)
        val byId = lines.associateBy { it.id }

        // Tahap 1: hapus semua teks lama (patch selalu dibuat dari foto asli).
        for (id in edits.keys) {
            val line = byId[id] ?: continue
            val g = line.geo
            val res = Inpaint.build(src, g)
            val q = g.quad(Inpaint.pad(g))
            val path = Path().apply {
                moveTo(q[0], q[1])
                lineTo(q[2], q[3])
                lineTo(q[4], q[5])
                lineTo(q[6], q[7])
                close()
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawBitmap(res.patch, res.left.toFloat(), res.top.toFloat(), null)
            canvas.restore()
        }

        // Tahap 2: gambar teks baru.
        for ((id, edit) in edits) {
            val line = byId[id] ?: continue
            drawText(canvas, src, line, edit)
        }
        return out
    }

    private fun drawText(canvas: Canvas, src: Bitmap, line: TextLine, e: LineEdit) {
        val g = line.geo
        val txt = e.text
        val orig = line.text
        if (txt.isBlank() || orig.isEmpty()) return

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = e.color ?: line.textColor
            typeface = if (e.bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            textSize = 100f
        }

        // Samakan ukuran huruf dengan teks asli (berdasarkan tinggi tinta teks asli).
        val ob = Rect()
        paint.getTextBounds(orig, 0, orig.length, ob)
        val oh = max(1, ob.height())
        val size = (100f * g.h / oh * 0.95f).coerceIn(g.h * 0.5f, g.h * 1.6f)
        paint.textSize = size

        // Samakan lebar huruf (agak lebih sempit/lebar dari font bawaan).
        val ow = paint.measureText(orig)
        val sx = if (ow > 1f) (g.w / ow).coerceIn(0.85f, 1.2f) else 1f
        paint.textScaleX = sx

        // Bila teks baru terlalu panjang, kecilkan agar muat.
        val nw = paint.measureText(txt)
        val maxW = min(g.w * 1.25f, src.width * 0.98f)
        if (nw > maxW && nw > 0f) {
            paint.textSize = size * maxW / nw
        }

        paint.getTextBounds(orig, 0, orig.length, ob)
        val inkCenter = (ob.top + ob.bottom) / 2f
        paint.textAlign = when (e.align) {
            1 -> Paint.Align.CENTER
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        val x = when (e.align) {
            1 -> g.cx
            2 -> g.cx + g.w / 2f
            else -> g.cx - g.w / 2f
        }
        val y = g.cy - inkCenter

        canvas.save()
        canvas.rotate(g.angle, g.cx, g.cy)
        canvas.drawText(txt, x, y, paint)
        canvas.restore()
    }
}
