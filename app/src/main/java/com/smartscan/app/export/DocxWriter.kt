package com.smartscan.app.export

import com.smartscan.app.data.DocumentRepository
import com.smartscan.app.data.ScanDocument
import com.smartscan.app.processing.ImageUtils
import com.smartscan.app.processing.OcrEngine
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

/**
 * Minimal Word (.docx) writer. A .docx is a zip of XML files; this writes:
 *  - editable mode: OCR text as normal Word paragraphs (title, "Page n", paragraphs)
 *  - image mode: each scanned page as a full-page picture
 * Page: A4 with 0.5" margins.
 */
object DocxWriter {
    const val MIME = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"

    private const val EMU_PER_INCH = 914400.0
    private const val BOX_W_IN = 7.2   // usable width (8.27" - 2 × 0.5")
    private const val BOX_H_IN = 10.3  // usable height, a little less than 10.69" to avoid spill-over

    suspend fun write(
        file: File,
        docs: List<ScanDocument>,
        editable: Boolean,
        quality: ExportQuality,
        tick: () -> Unit,
    ) {
        val body = StringBuilder()
        var imageCount = 0
        var needBreak = false

        fun paragraph(runs: String, center: Boolean = false, tight: Boolean = false) {
            body.append("<w:p>")
            val ppr = StringBuilder()
            if (needBreak) ppr.append("<w:pageBreakBefore/>")
            if (tight) ppr.append("<w:spacing w:before=\"0\" w:after=\"0\"/>")
            if (center) ppr.append("<w:jc w:val=\"center\"/>")
            if (ppr.isNotEmpty()) body.append("<w:pPr>").append(ppr).append("</w:pPr>")
            body.append(runs).append("</w:p>")
            needBreak = false
        }

        ZipOutputStream(FileOutputStream(file).buffered()).use { zip ->
            fun entry(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            entry("[Content_Types].xml", CONTENT_TYPES.toByteArray())
            entry("_rels/.rels", ROOT_RELS.toByteArray())
            entry("word/styles.xml", STYLES.toByteArray())

            docs.forEachIndexed { di, doc ->
                if (editable) {
                    if (di > 0) needBreak = true
                    paragraph(run(doc.name, bold = true, size = 32))
                }
                doc.pages.forEachIndexed { pi, page ->
                    val bmp = ImageUtils.load(
                        DocumentRepository.file(doc.id, page.processedFile),
                        if (editable) 2500 else quality.maxSide,
                    )
                    try {
                        if (editable) {
                            val blocks = OcrEngine.recognize(bmp).blocks
                            if (pi > 0) needBreak = true
                            if (doc.pages.size > 1) {
                                paragraph(run("Page ${pi + 1}", bold = true, size = 20, color = "888888"))
                            }
                            if (blocks.isEmpty()) {
                                paragraph(run("(No text found on this page)", italic = true, color = "888888"))
                            } else {
                                blocks.forEach { paragraph(run(it)) }
                            }
                        } else {
                            imageCount++
                            val name = "image$imageCount.jpeg"
                            entry("word/media/$name", ImageUtils.jpegBytes(bmp, quality.jpegQuality))
                            val scale = min(BOX_W_IN / bmp.width, BOX_H_IN / bmp.height)
                            val cx = (bmp.width * scale * EMU_PER_INCH).toLong()
                            val cy = (bmp.height * scale * EMU_PER_INCH).toLong()
                            if (imageCount > 1) needBreak = true
                            paragraph(drawing(imageCount, cx, cy), center = true, tight = true)
                        }
                    } finally {
                        bmp.recycle()
                    }
                    tick()
                }
            }

            entry("word/document.xml", (DOC_HEAD + body + DOC_TAIL).toByteArray())
            entry("word/_rels/document.xml.rels", documentRels(imageCount).toByteArray())
        }
    }

    private fun run(
        text: String,
        bold: Boolean = false,
        size: Int = 22,
        italic: Boolean = false,
        color: String? = null,
    ): String = buildString {
        append("<w:r><w:rPr>")
        if (bold) append("<w:b/>")
        if (italic) append("<w:i/>")
        if (color != null) append("<w:color w:val=\"").append(color).append("\"/>")
        append("<w:sz w:val=\"").append(size).append("\"/>")
        append("</w:rPr><w:t xml:space=\"preserve\">").append(esc(text)).append("</w:t></w:r>")
    }

    private fun drawing(n: Int, cx: Long, cy: Long) =
        "<w:r><w:drawing><wp:inline distT=\"0\" distB=\"0\" distL=\"0\" distR=\"0\">" +
            "<wp:extent cx=\"$cx\" cy=\"$cy\"/><wp:docPr id=\"$n\" name=\"Page $n\"/>" +
            "<a:graphic><a:graphicData uri=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<pic:pic><pic:nvPicPr><pic:cNvPr id=\"$n\" name=\"image$n.jpeg\"/><pic:cNvPicPr/></pic:nvPicPr>" +
            "<pic:blipFill><a:blip r:embed=\"rIdImg$n\"/><a:stretch><a:fillRect/></a:stretch></pic:blipFill>" +
            "<pic:spPr><a:xfrm><a:off x=\"0\" y=\"0\"/><a:ext cx=\"$cx\" cy=\"$cy\"/></a:xfrm>" +
            "<a:prstGeom prst=\"rect\"><a:avLst/></a:prstGeom></pic:spPr></pic:pic>" +
            "</a:graphicData></a:graphic></wp:inline></w:drawing></w:r>"

    private fun documentRels(images: Int) = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        append("<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">")
        append("<Relationship Id=\"rIdStyles\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>")
        for (i in 1..images) {
            append("<Relationship Id=\"rIdImg$i\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/image\" Target=\"media/image$i.jpeg\"/>")
        }
        append("</Relationships>")
    }

    private fun esc(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (ch in s) {
            when {
                ch == '&' -> sb.append("&amp;")
                ch == '<' -> sb.append("&lt;")
                ch == '>' -> sb.append("&gt;")
                ch == '"' -> sb.append("&quot;")
                ch == '\t' || ch == '\n' || ch == '\r' -> sb.append(' ')
                ch.code < 0x20 || ch == '\uFFFE' || ch == '\uFFFF' -> {} // not allowed in XML
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    private const val CONTENT_TYPES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Default Extension=\"jpeg\" ContentType=\"image/jpeg\"/>" +
            "<Override PartName=\"/word/document.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml\"/>" +
            "<Override PartName=\"/word/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml\"/>" +
            "</Types>"

    private const val ROOT_RELS =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">" +
            "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"word/document.xml\"/>" +
            "</Relationships>"

    /** Default font Calibri 11pt with comfortable paragraph spacing. */
    private const val STYLES =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:styles xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
            "<w:docDefaults><w:rPrDefault><w:rPr>" +
            "<w:rFonts w:ascii=\"Calibri\" w:hAnsi=\"Calibri\" w:eastAsia=\"Calibri\" w:cs=\"Calibri\"/>" +
            "<w:sz w:val=\"22\"/><w:szCs w:val=\"22\"/><w:lang w:val=\"en-US\"/>" +
            "</w:rPr></w:rPrDefault>" +
            "<w:pPrDefault><w:pPr><w:spacing w:after=\"160\" w:line=\"264\" w:lineRule=\"auto\"/></w:pPr></w:pPrDefault>" +
            "</w:docDefaults>" +
            "<w:style w:type=\"paragraph\" w:default=\"1\" w:styleId=\"Normal\"><w:name w:val=\"Normal\"/></w:style>" +
            "</w:styles>"

    private const val DOC_HEAD =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>" +
            "<w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\"" +
            " xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"" +
            " xmlns:wp=\"http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing\"" +
            " xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"" +
            " xmlns:pic=\"http://schemas.openxmlformats.org/drawingml/2006/picture\">" +
            "<w:body>"

    private const val DOC_TAIL =
        "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/>" +
            "<w:pgMar w:top=\"720\" w:right=\"720\" w:bottom=\"720\" w:left=\"720\" w:header=\"0\" w:footer=\"0\" w:gutter=\"0\"/>" +
            "</w:sectPr></w:body></w:document>"
}
