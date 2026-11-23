package com.ky7ra.fototeks

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Random
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.min

/**
 * Uji visual end-to-end: membuat foto sintetis dengan font yang diketahui, menjalankan OCR dan
 * analisis gaya seperti di aplikasi, mengganti teks, lalu menyimpan gambar sebelum/sesudah
 * beserta laporan font yang terdeteksi ke folder keluaran untuk diperiksa.
 */
@RunWith(AndroidJUnit4::class)
class PipelineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val outDir = File(ctx.getExternalFilesDir(null), "out").apply { mkdirs() }
    private val report = StringBuilder()

    private class SL(
        val text: String,
        val face: String,
        val size: Float,
        val color: Int,
        val x: Float,
        val y: Float,
        val rot: Float = 0f,
    )

    private class Scene(
        val name: String,
        val w: Int,
        val h: Int,
        val bgTop: Int,
        val bgBottom: Int,
        val lines: List<SL>,
        val blur: Float,
        val noise: Float,
        val jpeg: Int,
        val newTexts: List<String>,
    )

    private fun buildScenes(): List<Scene> = listOf(
        Scene(
            "poster", 1100, 620, 0xFF1B3A6B.toInt(), 0xFF6B2D8C.toInt(),
            listOf(
                SL("SALE 50% OFF", "Montserrat Bold", 84f, Color.WHITE, 60f, 130f),
                SL("Limited time only", "Lato", 44f, Color.WHITE, 60f, 220f),
                SL("Fresh Coffee Roasters", "Playfair Display Bold", 52f, Color.WHITE, 60f, 330f),
                SL("Open daily 8am - 9pm", "Open Sans", 36f, 0xFFFFE9A8.toInt(), 60f, 420f),
                SL("Special Offer", "Pacifico", 54f, Color.WHITE, 600f, 540f, -6f),
            ),
            0.3f, 2f, 92,
            listOf("SALE 70% OFF", "Open every day now", "Handmade Cookies Daily", "Open daily 7am - 10pm", "Big Discount"),
        ),
        Scene(
            "photo", 1100, 700, 0xFFE8D8B8.toInt(), 0xFFC9B38A.toInt(),
            listOf(
                SL("Welcome to Jakarta", "Merriweather Bold", 56f, 0xFF3B2A1A.toInt(), 80f, 150f),
                SL("Best nasi goreng in town", "Sans Sistem", 38f, 0xFF3B2A1A.toInt(), 80f, 240f),
                SL("Call 0812 3456 7890", "Roboto Mono", 34f, 0xFF3B2A1A.toInt(), 80f, 330f),
                SL("Since 1998", "Oswald Medium", 64f, 0xFF7A1F1F.toInt(), 80f, 450f),
                SL("Family Restaurant", "Lora Bold", 46f, 0xFF3B2A1A.toInt(), 500f, 560f, 4f),
            ),
            1.0f, 6f, 80,
            listOf("Welcome to Bandung", "Best mie ayam in town", "Call 0857 1111 2222", "Since 2005", "Family Kitchen"),
        ),
        Scene(
            "receipt", 700, 800, 0xFFF7F7F2.toInt(), 0xFFF2F2EC.toInt(),
            listOf(
                SL("TOKO MAJU JAYA", "Cousine Bold", 40f, 0xFF111111.toInt(), 60f, 110f),
                SL("Jl. Merdeka No. 12", "Cousine", 28f, 0xFF111111.toInt(), 60f, 170f),
                SL("TOTAL   Rp 125.000", "Cousine Bold", 32f, 0xFF111111.toInt(), 60f, 300f),
                SL("Terima kasih", "Cousine", 28f, 0xFF111111.toInt(), 60f, 400f),
            ),
            0.5f, 3f, 85,
            listOf("TOKO SINAR ABADI", "Jl. Sudirman No. 88", "TOTAL   Rp 980.500", "Sampai jumpa"),
        ),
        Scene(
            "dark", 900, 500, 0xFF101820.toInt(), 0xFF203040.toInt(),
            listOf(
                SL("NIGHT MARKET", "Bebas Neue", 96f, 0xFFF5F5F5.toInt(), 50f, 120f),
                SL("Open until midnight", "Nunito", 40f, 0xFFDDDDDD.toInt(), 50f, 210f),
                SL("Tickets Rp 50.000", "Poppins Medium", 38f, 0xFFFFC857.toInt(), 50f, 300f),
            ),
            0.6f, 4f, 85,
            listOf("DAY MARKET", "Open until sunset", "Tickets Rp 75.000"),
        ),
    )

    @Test
    fun scenes() {
        val faces = FontLibrary.all(ctx)
        report.appendLine("faces=${faces.size} labels=${faces.map { it.label }.distinct().size}")
        report.appendLine("families: " + faces.map { it.family }.distinct().sorted().joinToString(", "))
        try {
            for (s in buildScenes()) {
                try {
                    runScene(s, faces)
                } catch (e: Throwable) {
                    report.appendLine("SCENE ${s.name} GAGAL: $e\n${e.stackTraceToString()}")
                }
            }
        } finally {
            File(outDir, "report.txt").writeText(report.toString())
        }
    }

    private fun findFace(faces: List<FontFace>, label: String): FontFace {
        val f = faces.firstOrNull { it.label.equals(label, true) }
        if (f == null) report.appendLine("  (font '$label' tidak ada, pakai pengganti)")
        return f ?: FontLibrary.fallback()
    }

    private fun draw(s: Scene, faces: List<FontFace>): Bitmap {
        val bmp = Bitmap.createBitmap(s.w, s.h, Bitmap.Config.ARGB_8888)
        val cv = Canvas(bmp)
        val bgp = Paint().apply {
            shader = LinearGradient(0f, 0f, s.w * 0.3f, s.h.toFloat(), s.bgTop, s.bgBottom, Shader.TileMode.CLAMP)
        }
        cv.drawRect(0f, 0f, s.w.toFloat(), s.h.toFloat(), bgp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
        for (l in s.lines) {
            val f = findFace(faces, l.face)
            p.typeface = f.typeface
            p.textSize = l.size
            p.color = l.color
            cv.save()
            cv.rotate(l.rot, l.x, l.y)
            cv.drawText(l.text, l.x, l.y, p)
            cv.restore()
            report.appendLine("TRUTH: '${l.text}' font='${f.label}' size=${l.size} color=#${Integer.toHexString(l.color)} rot=${l.rot}")
        }
        return bmp
    }

    private fun softBlur(b: Bitmap, amount: Float): Bitmap {
        if (amount <= 0.05f) return b
        val f = 1f + amount
        val small = Bitmap.createScaledBitmap(b, max(1, (b.width / f).toInt()), max(1, (b.height / f).toInt()), true)
        return Bitmap.createScaledBitmap(small, b.width, b.height, true)
    }

    private fun addNoise(b: Bitmap, sigma: Float, seed: Long): Bitmap {
        if (sigma <= 0f) return b
        val out = b.copy(Bitmap.Config.ARGB_8888, true)
        val rnd = Random(seed)
        val px = IntArray(out.width * out.height)
        out.getPixels(px, 0, out.width, 0, 0, out.width, out.height)
        for (i in px.indices) {
            val n = (rnd.nextGaussian() * sigma).toFloat()
            val c = px[i]
            val r = (Color.red(c) + n).toInt().coerceIn(0, 255)
            val g = (Color.green(c) + n).toInt().coerceIn(0, 255)
            val bl = (Color.blue(c) + n).toInt().coerceIn(0, 255)
            px[i] = Color.rgb(r, g, bl)
        }
        out.setPixels(px, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    private fun jpegRound(b: Bitmap, q: Int): Bitmap {
        val bos = ByteArrayOutputStream()
        b.compress(Bitmap.CompressFormat.JPEG, q, bos)
        val bytes = bos.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size).copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun savePng(b: Bitmap, name: String) {
        FileOutputStream(File(outDir, name)).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun zoom(orig: Bitmap, edit: Bitmap, g: Geo): Bitmap {
        val m = (g.h * 0.6f).toInt() + 6
        val q = g.padded(m.toFloat())
        val x0 = max(0, min(min(q[0], q[2]), min(q[4], q[6])).toInt())
        val y0 = max(0, min(min(q[1], q[3]), min(q[5], q[7])).toInt())
        val x1 = min(orig.width, max(max(q[0], q[2]), max(q[4], q[6])).toInt())
        val y1 = min(orig.height, max(max(q[1], q[3]), max(q[5], q[7])).toInt())
        val w = max(2, x1 - x0)
        val h = max(2, y1 - y0)
        val a = Bitmap.createBitmap(orig, x0, y0, w, h)
        val b = Bitmap.createBitmap(edit, x0, y0, w, h)
        val k = max(1, min(3, 1000 / w))
        val out = Bitmap.createBitmap(w * k, h * k * 2 + 6, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.MAGENTA)
        val p = Paint()
        cv.drawBitmap(Bitmap.createScaledBitmap(a, w * k, h * k, false), 0f, 0f, p)
        cv.drawBitmap(Bitmap.createScaledBitmap(b, w * k, h * k, false), 0f, (h * k + 6).toFloat(), p)
        return out
    }

    private fun runScene(s: Scene, faces: List<FontFace>) {
        report.appendLine("=== scene ${s.name} ===")
        var bmp = draw(s, faces)
        bmp = softBlur(bmp, s.blur)
        bmp = addNoise(bmp, s.noise, 42L)
        bmp = jpegRound(bmp, s.jpeg)
        savePng(bmp, "${s.name}_orig.png")

        val rec = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val text = Tasks.await(rec.process(InputImage.fromBitmap(bmp, 0)), 120, TimeUnit.SECONDS)
        rec.close()

        val t0 = System.currentTimeMillis()
        val lines = runBlocking { Pipeline.analyze(bmp, text, faces) }
        report.appendLine("analisis ${lines.size} baris: ${System.currentTimeMillis() - t0} ms")

        for (l in lines) {
            val st = l.style
            val f = FontLibrary.byId(st.faceId)
            val alts = st.alternatives.take(5).joinToString(", ") { FontLibrary.byId(it)?.label ?: it }
            report.appendLine(
                "[${l.id}] '${l.text}' box=${l.geo.w.toInt()}x${l.geo.h.toInt()} angle=${"%.1f".format(l.geo.angle)} " +
                    "-> font='${f?.label}' err=${"%.3f".format(st.error)} reliable=${st.reliable} " +
                    "size=${"%.1f".format(st.sizeL)} sx=${"%.2f".format(st.sx)} blur=${"%.2f".format(st.blurL)} " +
                    "noise=${"%.2f".format(st.noise)} color=#${Integer.toHexString(st.color)} bg=#${Integer.toHexString(st.bgColor)} alt=[$alts]",
            )
        }

        // Diagnostik: seberapa kecil galat bila font yang benar dipaksakan?
        for ((i, l) in lines.withIndex()) {
            if (i >= s.lines.size) break
            val tf = faces.firstOrNull { it.label.equals(s.lines[i].face, true) } ?: continue
            val fs = TextAnalyzer.analyze(bmp, l.text, l.geo, faces, tf)
            report.appendLine("   [$i] dipaksa font benar '${tf.label}': err=${"%.3f".format(fs.error)} (terpilih: ${"%.3f".format(l.style.error)})")
        }

        val edits = HashMap<Int, LineEdit>()
        for ((i, l) in lines.withIndex()) {
            if (i < s.newTexts.size) edits[l.id] = LineEdit(s.newTexts[i], null, l.defaultAlign, null, 1f)
        }
        val t1 = System.currentTimeMillis()
        val result = Renderer.render(bmp, lines, edits) { line, faceId -> Pipeline.styleFor(bmp, line, faceId, faces) }
        report.appendLine("render ${edits.size} edit: ${System.currentTimeMillis() - t1} ms")
        savePng(result, "${s.name}_edit.png")

        val cmp = Bitmap.createBitmap(bmp.width, bmp.height * 2 + 8, Bitmap.Config.ARGB_8888)
        val cv = Canvas(cmp)
        cv.drawColor(Color.MAGENTA)
        cv.drawBitmap(bmp, 0f, 0f, null)
        cv.drawBitmap(result, 0f, (bmp.height + 8).toFloat(), null)
        savePng(cmp, "${s.name}_cmp.png")

        for ((i, l) in lines.withIndex()) {
            if (i >= 4) break
            if (!edits.containsKey(l.id)) continue
            savePng(zoom(bmp, result, l.geo), "${s.name}_zoom$i.png")
        }
    }
}
