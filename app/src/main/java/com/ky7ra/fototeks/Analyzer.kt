package com.ky7ra.fototeks

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import java.util.Arrays
import java.util.Random
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

class Bg(val r: FloatArray, val g: FloatArray, val b: FloatArray)

class Filled(val r: FloatArray, val g: FloatArray, val b: FloatArray)

class InkInfo(
    /** Peta cakupan tinta 0..1 (seukuran crop). */
    val a: FloatArray,
    val tc: FloatArray,
    val rx0: Int,
    val ry0: Int,
    val rx1: Int,
    val ry1: Int,
    val sBg: Float,
    val ok: Boolean,
)

/**
 * Menganalisis satu baris teks pada foto: meluruskan baris, memodelkan latar, memisahkan tinta,
 * lalu mencocokkan font dengan cara merender teks asli memakai tiap kandidat font dan
 * membandingkannya piksel demi piksel (ukuran, lebar, ketebalan, ketajaman tepi, posisi, warna).
 */
object TextAnalyzer {

    fun padFor(h: Float): Float = max(6f, 0.45f * h)

    fun stripFor(pad: Float, kx: Float): Int = max(3, (pad * 0.8f * kx).toInt())

    // ------------------------------------------------------------------------------------
    // Model latar
    // ------------------------------------------------------------------------------------

    /** Latar diperkirakan dari warna median di sisi kiri dan kanan kotak, diinterpolasi tiap baris. */
    fun rowLerpBg(c: Crop, bx0: Int, bx1: Int, strip: Int): Bg {
        val w = c.w
        val h = c.h
        val lx0 = max(0, bx0 - strip)
        val lx1 = max(lx0, min(bx0, w))
        val rx0 = min(w, max(bx1, 0))
        val rx1 = max(rx0, min(w, bx1 + strip))
        val lc = Array(3) { FloatArray(h) }
        val rc = Array(3) { FloatArray(h) }
        val hasL = BooleanArray(h)
        val hasR = BooleanArray(h)
        val cap = max(1, max(lx1 - lx0, rx1 - rx0)) * 3
        val sr = FloatArray(cap)
        val sg = FloatArray(cap)
        val sb = FloatArray(cap)

        fun med(x0: Int, x1: Int, y: Int, out: Array<FloatArray>, flag: BooleanArray) {
            var n = 0
            for (yy in max(0, y - 1)..min(h - 1, y + 1)) {
                for (xx in x0 until x1) {
                    val i = yy * w + xx
                    if (!c.v[i]) continue
                    sr[n] = c.r[i]
                    sg[n] = c.g[i]
                    sb[n] = c.b[i]
                    n++
                }
            }
            if (n == 0) {
                flag[y] = false
                return
            }
            Arrays.sort(sr, 0, n)
            Arrays.sort(sg, 0, n)
            Arrays.sort(sb, 0, n)
            out[0][y] = sr[n / 2]
            out[1][y] = sg[n / 2]
            out[2][y] = sb[n / 2]
            flag[y] = true
        }

        for (y in 0 until h) {
            med(lx0, lx1, y, lc, hasL)
            med(rx0, rx1, y, rc, hasR)
        }

        var fr = 0f
        var fg = 0f
        var fb = 0f
        var fn = 0
        for (y in 0 until h) {
            if (hasL[y]) {
                fr += lc[0][y]; fg += lc[1][y]; fb += lc[2][y]; fn++
            }
            if (hasR[y]) {
                fr += rc[0][y]; fg += rc[1][y]; fb += rc[2][y]; fn++
            }
        }
        if (fn == 0) {
            var i = 0
            while (i < w * h) {
                if (c.v[i]) {
                    fr += c.r[i]; fg += c.g[i]; fb += c.b[i]; fn++
                }
                i += 7
            }
        }
        if (fn == 0) {
            fr = 128f; fg = 128f; fb = 128f
        } else {
            fr /= fn; fg /= fn; fb /= fn
        }

        for (y in 0 until h) {
            if (hasL[y] && hasR[y]) continue
            if (hasL[y]) {
                for (k in 0..2) rc[k][y] = lc[k][y]
                continue
            }
            if (hasR[y]) {
                for (k in 0..2) lc[k][y] = rc[k][y]
                continue
            }
            var found = -1
            for (d in 1 until h) {
                val a = y - d
                val b2 = y + d
                if (a >= 0 && (hasL[a] || hasR[a])) {
                    found = a; break
                }
                if (b2 < h && (hasL[b2] || hasR[b2])) {
                    found = b2; break
                }
            }
            if (found < 0) {
                lc[0][y] = fr; lc[1][y] = fg; lc[2][y] = fb
                rc[0][y] = fr; rc[1][y] = fg; rc[2][y] = fb
            } else {
                for (k in 0..2) {
                    val v = if (hasL[found]) lc[k][found] else rc[k][found]
                    lc[k][y] = v
                    rc[k][y] = v
                }
            }
        }

        // Haluskan sepanjang sumbu y (jendela 5 baris).
        val sl = Array(3) { FloatArray(h) }
        val srr = Array(3) { FloatArray(h) }
        for (k in 0..2) {
            for (y in 0 until h) {
                var a = 0f
                var b = 0f
                var n = 0
                for (yy in max(0, y - 2)..min(h - 1, y + 2)) {
                    a += lc[k][yy]; b += rc[k][yy]; n++
                }
                sl[k][y] = a / n
                srr[k][y] = b / n
            }
        }

        val xl = if (lx1 > lx0) (lx0 + lx1 - 1) / 2f else bx0.toFloat()
        val xr = if (rx1 > rx0) (rx0 + rx1 - 1) / 2f else bx1.toFloat()
        val span = max(1f, xr - xl)
        val outR = FloatArray(w * h)
        val outG = FloatArray(w * h)
        val outB = FloatArray(w * h)
        for (y in 0 until h) {
            val l0 = sl[0][y]; val l1 = sl[1][y]; val l2 = sl[2][y]
            val r0 = srr[0][y]; val r1 = srr[1][y]; val r2 = srr[2][y]
            for (x in 0 until w) {
                var t = (x - xl) / span
                if (t < 0f) t = 0f else if (t > 1f) t = 1f
                val i = y * w + x
                outR[i] = l0 + (r0 - l0) * t
                outG[i] = l1 + (r1 - l1) * t
                outB[i] = l2 + (r2 - l2) * t
            }
        }
        return Bg(outR, outG, outB)
    }

    fun averageBg(bg: Bg, w: Int, box: IntArray): Int {
        var r = 0f
        var g = 0f
        var b = 0f
        var n = 0
        val ys = max(0, box[1])
        val ye = min(bg.r.size / w, box[3])
        var y = ys
        while (y < ye) {
            var x = max(0, box[0])
            while (x < min(w, box[2])) {
                val i = y * w + x
                r += bg.r[i]; g += bg.g[i]; b += bg.b[i]; n++
                x += 3
            }
            y += 2
        }
        if (n == 0) return Color.GRAY
        return Color.rgb((r / n).roundToInt().coerceIn(0, 255), (g / n).roundToInt().coerceIn(0, 255), (b / n).roundToInt().coerceIn(0, 255))
    }

    // ------------------------------------------------------------------------------------
    // Pemisahan tinta
    // ------------------------------------------------------------------------------------

    fun computeA(
        c: Crop, bg: Bg, tc: FloatArray, sBg: Float,
        rx0: Int, ry0: Int, rx1: Int, ry1: Int,
    ): FloatArray {
        val w = c.w
        val a = FloatArray(w * c.h)
        for (y in ry0 until ry1) {
            for (x in rx0 until rx1) {
                val i = y * w + x
                if (!c.v[i]) continue
                val ur = tc[0] - bg.r[i]
                val ug = tc[1] - bg.g[i]
                val ub = tc[2] - bg.b[i]
                val den = ur * ur + ug * ug + ub * ub
                if (den < 150f) continue
                val num = (c.r[i] - bg.r[i]) * ur + (c.g[i] - bg.g[i]) * ug + (c.b[i] - bg.b[i]) * ub
                val aa = num / den
                val l1 = abs(ur) + abs(ug) + abs(ub)
                val thr = max(0.06f, 2.5f * sBg / max(l1, 1f))
                if (aa >= thr) a[i] = min(aa, 1f)
            }
        }
        return a
    }

    fun analyzeInk(c: Crop, bg: Bg, box: IntArray, strip: Int, fixedTc: FloatArray?): InkInfo {
        val w = c.w
        val h = c.h
        val bx0 = max(0, box[0])
        val bx1 = min(w, box[2])
        val by0 = max(0, box[1])
        val by1 = min(h, box[3])
        val bh = max(1, by1 - by0)
        val ex = max(2, (0.3f * bh).roundToInt())
        val ey = max(2, (0.25f * bh).roundToInt())
        val rx0 = max(0, bx0 - ex)
        val rx1 = min(w, bx1 + ex)
        val ry0 = max(0, by0 - ey)
        val ry1 = min(h, by1 + ey)

        var ss = 0.0
        var sn = 0
        for (y in by0 until by1) {
            for (x in max(0, bx0 - strip) until bx0) {
                val i = y * w + x
                if (!c.v[i]) continue
                val d = abs(c.r[i] - bg.r[i]) + abs(c.g[i] - bg.g[i]) + abs(c.b[i] - bg.b[i])
                ss += (d * d).toDouble(); sn++
            }
            for (x in bx1 until min(w, bx1 + strip)) {
                val i = y * w + x
                if (!c.v[i]) continue
                val d = abs(c.r[i] - bg.r[i]) + abs(c.g[i] - bg.g[i]) + abs(c.b[i] - bg.b[i])
                ss += (d * d).toDouble(); sn++
            }
        }
        val sBg = if (sn > 0) sqrt(ss / sn).toFloat() else 8f

        val tc = FloatArray(3)
        var ok = true
        if (fixedTc != null) {
            tc[0] = fixedTc[0]; tc[1] = fixedTc[1]; tc[2] = fixedTc[2]
        } else {
            val hist = IntArray(766)
            var tot = 0
            for (y in by0 until by1) {
                for (x in bx0 until bx1) {
                    val i = y * w + x
                    if (!c.v[i]) continue
                    val d = abs(c.r[i] - bg.r[i]) + abs(c.g[i] - bg.g[i]) + abs(c.b[i] - bg.b[i])
                    hist[min(765, d.toInt())]++
                    tot++
                }
            }
            var dRef = 0
            if (tot > 0) {
                val target = (tot * 0.997).toInt()
                var acc = 0
                for (k in 0..765) {
                    acc += hist[k]
                    if (acc >= target) {
                        dRef = k
                        break
                    }
                }
            }
            if (tot == 0 || dRef < max(36f, 5f * sBg)) ok = false
            var tn = 0
            var tr = 0.0
            var tg = 0.0
            var tb = 0.0
            if (ok) {
                val thr = 0.85f * dRef
                for (y in by0 until by1) {
                    for (x in bx0 until bx1) {
                        val i = y * w + x
                        if (!c.v[i]) continue
                        val d = abs(c.r[i] - bg.r[i]) + abs(c.g[i] - bg.g[i]) + abs(c.b[i] - bg.b[i])
                        if (d >= thr) {
                            tr += c.r[i]; tg += c.g[i]; tb += c.b[i]; tn++
                        }
                    }
                }
            }
            if (tn < 3) {
                ok = false
                val cx = ((bx0 + bx1) / 2).coerceIn(0, w - 1)
                val cy = ((by0 + by1) / 2).coerceIn(0, h - 1)
                val i = cy * w + cx
                val lum = 0.299f * bg.r[i] + 0.587f * bg.g[i] + 0.114f * bg.b[i]
                val v = if (lum > 140f) 0f else 255f
                tc[0] = v; tc[1] = v; tc[2] = v
            } else {
                tc[0] = (tr / tn).toFloat(); tc[1] = (tg / tn).toFloat(); tc[2] = (tb / tn).toFloat()
            }
        }
        val a = if (ok) computeA(c, bg, tc, sBg, rx0, ry0, rx1, ry1) else FloatArray(w * h)
        return InkInfo(a, tc, rx0, ry0, rx1, ry1, sBg, ok)
    }

    /** Kotak pembatas tinta (x0,y0,x1,y1 dengan x1,y1 eksklusif), atau null bila tak ada tinta. */
    fun inkBBox(a: FloatArray, w: Int, h: Int, rx0: Int, ry0: Int, rx1: Int, ry1: Int): IntArray? {
        var x0 = Int.MAX_VALUE
        var y0 = Int.MAX_VALUE
        var x1 = -1
        var y1 = -1
        for (y in ry0 until ry1) {
            for (x in rx0 until rx1) {
                val i = y * w + x
                if (a[i] < 0.5f) continue
                var s = 0
                if (x > 0 && a[i - 1] >= 0.35f) s++
                if (x < w - 1 && a[i + 1] >= 0.35f) s++
                if (y > 0 && a[i - w] >= 0.35f) s++
                if (y < h - 1 && a[i + w] >= 0.35f) s++
                if (s < 1) continue
                if (x < x0) x0 = x
                if (x > x1) x1 = x
                if (y < y0) y0 = y
                if (y > y1) y1 = y
            }
        }
        if (x1 < 0) return null
        return intArrayOf(x0, y0, x1 + 1, y1 + 1)
    }

    /** Topeng piksel yang akan dihapus: tinta ditambah pelebaran kecil agar tepi anti-alias ikut hilang. */
    fun inkMask(info: InkInfo, w: Int, h: Int, dil: Int): BooleanArray {
        val m = BooleanArray(w * h)
        for (y in info.ry0 until info.ry1) {
            for (x in info.rx0 until info.rx1) {
                val i = y * w + x
                if (info.a[i] >= 0.1f) m[i] = true
            }
        }
        return Imaging.dilate(m, w, h, dil)
    }

    /** Mengisi piksel bertopeng secara harmonik dari sekelilingnya, lalu menambahkan butiran noise. */
    fun harmonicFill(c: Crop, bg: Bg, mask: BooleanArray, noise: Float, rnd: Random): Filled {
        val w = c.w
        val h = c.h
        val r = c.r.copyOf()
        val g = c.g.copyOf()
        val b = c.b.copyOf()
        var any = false
        for (i in 0 until w * h) {
            if (mask[i] && c.v[i]) {
                r[i] = bg.r[i]; g[i] = bg.g[i]; b[i] = bg.b[i]
                any = true
            }
        }
        if (!any) return Filled(r, g, b)
        val omega = 1.5f
        repeat(30) {
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val i = y * w + x
                    if (!mask[i] || !c.v[i]) continue
                    var n = 0
                    var sr = 0f
                    var sg = 0f
                    var sb = 0f
                    if (x > 0 && c.v[i - 1]) {
                        sr += r[i - 1]; sg += g[i - 1]; sb += b[i - 1]; n++
                    }
                    if (x < w - 1 && c.v[i + 1]) {
                        sr += r[i + 1]; sg += g[i + 1]; sb += b[i + 1]; n++
                    }
                    if (y > 0 && c.v[i - w]) {
                        sr += r[i - w]; sg += g[i - w]; sb += b[i - w]; n++
                    }
                    if (y < h - 1 && c.v[i + w]) {
                        sr += r[i + w]; sg += g[i + w]; sb += b[i + w]; n++
                    }
                    if (n == 0) continue
                    r[i] += omega * (sr / n - r[i])
                    g[i] += omega * (sg / n - g[i])
                    b[i] += omega * (sb / n - b[i])
                }
            }
        }
        for (i in 0 until w * h) {
            if (!mask[i] || !c.v[i]) continue
            val nz = if (noise > 0.05f) (rnd.nextGaussian() * noise).toFloat() else 0f
            r[i] = (r[i] + nz).coerceIn(0f, 255f)
            g[i] = (g[i] + nz).coerceIn(0f, 255f)
            b[i] = (b[i] + nz).coerceIn(0f, 255f)
        }
        return Filled(r, g, b)
    }

    // ------------------------------------------------------------------------------------
    // Noise
    // ------------------------------------------------------------------------------------

    /** Deviasi standar noise luminans dari area sekitar teks (estimator MAD, tahan terhadap tepi). */
    fun estimateNoise(src: Bitmap, geo: Geo): Float {
        val p = padFor(geo.h)
        val spec = CropSpec.make(geo, p, p, p, p, 1f)
        val c = buildCrop(src, spec)
        val box = spec.box()
        val w = c.w
        val h = c.h
        if (w < 5 || h < 5) return 0f
        val total = w * h
        val step = max(1, total / 60000)
        val buf = FloatArray(total / step + 8)
        var n = 0
        var cnt = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val outside = y < box[1] - 2 || y >= box[3] + 2 || x < box[0] - 2 || x >= box[2] + 2
                if (!outside) continue
                cnt++
                if (cnt % step != 0) continue
                val i = y * w + x
                if (!(c.v[i] && c.v[i - 1] && c.v[i + 1] && c.v[i - w] && c.v[i + w])) continue
                val yc = (c.r[i] + c.g[i] + c.b[i]) / 3f
                val y1 = (c.r[i - 1] + c.g[i - 1] + c.b[i - 1]) / 3f
                val y2 = (c.r[i + 1] + c.g[i + 1] + c.b[i + 1]) / 3f
                val y3 = (c.r[i - w] + c.g[i - w] + c.b[i - w]) / 3f
                val y4 = (c.r[i + w] + c.g[i + w] + c.b[i + w]) / 3f
                if (n < buf.size) buf[n++] = abs(yc - (y1 + y2 + y3 + y4) / 4f)
            }
        }
        if (n < 40) return 0f
        Arrays.sort(buf, 0, n)
        val med = buf[n / 2]
        return (1.4826f * med / 1.118f).coerceIn(0f, 25f)
    }

    // ------------------------------------------------------------------------------------
    // Pencocokan font
    // ------------------------------------------------------------------------------------

    private class FB(val face: FontFace, val bl: Float, val bt: Float, val br: Float, val bb: Float)

    /** Parameter: [ukuran, skala-x, geser-x, geser-y, blur]. */
    private class Fit(val fb: FB, var p: FloatArray, var err: Float)

    private class Fitter(
        val c: Crop,
        val bg: Bg,
        a: FloatArray,
        val rx0: Int, val ry0: Int, val rx1: Int, val ry1: Int,
        val text: String,
        val ix0: Int, val iy0: Int, val ix1: Int, val iy1: Int,
    ) {
        val rw = rx1 - rx0
        val rh = ry1 - ry0
        val renderer = MaskRenderer(rw, rh)
        val mask = FloatArray(rw * rh)
        val tmp = FloatArray(rw * rh)
        val aReg = FloatArray(rw * rh)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply { textSize = 100f }
        private val rect = Rect()

        init {
            setA(a)
        }

        fun setA(a: FloatArray) {
            for (y in 0 until rh) {
                for (x in 0 until rw) aReg[y * rw + x] = a[(ry0 + y) * c.w + rx0 + x]
            }
        }

        fun bounds(face: FontFace): FB {
            paint.typeface = face.typeface
            paint.getTextBounds(text, 0, text.length, rect)
            return FB(face, rect.left.toFloat(), rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat())
        }

        fun initial(fb: FB): FloatArray {
            val inkW = (ix1 - ix0).toFloat()
            val inkH = (iy1 - iy0).toFloat()
            val bh = max(1f, fb.bb - fb.bt)
            val bw = max(1f, fb.br - fb.bl)
            val s0 = 100f * inkH / bh
            val sx0 = ((inkW / inkH) / (bw / bh)).coerceIn(0.6f, 1.6f)
            return floatArrayOf(s0, sx0, 0f, 0f, 0.8f)
        }

        fun renderMask(fb: FB, p: FloatArray) {
            val scale = p[0] / 100f
            val ox = (ix0 - rx0) - fb.bl * scale * p[1] + p[2]
            val base = (iy0 - ry0) - fb.bt * scale + p[3]
            renderer.render(text, fb.face.typeface, p[0], p[1], ox, base, mask)
            Imaging.blur(mask, rw, rh, p[4], tmp)
        }

        fun eval(fb: FB, p: FloatArray): Float {
            renderMask(fb, p)
            var sumAbs = 0.0
            var sa = 0.0
            var sm = 0.0
            for (i in 0 until rw * rh) {
                val a = aReg[i]
                val m = mask[i]
                sumAbs += abs(a - m)
                sa += a
                sm += m
            }
            return (2.0 * sumAbs / (sa + sm + 1e-3)).toFloat()
        }

        private fun tweak(p: FloatArray, idx: Int, dir: Float, s: Float): FloatArray {
            val q = p.copyOf()
            when (idx) {
                0 -> q[0] = p[0] * (1f + 0.03f * dir * s)
                1 -> q[1] = p[1] * (1f + 0.03f * dir * s)
                2 -> q[2] = p[2] + 0.6f * dir * s
                3 -> q[3] = p[3] + 0.6f * dir * s
                else -> q[4] = max(0f, p[4] + 0.4f * dir * s)
            }
            q[1] = q[1].coerceIn(0.6f, 1.6f)
            return q
        }

        fun refine(f: Fit, rounds: Int) {
            var s = 1f
            repeat(rounds) {
                for (idx in 0..4) {
                    for (dir in floatArrayOf(-1f, 1f)) {
                        val q = tweak(f.p, idx, dir, s)
                        val e = eval(f.fb, q)
                        if (e < f.err) {
                            f.err = e
                            f.p = q
                        }
                    }
                }
                s *= 0.5f
            }
        }

        /** Warna teks terbaik (kuadrat terkecil) untuk bentuk yang sedang dicocokkan. */
        fun lsColor(f: Fit): FloatArray? {
            renderMask(f.fb, f.p)
            var nr = 0.0
            var ng = 0.0
            var nb = 0.0
            var den = 0.0
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    val m = mask[y * rw + x]
                    if (m < 0.02f) continue
                    val ci = (ry0 + y) * c.w + rx0 + x
                    if (!c.v[ci]) continue
                    nr += m * (c.r[ci] - bg.r[ci] * (1f - m))
                    ng += m * (c.g[ci] - bg.g[ci] * (1f - m))
                    nb += m * (c.b[ci] - bg.b[ci] * (1f - m))
                    den += (m * m).toDouble()
                }
            }
            if (den < 1.0) return null
            return floatArrayOf(
                (nr / den).toFloat().coerceIn(0f, 255f),
                (ng / den).toFloat().coerceIn(0f, 255f),
                (nb / den).toFloat().coerceIn(0f, 255f),
            )
        }

        /**
         * Warna teks dari piksel inti huruf: tempat bentuk hasil cocokan menutup penuh (>= 0.9) dan
         * peta tinta asli juga yakin (>= 0.5). Tidak terpengaruh bila font belum tepat benar.
         */
        fun coreColor(f: Fit): FloatArray? {
            renderMask(f.fb, f.p)
            var n = 0
            var sr = 0.0
            var sg = 0.0
            var sb = 0.0
            for (y in 0 until rh) {
                for (x in 0 until rw) {
                    val i = y * rw + x
                    if (mask[i] < 0.9f || aReg[i] < 0.5f) continue
                    val ci = (ry0 + y) * c.w + rx0 + x
                    if (!c.v[ci]) continue
                    sr += c.r[ci]; sg += c.g[ci]; sb += c.b[ci]; n++
                }
            }
            if (n < 6) return null
            return floatArrayOf((sr / n).toFloat(), (sg / n).toFloat(), (sb / n).toFloat())
        }

        fun release() = renderer.release()
    }

    private fun fallbackStyle(geo: Geo, color: Int, bgColor: Int, noise: Float): LineStyle {
        val face = FontLibrary.fallback()
        return LineStyle(
            faceId = face.id,
            sizeL = geo.h * 1.1f,
            sx = 1f,
            blurL = 0.6f,
            inkLeftL = 0f,
            inkRightL = geo.w,
            baselineL = geo.h * 0.82f,
            color = color,
            bgColor = bgColor,
            noise = noise,
            error = 2f,
            alternatives = emptyList(),
            reliable = false,
        )
    }

    fun analyze(src: Bitmap, text: String, geo: Geo, faces: List<FontFace>, forced: FontFace? = null): LineStyle {
        return try {
            run(src, text, geo, faces, forced)
        } catch (e: Exception) {
            fallbackStyle(geo, Color.BLACK, Color.WHITE, 0f)
        }
    }

    private fun run(src: Bitmap, text: String, geo: Geo, faces: List<FontFace>, forced: FontFace?): LineStyle {
        val t = text.trim()
        val pad = padFor(geo.h)
        val ka = min(1f, 56f / geo.h).coerceAtLeast(0.2f)
        val spec = CropSpec.make(geo, pad, pad, pad, pad, ka)
        val crop = buildCrop(src, spec)
        val box = spec.box()
        val strip = stripFor(pad, spec.kx)
        val bg = rowLerpBg(crop, box[0], box[2], strip)
        val noise = estimateNoise(src, geo)
        val bgInt = averageBg(bg, crop.w, box)
        val ink = analyzeInk(crop, bg, box, strip, null)
        val tcInt = Color.rgb(
            ink.tc[0].roundToInt().coerceIn(0, 255),
            ink.tc[1].roundToInt().coerceIn(0, 255),
            ink.tc[2].roundToInt().coerceIn(0, 255),
        )
        if (!ink.ok || t.isEmpty() || faces.isEmpty()) return fallbackStyle(geo, tcInt, bgInt, noise)
        val bb = inkBBox(ink.a, crop.w, crop.h, ink.rx0, ink.ry0, ink.rx1, ink.ry1)
        if (bb == null || bb[2] - bb[0] < 3 || bb[3] - bb[1] < 3) return fallbackStyle(geo, tcInt, bgInt, noise)

        val fitter = Fitter(
            crop, bg, ink.a, ink.rx0, ink.ry0, ink.rx1, ink.ry1, t, bb[0], bb[1], bb[2], bb[3],
        )
        try {
            val inkW = (bb[2] - bb[0]).toFloat()
            val inkH = (bb[3] - bb[1]).toFloat()

            val cand: List<FB> = if (forced != null) {
                listOf(fitter.bounds(forced))
            } else {
                faces.map { f ->
                    val fb = fitter.bounds(f)
                    val bw = fb.br - fb.bl
                    val bh = fb.bb - fb.bt
                    val sx0 = if (bw > 1f && bh > 1f) (inkW / inkH) / (bw / bh) else 99f
                    Pair(fb, abs(ln(max(sx0, 0.01f))))
                }.sortedBy { it.second }.take(100).map { it.first }
            }

            val fits = cand.map { fb ->
                val p = fitter.initial(fb)
                Fit(fb, p, fitter.eval(fb, p))
            }.sortedBy { it.err }

            // Putaran 1: haluskan parameter pada kandidat terbaik.
            // Tahap 2: setel posisi/ukuran 24 kandidat teratas satu putaran, agar font yang benar
            // tidak tersisih hanya karena posisi awalnya meleset 1-2 piksel.
            val wide = fits.take(40)
            for (f in wide) fitter.refine(f, 1)
            val top = wide.sortedBy { it.err }.take(8)
            for (f in top) fitter.refine(f, 3)
            var best = top.minByOrNull { it.err }!!

            // Koreksi warna teks dengan kuadrat terkecil, hitung ulang peta tinta, lalu putaran 2.
            // Bila bentuk font sudah sangat cocok, kuadrat terkecil paling akurat; bila belum,
            // pakai piksel inti huruf yang tidak bergantung pada ketepatan font.
            fun pickColor(f: Fit, fallback: FloatArray): FloatArray =
                (if (f.err < 0.3f) fitter.lsColor(f) else null)
                    ?: fitter.coreColor(f)
                    ?: fallback

            val tc1 = pickColor(best, ink.tc)
            val a2 = computeA(crop, bg, tc1, ink.sBg, ink.rx0, ink.ry0, ink.rx1, ink.ry1)
            fitter.setA(a2)
            for (f in top) {
                f.err = fitter.eval(f.fb, f.p)
                fitter.refine(f, 2)
            }
            best = top.minByOrNull { it.err }!!
            val tcFinal = pickColor(best, tc1)

            val scale = best.p[0] / 100f
            val sx = best.p[1]
            val oxC = bb[0] + best.p[2] - best.fb.bl * scale * sx
            val baseC = bb[1] - best.fb.bt * scale + best.p[3]
            val inkLeftC = bb[0] + best.p[2]
            val inkRightC = oxC + best.fb.br * scale * sx

            val alternatives = ArrayList<String>()
            for (f in top.sortedBy { it.err }) alternatives += f.fb.face.id
            for (f in fits) if (f.fb.face.id !in alternatives) alternatives += f.fb.face.id
            val altTop = alternatives.take(8)

            val color = Color.rgb(
                tcFinal[0].roundToInt().coerceIn(0, 255),
                tcFinal[1].roundToInt().coerceIn(0, 255),
                tcFinal[2].roundToInt().coerceIn(0, 255),
            )
            return LineStyle(
                faceId = best.fb.face.id,
                sizeL = best.p[0] / spec.ky,
                sx = sx,
                blurL = best.p[4] / spec.kx,
                inkLeftL = inkLeftC / spec.kx - spec.padL,
                inkRightL = inkRightC / spec.kx - spec.padL,
                baselineL = baseC / spec.ky - spec.padT,
                color = color,
                bgColor = bgInt,
                noise = noise,
                error = best.err,
                alternatives = altTop,
                reliable = best.err < 1.0f,
            )
        } finally {
            fitter.release()
        }
    }
}
