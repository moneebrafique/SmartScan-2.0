package com.smartscan.app.export

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.OcrEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

const val DOCX_MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

/** [combinable] = several documents can be merged into one file of this type. */
enum class ExportFormat(
    val label: String,
    val ext: String,
    val mime: String,
    val combinable: Boolean = true,
) {
    PDF("PDF", "pdf", "application/pdf"),
    PDF_OCR("Searchable PDF (OCR)", "pdf", "application/pdf"),
    DOCX_TEXT("Word – editable text (OCR)", "docx", DOCX_MIME),
    DOCX_IMAGES("Word – scanned pages", "docx", DOCX_MIME),
    JPG("JPG images", "jpg", "image/jpeg", combinable = false),
    PNG("PNG images", "png", "image/png", combinable = false),
    TXT("Text file (OCR)", "txt", "text/plain"),
}

enum class PdfPageSize(val label: String, val width: Float, val height: Float) {
    A4("A4", 595.28f, 841.89f),
    LETTER("Letter", 612f, 792f),
    FIT("Fit to image", 0f, 0f),
}

enum class ExportQuality(val label: String, val maxSide: Int, val jpegQuality: Int) {
    HIGH("High", 3000, 90),
    MEDIUM("Medium", 2000, 78),
    LOW("Small", 1300, 62),
}

data class ExportResult(val files: List<File>, val mime: String, val text: String? = null)

object Exporter {

    /** Single document. */
    suspend fun export(
        context: Context,
        doc: ScanDocument,
        format: ExportFormat,
        pageSize: PdfPageSize,
        quality: ExportQuality,
        onProgress: (Float) -> Unit,
    ): ExportResult = exportMany(context, listOf(doc), format, pageSize, quality, combine = true, onProgress)

    /**
     * Several documents. With [combine] (and a combinable format) everything goes into one file,
     * otherwise each document becomes its own file (images: one file per page).
     */
    suspend fun exportMany(
        context: Context,
        docs: List<ScanDocument>,
        format: ExportFormat,
        pageSize: PdfPageSize,
        quality: ExportQuality,
        combine: Boolean,
        onProgress: (Float) -> Unit,
    ): ExportResult = withContext(Dispatchers.Default) {
        val list = docs.filter { it.pages.isNotEmpty() }
        require(list.isNotEmpty()) { "The selected documents have no pages" }
        val dir = File(context.cacheDir, "exports").apply { deleteRecursively(); mkdirs() }

        val total = list.sumOf { it.pages.size }
        var done = 0
        val tick: () -> Unit = {
            done++
            onProgress(done.toFloat() / total)
        }
        val used = mutableSetOf<String>()
        fun uniqueFile(base: String, ext: String): File {
            var name = "$base.$ext"
            var i = 2
            while (!used.add(name.lowercase())) {
                name = "$base ($i).$ext"
                i++
            }
            return File(dir, name)
        }

        val groups: List<Pair<String, List<ScanDocument>>> =
            if (combine && format.combinable && list.size > 1) {
                listOf("${safeName(list.first().name)}_and_${list.size - 1}_more" to list)
            } else {
                list.map { safeName(it.name) to listOf(it) }
            }

        val files = mutableListOf<File>()
        val allText = StringBuilder()
        for ((base, group) in groups) {
            when (format) {
                ExportFormat.PDF, ExportFormat.PDF_OCR -> {
                    val f = uniqueFile(base, "pdf")
                    writePdf(f, group, format == ExportFormat.PDF_OCR, pageSize, quality, tick)
                    files += f
                }
                ExportFormat.DOCX_TEXT, ExportFormat.DOCX_IMAGES -> {
                    val f = uniqueFile(base, "docx")
                    DocxWriter.write(f, group, format == ExportFormat.DOCX_TEXT, quality, tick)
                    files += f
                }
                ExportFormat.JPG, ExportFormat.PNG -> {
                    for (doc in group) {
                        doc.pages.forEachIndexed { i, page ->
                            val bmp = ImageUtils.load(DocumentRepository.file(doc.id, page.processedFile), quality.maxSide)
                            val f = uniqueFile(String.format(Locale.US, "%s_%02d", safeName(doc.name), i + 1), format.ext)
                            FileOutputStream(f).use {
                                val fmt = if (format == ExportFormat.PNG) Bitmap.CompressFormat.PNG else Bitmap.CompressFormat.JPEG
                                bmp.compress(fmt, quality.jpegQuality, it)
                            }
                            bmp.recycle()
                            files += f
                            tick()
                        }
                    }
                }
                ExportFormat.TXT -> {
                    val text = ocrText(group, tick)
                    val f = uniqueFile(base, "txt")
                    f.writeText(text)
                    files += f
                    allText.append(text)
                }
            }
        }
        ExportResult(files, format.mime, if (format == ExportFormat.TXT) allText.toString() else null)
    }

    private suspend fun writePdf(
        file: File,
        docs: List<ScanDocument>,
        ocr: Boolean,
        pageSize: PdfPageSize,
        quality: ExportQuality,
        tick: () -> Unit,
    ) {
        FileOutputStream(file).buffered().use { stream ->
            val writer = PdfWriter(stream)
            for (doc in docs) {
                for (page in doc.pages) {
                    val bmp = ImageUtils.load(DocumentRepository.file(doc.id, page.processedFile), quality.maxSide)
                    val lines = if (ocr) OcrEngine.recognize(bmp).lines else emptyList()
                    val (pw, ph) = pageDimensions(pageSize, bmp.width, bmp.height)
                    writer.addPage(ImageUtils.jpegBytes(bmp, quality.jpegQuality), bmp.width, bmp.height, pw, ph, lines)
                    bmp.recycle()
                    tick()
                }
            }
            writer.finish(if (docs.size == 1) docs[0].name else "${docs.size} documents")
        }
    }

    private suspend fun ocrText(docs: List<ScanDocument>, tick: () -> Unit): String {
        val sb = StringBuilder()
        for (doc in docs) {
            if (docs.size > 1) sb.append("===== ").append(doc.name).append(" =====\n\n")
            doc.pages.forEachIndexed { i, page ->
                val bmp = ImageUtils.load(DocumentRepository.file(doc.id, page.processedFile), 2500)
                val text = OcrEngine.recognize(bmp).text
                bmp.recycle()
                if (doc.pages.size > 1) sb.append("----- Page ${i + 1} -----\n")
                sb.append(text.trim()).append("\n\n")
                tick()
            }
        }
        return sb.toString()
    }

    /** PDF page size in points; A4/Letter auto-rotate to landscape for wide pages. */
    private fun pageDimensions(size: PdfPageSize, w: Int, h: Int): Pair<Float, Float> = when (size) {
        PdfPageSize.FIT -> {
            val width = if (w > h) 841.89f else 595.28f
            width to width * h / w
        }
        else -> if (w > h) size.height to size.width else size.width to size.height
    }

    fun share(context: Context, result: ExportResult) {
        val authority = context.packageName + ".fileprovider"
        val uris = result.files.map { FileProvider.getUriForFile(context, authority, it) }
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply { putExtra(Intent.EXTRA_STREAM, uris[0]) }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            }
        }
        intent.type = result.mime
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri("", uris[0]).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        context.startActivity(Intent.createChooser(intent, "Share"))
    }

    /** Saves into Download/SmartScan via MediaStore (no storage permission needed). */
    fun saveToDownloads(context: Context, result: ExportResult): Int {
        val resolver = context.contentResolver
        var saved = 0
        for (file in result.files) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                put(MediaStore.MediaColumns.MIME_TYPE, result.mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/SmartScan")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: continue
            resolver.openOutputStream(uri)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            saved++
        }
        return saved
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9 _.-]"), "_").trim().take(60).ifBlank { "scan" }
}
