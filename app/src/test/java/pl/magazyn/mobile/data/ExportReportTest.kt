package pl.magazyn.mobile.data

import java.io.File
import java.time.LocalDateTime
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import pl.magazyn.mobile.ui.ExportPreview

class ExportReportTest {
    private val now = LocalDateTime.of(2026, 10, 10, 16, 30)
    private val warehouses = listOf(WarehouseEntity("other", "Inny", false), WarehouseEntity("main", "Magazyn główny", true))
    private val yards = listOf(ShipyardEntity("a", "Stocznia A"), ShipyardEntity("b", "Stocznia B"))
    private val day = ExportSelection(report = ExportReportType.DELIVERIES, dateFrom = "2026-10-10", dateTo = "2026-10-10")

    @Test fun defaultsSelectStockAndMainWarehouse() {
        val selection = ExportSelection()
        assertEquals(ExportReportType.STOCK, selection.report)
        assertEquals(StockReportSource.MAIN, selection.source)
        assertEquals("main", resolveExportStockTarget(selection, warehouses, yards).id)
    }

    @Test fun eachShipyardIsSelectedByStableIdAndMissingYardNeverFallsBack() {
        yards.forEach { yard ->
            val selection = ExportSelection(source = StockReportSource.SHIPYARD, shipyardId = yard.id)
            assertEquals(yard.id, resolveExportStockTarget(selection, warehouses, yards).id)
            assertThrows(IllegalArgumentException::class.java) { resolveExportStockTarget(selection, warehouses, yards.filter { it.id != yard.id }) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            resolveExportStockTarget(ExportSelection(source = StockReportSource.SHIPYARD, shipyardId = "a"), warehouses, listOf(yards[0].copy(isArchived = true)))
        }
        assertThrows(IllegalArgumentException::class.java) { resolveExportStockTarget(ExportSelection(), warehouses.take(1), yards) }
    }

    @Test fun negativeShipyardStockKeepsItsQuantityAndProductDetails() {
        val row = ShipyardStockItem("helmet", "Kask", "Żółty", "szt.", -3.0, "BHP", "Ochrona", "Kaski").toExportProduct()
        val table = stockExportTable("Stocznia A", listOf(row), now)
        assertEquals("-3", table.rows.single()[6])
        assertEquals("Kaski", table.rows.single()[5])
        assertEquals("Żółty", table.rows.single()[2])
        assertTrue(row.stockKnown)
        assertEquals("10.10.2026 16:30", table.rows.single()[8])
        assertEquals("Nieustalony", stockExportTable("Główny", listOf(row.copy(stockKnown = false)), now).rows.single()[6])
    }

    @Test fun datesAcceptOneDayAndRejectMissingInvalidAndReversedRanges() {
        assertNull(day.dateError())
        assertNull(day.copy(dateFrom = "2026-10-01", dateTo = "2026-10-31").dateError())
        assertNotNull(day.copy(dateFrom = "2026-10-11").dateError())
        assertNotNull(day.copy(dateFrom = "").dateError())
        assertNotNull(day.copy(dateTo = "2026-13-01").dateError())
        assertThrows(IllegalArgumentException::class.java) { deliveryExportTable(day.copy(dateFrom = "2026-10-11"), listOf(line()), now) }
    }

    @Test fun deliveryRowsKeepEveryLineEveryOperationAndHistoricalQuantities() {
        val rows = listOf(line(), line(id = "line2", product = "Rękawice"), line(operation = "operation2", id = "line3"))
        val table = deliveryExportTable(day, rows, now)
        assertEquals(3, table.rows.size)
        assertEquals(listOf("operation1", "operation1", "operation2"), table.rows.map { it[7] })
        assertEquals(listOf("line1", "line2", "line3"), table.rows.map { it[8] })
        assertEquals("4", table.rows[0][3])
        assertEquals("Okres: 2026-10-10 – 2026-10-10", table.subtitle)
        assertEquals(listOf("2026-10-10", "2026-10-10"), table.rows[0].subList(9, 11))
    }

    @Test fun loadingEmptyErrorsAndStaleSelectionCannotEnableExport() {
        assertFalse(ExportPreview(day).canExport)
        assertFalse(ExportPreview(day, loading = false).canExport)
        val filled = ExportPreview(day, loading = false, deliveries = listOf(line(), line(id = "line2")))
        assertTrue(filled.canExport)
        assertEquals(1, filled.deliveryCount)
        assertFalse(filled.copy(error = "Błąd odczytu").canExport)
        assertFalse(filled.forSelection(day.copy(dateTo = "2026-10-11")).canExport)
        assertTrue(filled.forSelection(day).canExport)
        assertFalse(filled.copy(selection = day.copy(dateFrom = "2026-10-11")).canExport)
        assertThrows(IllegalArgumentException::class.java) { withFile("csv") { StockExporter.write(it, deliveryExportTable(day, emptyList(), now), StockExportFormat.CSV) } }
    }

    @Test fun csvKeepsBomPolishQuotesSemicolonsNewlinesAndReportMetadata() = withFile("csv") { file ->
        val note = "Zażółć; \"gęślą\"\nDruga linia"
        StockExporter.write(file, deliveryExportTable(day, listOf(line(note = note)), now), StockExportFormat.CSV)
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.startsWith("\uFEFF\"Data dostawy\";"))
        assertTrue(text.contains("\"Zażółć; \"\"gęślą\"\"\nDruga linia\""))
        assertTrue(text.contains("\"Kask Żółty\""))
        assertTrue(text.contains("\"operation1\";\"line1\";\"2026-10-10\";\"2026-10-10\";\"10.10.2026 16:30\""))
        assertTrue(text.endsWith("\r\n"))
    }

    @Test fun xlsxContainsValidXmlNumericQuantitiesAndInlineTextNotFormulas() = withFile("xlsx") { file ->
        StockExporter.write(file, deliveryExportTable(day, listOf(line(note = "=SUM(A1) & <Żółty>\nNowa linia")), now), StockExportFormat.XLSX)
        ZipFile(file).use { zip ->
            assertEquals(5, zip.size())
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(it) }
            }
            val xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use { it.reader(Charsets.UTF_8).readText() }
            assertTrue(xml.contains("<c r=\"D2\"><v>4</v></c>"))
            assertTrue(xml.contains("&amp; &lt;Żółty&gt;"))
            assertFalse(xml.contains("<f>"))
            assertTrue(xml.contains("autoFilter ref=\"A1:L2\""))
        }
    }

    @Test fun stockCsvAndXlsxKeepNegativeAndUnknownValues() {
        val item = ShipyardStockItem("p", "Kask", null, "szt.", -2.0, "", "").toExportProduct()
        val table = stockExportTable("Magazyn główny", listOf(item, item.copy(id = "unknown", stockKnown = false)), now)
        withFile("csv") { file ->
            StockExporter.write(file, table, StockExportFormat.CSV)
            val csv = file.readText()
            assertTrue(csv.startsWith("\uFEFF\"Magazyn\";\"Przedmiot\""))
            assertTrue(csv.contains("\"-2\"")); assertTrue(csv.contains("\"Nieustalony\""))
        }
        withFile("xlsx") { file ->
            StockExporter.write(file, table, StockExportFormat.XLSX)
            ZipFile(file).use { zip ->
                val xml = zip.getInputStream(zip.getEntry("xl/worksheets/sheet1.xml")).use { it.reader().readText() }
                assertTrue(xml.contains("<c r=\"G2\"><v>-2</v></c>"))
                assertTrue(xml.contains("Nieustalony"))
            }
        }
    }

    @Test fun fileNamesIdentifySourceOrPeriodAndNeverContainPathSeparators() {
        assertEquals("stan-stocznia-żółta-2026-10-10-163000000.csv", exportFileName(ExportSelection(), "Stocznia Żółta", StockExportFormat.CSV, now))
        assertEquals("dostawy-2026-10-10-2026-10-10-163000000.pdf", exportFileName(day, "", StockExportFormat.PDF, now))
        assertFalse(exportFileName(ExportSelection(), "../A/B", StockExportFormat.XLSX, now).contains('/'))
    }

    @Test fun pdfWrappingPreservesLongNotesAndSplitsLongIdentifiers() {
        val note = "Zażółć gęślą jaźń\nDruga linia"
        val wrapped = wrapExportText(note, 12f) { it.length.toFloat() }
        assertTrue(wrapped.all { it.length <= 12 })
        assertEquals(note.replace('\n', ' '), wrapped.joinToString(" "))
        val id = "1234567890".repeat(8)
        assertEquals(id, wrapExportText(id, 10f) { it.length.toFloat() }.joinToString(""))
    }

    private fun line(operation: String = "operation1", id: String = "line1", product: String = "Kask Żółty", note: String = "Uwagi") =
        DeliveryReportLine(operation, id, "2026-10-10", product, "Żółty", 4.0, "szt.", "Magazyn główny", note)

    private fun withFile(extension: String, action: (File) -> Unit) {
        val file = File.createTempFile("magazyn-export-", ".$extension")
        try { action(file) } finally { file.delete() }
    }
}
