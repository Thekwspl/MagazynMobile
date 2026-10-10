package pl.magazyn.mobile.data

import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.seedCoreData

@RunWith(AndroidJUnit4::class)
class ExportDaoTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase

    @Before fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData()
        database.productDao().insert(ProductEntity("product-2", "Rękawice", "10", "opak."))
    }

    @After fun cleanup() { environment.close() }

    @Test fun stockSourcesRemainSeparateAndPreserveNegativeAndUnknownStates() = runBlocking {
        val dao = database.shipyardDao()
        dao.insert(ShipyardEntity("a", "A")); dao.insert(ShipyardEntity("b", "B"))
        dao.upsertStock(ShipyardStockBalanceEntity("a", "product-1", -3.0))
        dao.upsertStock(ShipyardStockBalanceEntity("b", "product-2", 7.0))
        val main = database.productDao().observeWithStock("warehouse-main").first()
        assertEquals(10.0, main.single { it.id == "product-1" }.stockQuantity, 0.0)
        assertFalse(main.single { it.id == "product-2" }.stockKnown)
        assertEquals(listOf("product-1"), dao.observeStock("a").first().map { it.productId })
        assertEquals(-3.0, dao.observeStock("a").first().single().toExportProduct().stockQuantity, 0.0)
        assertEquals(listOf("product-2"), dao.observeStock("b").first().map { it.productId })
        assertEquals(10.0, database.stockDao().find("warehouse-main", "product-1")!!.quantity, 0.0)
    }

    @Test fun deliveryRangeUsesEffectiveDateIncludesBothEndsAndKeepsAllLines() = runBlocking {
        movement("before", "2026-10-09", created = 999)
        movement("start", "2026-10-10", created = 900)
        database.movementDao().insertLine(StockMovementLineEntity("start-2", "start", "product-2", 8.0, "opak."))
        movement("again", "2026-10-11", created = 200)
        movement("end", "2026-10-12", created = 1)
        movement("after", "2026-10-13", created = 0)
        database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", -50.0)))
        val lines = database.movementDao().observeDeliveryReport("2026-10-10", "2026-10-12").first()
        assertEquals(listOf("start", "start", "again", "end"), lines.map { it.movementId })
        assertEquals(listOf("start-1", "start-2", "again-1", "end-1"), lines.map { it.lineId })
        assertEquals(listOf(4.0, 8.0, 4.0, 4.0), lines.map { it.quantity })
        assertEquals("Uwagi start", lines.first().note)
        assertEquals("Magazyn główny", lines.first().warehouseName)
    }

    @Test fun oneDayExcludesOtherOperationTypesAndMissingPeriodsAreEmpty() = runBlocking {
        movement("delivery", "2026-10-10")
        listOf("RETURN", "CORRECTION", "INVENTORY_CORRECTION", "ISSUE", "SHIPYARD_ISSUE", "FOUND", "HISTORICAL_ISSUE_IMPORT", "HISTORICAL_SHIPYARD_IMPORT", "TRANSFER").forEach { type ->
            movement(type, "2026-10-10", type)
        }
        assertEquals(listOf("delivery"), database.movementDao().observeDeliveryReport("2026-10-10", "2026-10-10").first().map { it.movementId })
        assertTrue(database.movementDao().observeDeliveryReport("2026-10-11", "2026-10-12").first().isEmpty())
        assertTrue(database.movementDao().observeDeliveryReport("2026-10-12", "2026-10-10").first().isEmpty())
    }

    @Test fun longDeliveryPdfHasMultipleReadablePagesAndOtherFormatsStillWrite() {
        val selection = ExportSelection(report = ExportReportType.DELIVERIES, dateFrom = "2026-10-10", dateTo = "2026-10-10")
        val lines = (1..80).map { id -> DeliveryReportLine("operation-$id", "line-$id", "2026-10-10", "Kask Żółty", "Żółty", 2.0, "szt.", "Magazyn główny",
            if (id == 1) "Zażółć gęślą jaźń. ".repeat(500) else "Uwagi; \"tekst\"") }
        val table = deliveryExportTable(selection, lines)
        StockExportFormat.entries.forEach { format ->
            val file = File(environment.root, "deliveries.${format.extension}")
            StockExporter.write(file, table, format)
            assertTrue(file.length() > 0)
            if (format == StockExportFormat.PDF) {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        assertTrue(renderer.pageCount > 1)
                        renderer.openPage(renderer.pageCount - 1).use { page -> assertEquals(842, page.width); assertEquals(595, page.height) }
                    }
                }
            }
        }
    }

    private suspend fun movement(id: String, date: String, type: String = "DELIVERY", created: Long = 0) {
        database.movementDao().insertMovement(StockMovementEntity(id, type, "warehouse-main", null, effectiveDate = date, createdAtEpochMillis = created, note = "Uwagi $id"))
        database.movementDao().insertLine(StockMovementLineEntity("$id-1", id, "product-1", 4.0, "szt."))
    }
}
