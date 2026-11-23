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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
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
    var busyText by mutableStateOf("")
        private set
    var message by mutableStateOf<String?>(null)
        private set

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private var renderJob: Job? = null
    private val styleCache = ConcurrentHashMap<String, LineStyle>()

    init {
        viewModelScope.launch(Dispatchers.Default) { FontLibrary.all(getApplication()) }
    }

    fun consumeMessage() {
        message = null
    }

    fun load(uri: Uri) {
        viewModelScope.launch {
            busy = true
            busyText = "Membaca foto..."
            try {
                val bmp = withContext(Dispatchers.IO) { decode(uri) }
                busyText = "Mendeteksi teks..."
                val result = recognizer.process(InputImage.fromBitmap(bmp, 0)).await()
                busyText = "Menganalisis font, warna, dan noise..."
                val faces = withContext(Dispatchers.Default) { FontLibrary.all(getApplication()) }
                val analyzed = Pipeline.analyze(bmp, result, faces)
                renderJob?.cancel()
                styleCache.clear()
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

    private fun styleFor(src: Bitmap, line: TextLine, faceId: String?): LineStyle {
        if (faceId == null || faceId == line.style.faceId) return line.style
        return styleCache.getOrPut("${line.id}|$faceId") {
            Pipeline.styleFor(src, line, faceId, FontLibrary.loaded())
        }
    }

    private fun rerender() {
        val src = original ?: return
        val currentLines = lines
        val currentEdits = edits
        renderJob?.cancel()
        renderJob = viewModelScope.launch(Dispatchers.Default) {
            busy = true
            busyText = "Memproses hasil edit..."
            try {
                val rendered = Renderer.render(src, currentLines, currentEdits) { line, faceId ->
                    styleFor(src, line, faceId)
                }
                ensureActive()
                preview = rendered
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val bmp = preview ?: return
        viewModelScope.launch {
            busy = true
            busyText = "Menyimpan..."
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
                        bmp.compress(Bitmap.CompressFormat.JPEG, 97, stream)
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
}
