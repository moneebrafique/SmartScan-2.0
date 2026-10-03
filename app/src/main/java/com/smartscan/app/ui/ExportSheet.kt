package com.smartscan.app.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.TextSnippet
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.export.ExportFormat
import com.smartscan.app.export.ExportQuality
import com.smartscan.app.export.Exporter
import com.smartscan.app.export.PdfPageSize
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatIcon(f: ExportFormat): ImageVector = when (f) {
    ExportFormat.PDF, ExportFormat.PDF_OCR -> Icons.Default.PictureAsPdf
    ExportFormat.DOCX_TEXT, ExportFormat.DOCX_IMAGES -> Icons.Default.Description
    ExportFormat.JPG, ExportFormat.PNG -> Icons.Default.Image
    ExportFormat.TXT -> Icons.Default.TextSnippet
}

/** Export / share one or several documents in any format. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportSheet(docs: List<ScanDocument>, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val usable = remember(docs) { docs.filter { it.pages.isNotEmpty() } }
    val pageCount = usable.sumOf { it.pages.size }
    val multi = usable.size > 1

    var format by rememberSaveable { mutableStateOf(ExportFormat.PDF) }
    var pageSize by rememberSaveable { mutableStateOf(PdfPageSize.A4) }
    var quality by rememberSaveable { mutableStateOf(ExportQuality.MEDIUM) }
    var combine by rememberSaveable { mutableStateOf(false) }
    var progress by remember { mutableStateOf<Float?>(null) }

    fun run(share: Boolean) {
        if (progress != null || usable.isEmpty()) return
        progress = 0f
        scope.launch {
            try {
                val result = Exporter.exportMany(context, usable, format, pageSize, quality, combine) { p -> progress = p }
                if (share) {
                    Exporter.share(context, result)
                } else {
                    val n = withContext(Dispatchers.IO) { Exporter.saveToDownloads(context, result) }
                    Toast.makeText(context, "Saved $n file(s) to Downloads/SmartScan", Toast.LENGTH_LONG).show()
                }
                onDismiss()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Toast.makeText(context, "Export failed: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                progress = null
            }
        }
    }

    val fileCountText = when {
        format == ExportFormat.JPG || format == ExportFormat.PNG -> "$pageCount image file(s)"
        !multi -> "1 file"
        combine -> "1 combined file"
        else -> "${usable.size} files (one per document)"
    }

    ModalBottomSheet(onDismissRequest = { if (progress == null) onDismiss() }) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp).padding(bottom = 24.dp),
        ) {
            Text(if (multi) "Share ${usable.size} documents" else "Export", style = MaterialTheme.typography.titleLarge)
            Text(
                if (multi) "$pageCount pages in total" else "${usable.firstOrNull()?.name ?: ""} • $pageCount page(s)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (docs.size != usable.size) {
                Text(
                    "${docs.size - usable.size} empty document(s) will be skipped.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            SectionLabel("Share as")
            ExportFormat.entries.forEach { f ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = f == format, onClick = { format = f })
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = f == format, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Icon(formatIcon(f), contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(f.label)
                }
            }
            if (format == ExportFormat.DOCX_TEXT || format == ExportFormat.TXT || format == ExportFormat.PDF_OCR) {
                Text(
                    "Text is read from the scans on your phone (English/Latin script). Takes a little longer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp),
                )
            }

            if (multi && format.combinable) {
                Row(
                    Modifier.fillMaxWidth().clickable { combine = !combine }.padding(top = 12.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Combine into one file")
                        Text(
                            "Off: each document is shared as its own file",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = combine, onCheckedChange = { combine = it })
                }
            }

            if (format == ExportFormat.PDF || format == ExportFormat.PDF_OCR) {
                SectionLabel("Page size")
                ChipRow(PdfPageSize.entries, pageSize, { it.label }) { pageSize = it }
            }
            if (format != ExportFormat.TXT && format != ExportFormat.DOCX_TEXT) {
                SectionLabel("Quality")
                ChipRow(ExportQuality.entries, quality, { it.label }) { quality = it }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                "You will get: $fileCountText",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.height(12.dp))
            progress?.let {
                LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = { run(share = false) },
                    enabled = progress == null && usable.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Save to Downloads") }
                Button(
                    onClick = { run(share = true) },
                    enabled = progress == null && usable.isNotEmpty(),
                    modifier = Modifier.weight(1f),
                ) { Text("Share") }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> ChipRow(items: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.forEach { item ->
            FilterChip(selected = item == selected, onClick = { onSelect(item) }, label = { Text(label(item)) })
        }
    }
}
