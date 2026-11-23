package com.ky7ra.fototeks

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlin.math.min

@Composable
fun EditorScreen(vm: EditorViewModel = viewModel()) {
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<TextLine?>(null) }
    var showList by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.load(uri)
    }

    val msg = vm.message
    LaunchedEffect(msg) {
        if (msg != null) {
            snackbar.showSnackbar(msg)
            vm.consumeMessage()
        }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
                .padding(12.dp),
        ) {
            Text(
                "Edit Teks Foto",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.size(10.dp))

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color(0xFFEFEFF4)),
                contentAlignment = Alignment.Center,
            ) {
                val bmp = vm.preview
                if (bmp == null) {
                    Text(
                        "Pilih foto yang ada tulisannya.\nAplikasi akan membaca teksnya, lalu kamu bisa mengubahnya dengan keyboard.",
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(24.dp),
                        color = Color(0xFF555555),
                    )
                } else {
                    ImageEditor(
                        bmp = bmp,
                        lines = vm.lines,
                        edits = vm.edits,
                        onTapLine = { editing = it },
                    )
                }
                if (vm.busy) CircularProgressIndicator()
            }

            Spacer(Modifier.size(8.dp))
            if (vm.preview != null) {
                Text(
                    "${vm.lines.size} baris teks · ${vm.edits.size} diedit · kotak biru = belum diedit, hijau = sudah diedit",
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                )
                Spacer(Modifier.size(8.dp))
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { picker.launch("image/*") },
                    modifier = Modifier.weight(1f),
                    enabled = !vm.busy,
                ) { Text("Pilih Foto") }
                OutlinedButton(
                    onClick = { showList = true },
                    modifier = Modifier.weight(1f),
                    enabled = vm.lines.isNotEmpty() && !vm.busy,
                ) { Text("Daftar Teks") }
                Button(
                    onClick = { vm.save() },
                    modifier = Modifier.weight(1f),
                    enabled = vm.preview != null && !vm.busy,
                ) { Text("Simpan") }
            }
        }
    }

    editing?.let { line ->
        EditDialog(
            line = line,
            initial = vm.edits[line.id] ?: LineEdit(line.text, false, line.defaultAlign, null),
            canRevert = vm.edits.containsKey(line.id),
            onApply = { e ->
                if (e.text == line.text && !e.bold && e.color == null) vm.revert(line.id) else vm.applyEdit(line.id, e)
                editing = null
            },
            onRevert = {
                vm.revert(line.id)
                editing = null
            },
            onDismiss = { editing = null },
        )
    }

    if (showList) {
        LinesDialog(
            lines = vm.lines,
            edits = vm.edits,
            onPick = {
                showList = false
                editing = it
            },
            onDismiss = { showList = false },
        )
    }
}

@Composable
private fun ImageEditor(
    bmp: Bitmap,
    lines: List<TextLine>,
    edits: Map<Int, LineEdit>,
    onTapLine: (TextLine) -> Unit,
) {
    var box by remember { mutableStateOf(IntSize.Zero) }
    val image = remember(bmp) { bmp.asImageBitmap() }

    fun scaleOf(): Float =
        if (box.width == 0 || box.height == 0) 1f
        else min(box.width / bmp.width.toFloat(), box.height / bmp.height.toFloat())

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { box = it }
            .pointerInput(lines, box, bmp.width, bmp.height) {
                detectTapGestures { pos ->
                    val s = scaleOf()
                    val ox = (box.width - bmp.width * s) / 2f
                    val oy = (box.height - bmp.height * s) / 2f
                    val ix = (pos.x - ox) / s
                    val iy = (pos.y - oy) / s
                    val slop = 8.dp.toPx() / s
                    val hit = lines
                        .filter { it.geo.contains(ix, iy, slop) }
                        .minByOrNull { it.geo.w * it.geo.h }
                    if (hit != null) onTapLine(hit)
                }
            },
    ) {
        Image(
            bitmap = image,
            contentDescription = "Foto",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(Modifier.fillMaxSize()) {
            val s = scaleOf()
            val ox = (box.width - bmp.width * s) / 2f
            val oy = (box.height - bmp.height * s) / 2f
            for (l in lines) {
                val q = l.geo.quad(2f)
                val path = Path().apply {
                    moveTo(ox + q[0] * s, oy + q[1] * s)
                    lineTo(ox + q[2] * s, oy + q[3] * s)
                    lineTo(ox + q[4] * s, oy + q[5] * s)
                    lineTo(ox + q[6] * s, oy + q[7] * s)
                    close()
                }
                val edited = edits.containsKey(l.id)
                drawPath(
                    path = path,
                    color = if (edited) Color(0xFF2E7D32) else Color(0xE61E88E5),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
        }
    }
}

@Composable
private fun Choice(label: String, selected: Boolean, onClick: () -> Unit) {
    if (selected) {
        FilledTonalButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            Text(label, fontSize = 13.sp)
        }
    } else {
        OutlinedButton(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp)) {
            Text(label, fontSize = 13.sp)
        }
    }
}

private val palette: List<Int?> = listOf(
    null,
    0xFF000000.toInt(),
    0xFFFFFFFF.toInt(),
    0xFFD32F2F.toInt(),
    0xFF1976D2.toInt(),
    0xFF388E3C.toInt(),
)

@Composable
private fun EditDialog(
    line: TextLine,
    initial: LineEdit,
    canRevert: Boolean,
    onApply: (LineEdit) -> Unit,
    onRevert: () -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(line.id) { mutableStateOf(initial.text) }
    var bold by remember(line.id) { mutableStateOf(initial.bold) }
    var align by remember(line.id) { mutableStateOf(initial.align) }
    var color by remember(line.id) { mutableStateOf(initial.color) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(line.id) { runCatching { focus.requestFocus() } }

    val g = line.geo
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Ubah teks") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Teks baru") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focus),
                )
                Text("Teks asli: ${line.text}", fontSize = 12.sp, color = Color(0xFF555555))
                val lang = if (line.language == "und" || line.language.isBlank()) "tidak diketahui" else line.language
                Text(
                    "${line.wordCount} kata · bahasa: $lang · ${g.w.toInt()}×${g.h.toInt()} px · sudut ${"%.1f".format(g.angle)}°",
                    fontSize = 12.sp,
                    color = Color(0xFF555555),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Warna teks terdeteksi", fontSize = 12.sp, color = Color(0xFF555555))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(line.textColor)).border(1.dp, Color.Gray, CircleShape))
                    Spacer(Modifier.width(12.dp))
                    Text("latar", fontSize = 12.sp, color = Color(0xFF555555))
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(16.dp).clip(CircleShape).background(Color(line.bgColor)).border(1.dp, Color.Gray, CircleShape))
                }

                Text("Rata teks", fontWeight = FontWeight.Medium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Choice("Kiri", align == 0) { align = 0 }
                    Choice("Tengah", align == 1) { align = 1 }
                    Choice("Kanan", align == 2) { align = 2 }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = bold, onCheckedChange = { bold = it })
                    Text("Tebal (bold)")
                }

                Text("Warna", fontWeight = FontWeight.Medium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (c in palette) {
                        val selected = color == c
                        val border = if (selected) Color(0xFF2E5BFF) else Color.Gray
                        val bw = if (selected) 3.dp else 1.dp
                        if (c == null) {
                            Box(
                                Modifier
                                    .height32()
                                    .clip(RoundedCornerShape(16.dp))
                                    .border(bw, border, RoundedCornerShape(16.dp))
                                    .clickable { color = null }
                                    .padding(horizontal = 10.dp),
                                contentAlignment = Alignment.Center,
                            ) { Text("Asli", fontSize = 12.sp) }
                        } else {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(Color(c))
                                    .border(bw, border, CircleShape)
                                    .clickable { color = c },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(LineEdit(text, bold, align, color)) }) { Text("Terapkan") }
        },
        dismissButton = {
            Row {
                if (canRevert) TextButton(onClick = onRevert) { Text("Kembalikan") }
                TextButton(onClick = onDismiss) { Text("Batal") }
            }
        },
    )
}

private fun Modifier.height32(): Modifier = this.size(width = 48.dp, height = 32.dp)

@Composable
private fun LinesDialog(
    lines: List<TextLine>,
    edits: Map<Int, LineEdit>,
    onPick: (TextLine) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Teks terdeteksi (${lines.size})") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp)) {
                items(lines, key = { it.id }) { l ->
                    val edit = edits[l.id]
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(l) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(edit?.text ?: l.text)
                        if (edit != null) {
                            Text("diedit · asli: ${l.text}", fontSize = 11.sp, color = Color(0xFF2E7D32))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Tutup") } },
    )
}
