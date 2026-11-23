package com.ky7ra.fototeks

import android.app.Application
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max

class EditorViewModel(app: Application) : AndroidViewModel(app) {

    var original by mutableStateOf<Bitmap?>(null)
        private set
    var preview by mutableStateOf<Bitmap?>(null)
        private set
    var lines by mutableStateOf<List<TextLine>>(emptyList())
        private set
    var edits by mutableStateOf<Map<Int, LineEdit>>(emptyMap())
        private set
    var busy by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var renderJob: Job? = null

    fun consumeMessage() {
        message = null
    }

    fun load(uri: Uri) {
        viewModelScope.launch {
            busy = true
            try {
                val bmp = withContext(Dispatchers.IO) { decode(uri) }
                val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                val analyzed = withContext(Dispatchers.Default) { analyze(bmp, result) }
                renderJob?.cancel()
                original = bmp
                preview = bmp
                lines = analyzed
                edits = emptyMap()
                message = if (analyzed.isEmpty()) {
                    "Tidak ada teks yang terdeteksi di foto ini"
                } else {
                    "${analyzed.size} baris teks terdeteksi. Ketuk teks pada foto untuk mengubahnya."
                }
            } catch (e: Exception) {
                message = "Gagal memuat foto: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    fun applyEdit(id: Int, edit: LineEdit) {
        edits = edits + (id to edit)
        rerender()
    }

    fun revert(id: Int) {
        edits = edits - id
        rerender()
    }

    private fun rerender() {
        val src = original ?: return
        val currentLines = lines
        val currentEdits = edits
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            val rendered = Renderer.render(src, currentLines, currentEdits)
            ensureActive()
            preview = rendered
        }
    }

    fun save() {
        val bmp = preview ?: return
        viewModelScope.launch {
            busy = true
            try {
                withContext(Dispatchers.IO) {
                    val resolver = getApplication<Application>().contentResolver
                    val values = ContentValues().apply {
                        put(MediaStore.Images.Media.DISPLAY_NAME, "FotoTeks_${System.currentTimeMillis()}.jpg")
                        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/FotoTeks")
                        put(MediaStore.Images.Media.IS_PENDING, 1)
                    }
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                        ?: error("Tidak bisa membuat file di galeri")
                    resolver.openOutputStream(uri)?.use { stream ->
                        bmp.compress(Bitmap.CompressFormat.JPEG, 95, stream)
                    } ?: error("Tidak bisa menulis file")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                }
                message = "Tersimpan di Galeri (Pictures/FotoTeks)"
            } catch (e: Exception) {
                message = "Gagal menyimpan: ${e.message ?: e.javaClass.simpleName}"
            } finally {
                busy = false
            }
        }
    }

    override fun onCleared() {
        recognizer.close()
        super.onCleared()
    }

    // ---------------------------------------------------------------------------------------

    private fun decode(uri: Uri): Bitmap {
        val cr = getApplication<Application>().contentResolver

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val maxSide = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxSide / sample > 3000) sample *= 2

        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bmp = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("File foto tidak bisa dibaca")

        val orientation = try {
            cr.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }

        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bmp
        }
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    private fun geoOf(line: Text.Line): Geo? {
        val cp = line.cornerPoints
        if (cp != null && cp.size == 4) {
            val tl = cp[0]
            val tr = cp[1]
            val br = cp[2]
            val bl = cp[3]
            val w = hypot((tr.x - tl.x).toFloat(), (tr.y - tl.y).toFloat())
            val h = hypot((bl.x - tl.x).toFloat(), (bl.y - tl.y).toFloat())
            if (w > 1f && h > 1f) {
                val cx = (tl.x + tr.x + br.x + bl.x) / 4f
                val cy = (tl.y + tr.y + br.y + bl.y) / 4f
                var angle = Math.toDegrees(
                    atan2((tr.y - tl.y).toDouble(), (tr.x - tl.x).toDouble()),
                ).toFloat()
                if (abs(angle) < 1.5f) angle = 0f
                return Geo(cx, cy, w, h, angle)
            }
        }
        val r = line.boundingBox ?: return null
        return Geo(r.exactCenterX(), r.exactCenterY(), r.width().toFloat(), r.height().toFloat(), 0f)
    }

    private fun analyze(bmp: Bitmap, text: Text): List<TextLine> {
        val result = ArrayList<TextLine>()
        var id = 0
        for (block in text.textBlocks) {
            for (line in block.lines) {
                if (line.text.isBlank()) continue
                val g = geoOf(line) ?: continue
                val est = Inpaint.build(bmp, g)
                val nearCenter = abs(g.cx - bmp.width / 2f) < bmp.width * 0.03f && g.w < bmp.width * 0.9f
                result += TextLine(
                    id = id++,
                    text = line.text,
                    geo = g,
                    wordCount = line.elements.size,
                    language = line.recognizedLanguage,
                    textColor = est.textColor,
                    bgColor = est.bgColor,
                    defaultAlign = if (nearCenter) 1 else 0,
                )
            }
        }
        return result
    }
}
