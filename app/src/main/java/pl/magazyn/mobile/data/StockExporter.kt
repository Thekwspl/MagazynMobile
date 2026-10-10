package pl.magazyn.mobile.data

import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.min

enum class StockExportFormat(val extension: String, val mimeType: String) {
    CSV("csv", "text/csv"),
    XLSX("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    PDF("pdf", "application/pdf")
}

object StockExporter {
    fun write(file: File, warehouseName: String, items: List<ProductWithStock>, format: StockExportFormat) =
        write(file, stockExportTable(warehouseName, items), format)

    fun write(file: File, table: ExportTable, format: StockExportFormat) {
        require(table.rows.isNotEmpty()) { "Brak danych do eksportu." }
        file.parentFile?.mkdirs()
        when (format) {
            StockExportFormat.CSV -> file.outputStream().use { writeCsv(it, table) }
            StockExportFormat.XLSX -> file.outputStream().use { writeXlsx(it, table) }
            StockExportFormat.PDF -> file.outputStream().use { writePdf(it, table) }
        }
    }

    private fun writeCsv(output: OutputStream, table: ExportTable) {
        output.writer(Charsets.UTF_8).use { writer ->
            writer.write("\uFEFF")
            (sequenceOf(table.headers) + table.rows.asSequence()).forEach { row ->
                writer.write(row.joinToString(";") { csv(it) })
                writer.write("\r\n")
            }
        }
    }

    private fun writeXlsx(output: OutputStream, table: ExportTable) {
        ZipOutputStream(output).use { zip ->
            zip.entry("[Content_Types].xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""")
            zip.entry("_rels/.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""")
            zip.entry("xl/workbook.xml", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="${xml(table.title)}" sheetId="1" r:id="rId1"/></sheets></workbook>""")
            zip.entry("xl/_rels/workbook.xml.rels", """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""")
            val rows = listOf(table.headers) + table.rows
            val sheet = buildString {
                append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?><worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetViews><sheetView workbookViewId="0"><pane ySplit="1" topLeftCell="A2" activePane="bottomLeft" state="frozen"/></sheetView></sheetViews><cols><col min="1" max="${table.headers.size}" width="24" customWidth="1"/></cols><sheetData>""")
                rows.forEachIndexed { rowIndex, row ->
                    append("<row r=\"${rowIndex + 1}\">")
                    row.forEachIndexed { columnIndex, value ->
                        val reference = columnName(columnIndex + 1) + (rowIndex + 1)
                        val numeric = rowIndex > 0 && columnIndex in table.numericColumns && value.toLongOrNull() != null
                        if (numeric) append("<c r=\"$reference\"><v>$value</v></c>")
                        else append("<c r=\"$reference\" t=\"inlineStr\"><is><t xml:space=\"preserve\">${xml(value)}</t></is></c>")
                    }
                    append("</row>")
                }
                append("</sheetData><autoFilter ref=\"A1:${columnName(table.headers.size)}${rows.size}\"/></worksheet>")
            }
            zip.entry("xl/worksheets/sheet1.xml", sheet)
        }
    }

    private fun writePdf(output: OutputStream, table: ExportTable) {
        val document = PdfDocument()
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(32, 38, 45); textSize = 8.5f }
        val headerPaint = Paint(textPaint).apply { typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD) }
        val titlePaint = Paint(headerPaint).apply { textSize = 18f }
        val linePaint = Paint().apply { color = Color.rgb(205, 210, 216); strokeWidth = 1f }
        // A4 poziomo: osobne kolumny oraz zawijanie zamiast ucinania uwag i identyfikatorów.
        val margin = 28f
        val widths = table.pdfWeights.map { 786f * it / table.pdfWeights.sum() }
        val columns = widths.runningFold(margin) { x, width -> x + width }.dropLast(1)
        val lineHeight = 13f
        var page: PdfDocument.Page? = null
        var pageNumber = 0
        var y = 0f
        var bodyTop = 0f
        fun finishPage() {
            page?.let {
                it.canvas.drawText("Strona $pageNumber", 746f, 574f, textPaint)
                document.finishPage(it)
            }
            page = null
        }
        fun startPage() {
            finishPage()
            pageNumber++
            page = document.startPage(PdfDocument.PageInfo.Builder(842, 595, pageNumber).create())
            val canvas = requireNotNull(page).canvas
            canvas.drawText(table.title, margin, 36f, titlePaint)
            val subtitle = wrapExportText(table.subtitle, 786f) { headerPaint.measureText(it) }
            subtitle.forEachIndexed { index, value -> canvas.drawText(value, margin, 54f + index * lineHeight, headerPaint) }
            val generatedY = 54f + subtitle.size * lineHeight
            canvas.drawText("Wygenerowano: ${table.generatedAt}", margin, generatedY, textPaint)
            val headers = table.pdfColumns.mapIndexed { index, column -> wrapExportText(table.headers[column], widths[index] - 8f) { headerPaint.measureText(it) } }
            val headerY = generatedY + 22f
            headers.forEachIndexed { column, lines -> lines.forEachIndexed { index, value ->
                canvas.drawText(value, columns[column], headerY + index * lineHeight, headerPaint)
            } }
            bodyTop = headerY + headers.maxOf { it.size } * lineHeight + 10f
            require(bodyTop + lineHeight < 552f) { "Nagłówek raportu jest zbyt długi, aby zmieścić tabelę na stronie PDF." }
            canvas.drawLine(margin, bodyTop - 10f, 814f, bodyTop - 10f, linePaint)
            y = bodyTop
        }
        try {
            startPage()
            table.rows.forEach { row ->
                val cells = table.pdfColumns.mapIndexed { index, column ->
                    val value = if (table.title == "Stan magazynowy" && column == 6 && row[column] == "Nieustalony") "?" else row[column]
                    wrapExportText(value, widths[index] - 8f) { textPaint.measureText(it) }
                }
                val count = cells.maxOf { it.size }
                if (y + count * lineHeight > 552f && y > bodyTop && count * lineHeight <= 552f - bodyTop) startPage()
                var offset = 0
                while (offset < count) {
                    val available = ((552f - y) / lineHeight).toInt()
                    if (available < 1) { startPage(); continue }
                    val take = min(available, count - offset)
                    val canvas = requireNotNull(page).canvas
                    cells.forEachIndexed { column, lines ->
                        lines.drop(offset).take(take).forEachIndexed { index, value ->
                            canvas.drawText(value, columns[column], y + index * lineHeight, textPaint)
                        }
                    }
                    y += take * lineHeight + 8f
                    canvas.drawLine(margin, y - 6f, 814f, y - 6f, linePaint)
                    offset += take
                    if (offset < count) startPage()
                }
            }
            finishPage()
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
    private fun xml(value: String): String = value.filter { it == '\n' || it == '\r' || it == '\t' || it.code >= 32 }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

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

internal fun wrapExportText(value: String, width: Float, measure: (String) -> Float): List<String> = buildList {
    value.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { paragraph ->
        var rest = paragraph
        if (rest.isEmpty()) add("")
        while (rest.isNotEmpty()) {
            if (measure(rest) <= width) { add(rest); break }
            var end = 1
            while (end < rest.length && measure(rest.take(end + 1)) <= width) end++
            val space = rest.lastIndexOf(' ', end - 1).takeIf { it > 0 }
            val cut = space ?: end
            add(rest.take(cut))
            rest = rest.drop(cut + if (space != null) 1 else 0)
        }
    }
}
