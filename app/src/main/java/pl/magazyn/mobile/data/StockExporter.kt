package pl.magazyn.mobile.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

enum class StockExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf")
}

object StockExporter {
    fun write(file: File, warehouseName: String, items: List<ProductWithStock>, format: StockExportFormat) {
        file.parentFile?.mkdirs()
        when (format) {
            StockExportFormat.CSV -> file.outputStream().use { writeCsv(it, warehouseName, items) }
            StockExportFormat.XLSX -> file.outputStream().use { writeXlsx(it, warehouseName, items) }
            StockExportFormat.PDF -> file.outputStream().use { writePdf(it, warehouseName, items) }
        }
    }

    private fun writeCsv(output: OutputStream, warehouseName: String, items: List<ProductWithStock>) {
        val text = buildString {
            append('\uFEFF')
            appendLine(listOf("Magazyn", "Przedmiot", "Wariant", "Grupa", "Podgrupa", "Kategoria", "Stan", "Jednostka").joinToString(";") { csv(it) })
            items.forEach { item ->
                appendLine(
                    listOf(
                        warehouseName, item.name, item.variant.orEmpty(), item.groupName, item.subgroupName,
                        item.category, if (item.stockKnown) item.stockQuantity.toLong().toString() else "Nieustalony", item.unit,
                    ).joinToString(";") { csv(it) },
                )
            }
        }
        output.write(text.toByteArray(Charsets.UTF_8))
    }

    private fun writeXlsx(output: OutputStream, warehouseName: String, items: List<ProductWithStock>) {
        ZipOutputStream(output).use { zip ->
            zip.entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            zip.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            zip.entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Stan magazynowy" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            zip.entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val rows = buildList {
                add(listOf("Magazyn", "Przedmiot", "Wariant", "Grupa", "Podgrupa", "Kategoria", "Stan", "Jednostka"))
                items.forEach { item ->
                    add(listOf(warehouseName, item.name, item.variant.orEmpty(), item.groupName, item.subgroupName, item.category, if (item.stockKnown) item.stockQuantity.toLong().toString() else "Nieustalony", item.unit))
                }
            }
            val sheet = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="1" width="20" customWidth="1"/><col min="2" max="2" width="34" customWidth="1"/><col min="3" max="8" width="16" customWidth="1"/></cols><sheetData>""")
                rows.forEachIndexed { rowIndex, row ->
                    append("<row r=\"${rowIndex + 1}\">")
                    row.forEachIndexed { columnIndex, value ->
                        val reference = columnName(columnIndex + 1) + (rowIndex + 1)
                        val numeric = rowIndex > 0 && columnIndex == 6 && value.toLongOrNull() != null
                        if (numeric) append("<c r=\"$reference\"><v>$value</v></c>")
                        else append("<c r=\"$reference\" t=\"inlineStr\"><is><t>${xml(value)}</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData><autoFilter ref=\"A1:H${rows.size}\"/></worksheet>")
            }
            zip.entry("xl/worksheets/sheet1.xml", sheet)
        }
    }

    private fun writePdf(output: OutputStream, warehouseName: String, items: List<ProductWithStock>) {
        val document = PdfDocument()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 38, 45); textSize = 9f }
        val headerPaint = Paint(textPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val titlePaint = Paint(headerPaint).apply { textSize = 18f }
        val linePaint = Paint().apply { color = Color.rgb(205, 210, 216); strokeWidth = 1f }
        val columns = floatArrayOf(32f, 270f, 340f, 398f, 450f, 525f)
        val widths = floatArrayOf(232f, 64f, 52f, 46f, 69f, 38f)
        val rowsPerPage = 25
        try {
            items.chunked(rowsPerPage).ifEmpty { listOf(emptyList()) }.forEachIndexed { pageIndex, pageItems ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(595, 842, pageIndex + 1).create())
                val canvas = page.canvas
                canvas.drawText("Stan magazynowy", 32f, 40f, titlePaint)
                canvas.drawText(warehouseName, 32f, 60f, headerPaint)
                canvas.drawText("Wygenerowano: ${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"))}", 350f, 60f, textPaint)
                val headers = listOf("Przedmiot", "Wariant", "Stan", "Jedn.", "Grupa", "Kategoria")
                headers.forEachIndexed { index, value -> canvas.drawText(value, columns[index], 91f, headerPaint) }
                canvas.drawLine(32f, 98f, 563f, 98f, linePaint)
                pageItems.forEachIndexed { index, item ->
                    val y = 120f + index * 28f
                    val values = listOf(
                        item.name,
                        item.variant.orEmpty(),
                        if (item.stockKnown) item.stockQuantity.toLong().toString() else "?",
                        item.unit,
                        item.groupName,
                        item.category,
                    )
                    values.forEachIndexed { column, value -> canvas.drawText(fit(value, textPaint, widths[column]), columns[column], y, textPaint) }
                    canvas.drawLine(32f, y + 8f, 563f, y + 8f, linePaint)
                }
                canvas.drawText("Strona ${pageIndex + 1}", 500f, 816f, textPaint)
                document.finishPage(page)
            }
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun ZipOutputStream.entry(path: String, content: String) {
        putNextEntry(ZipEntry(path))
        write(content.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun csv(value: String): String = "\"${value.replace("\"", "\"\"")}\""
    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    private fun fit(value: String, paint: Paint, maxWidth: Float): String {
        if (paint.measureText(value) <= maxWidth) return value
        var shortened = value
        while (shortened.isNotEmpty() && paint.measureText("$shortened…") > maxWidth) shortened = shortened.dropLast(1)
        return "$shortened…"
    }
    private fun columnName(index: Int): String {
        var value = index
        var result = ""
        while (value > 0) {
            value--
            result = ('A'.code + value % 26).toChar() + result
            value /= 26
        }
        return result
    }
}
