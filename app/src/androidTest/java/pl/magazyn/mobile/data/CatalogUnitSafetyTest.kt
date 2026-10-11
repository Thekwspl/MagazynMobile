package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.magazyn.mobile.*
import pl.magazyn.mobile.domain.*
import pl.magazyn.mobile.ui.HomeViewModel
import pl.magazyn.mobile.ui.OrdersViewModel
import pl.magazyn.mobile.ui.ImportViewModel

@RunWith(AndroidJUnit4::class)
class CatalogUnitSafetyTest {
    private lateinit var env: IsolatedApplicationEnvironment
    private val db get() = env.database
    @Before fun setup() = runBlocking { env = IsolatedApplicationEnvironment.create(); db.seedCoreData() }
    @After fun cleanup() { env.close() }

    @Test fun mismatchCannotWriteOrderUntilQuantityInCatalogUnitIsConfirmed() = runBlocking {
        db.productDao().update(db.productDao().findById("product-1")!!.copy(unit = "opak."))
        val vm = env.track(HomeViewModel(env.application))
        val item = ParsedItem("Produkt", "52", 10, "szt.", 1f, notes = "Historyczne uwagi", productId = "product-1", requestedUnit = "szt.")
        val note = ParsedNote(null, listOf(item), kind = ParsedInputKind.ORDER, analyzedByAi = true)
        vm.runAndAwaitViewModelWork { vm.saveDraftOrder("Produkt 10 szt.", note, listOf(item to item), false) }
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM orders"))
        assertNotNull(vm.reviewSave.value.error)
        val corrected = item.copy(confirmedCatalogUnit = "opak.")
        vm.runAndAwaitViewModelWork { vm.saveDraftOrder("Produkt 10 szt.", note, listOf(item to corrected), false) }
        val order = db.orderDao().observeOpenOrders().first().single()
        val line = db.orderDao().getLinesNow(order.id).single()
        assertEquals("opak.", line.unit)
        assertEquals(10.0, line.quantity, 0.0)
        assertTrue(line.rawText.contains("Historyczne uwagi"))
        assertEquals(10.0, db.stockDao().find("warehouse-main", "product-1")!!.quantity, 0.0)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test fun conflictingImportRollsBackWholeBatchAndKeepsCatalogAndStock() = runBlocking {
        db.productDao().insert(ProductEntity("product-2", "Przewód", unit = "m"))
        db.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-2", 3.0)))
        val vm = env.track(ImportViewModel(env.application))
        val preview = ImportPreview(ImportKind.STOCK, "fixture.xlsx", "fixture-hash", listOf(
            StockImportRow(2, "row-2", "Przewód", 15, true, "m"),
            StockImportRow(3, "row-3", "Produkt 52", 20, true, "opak.")), emptyList(), 0, emptyList(), false)
        try { vm.applyImport(preview); fail("Import niezgodnej jednostki powinien zostać odrzucony") }
        catch (expected: IllegalStateException) { assertTrue(expected.message!!.contains("Jednostka importu")) }
        assertEquals(3.0, db.stockDao().find("warehouse-main", "product-2")!!.quantity, 0.0)
        assertEquals(10.0, db.stockDao().find("warehouse-main", "product-1")!!.quantity, 0.0)
        assertEquals("szt.", db.productDao().findById("product-1")!!.unit)
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0L, db.queryLong("SELECT COUNT(*) FROM import_batches"))
    }

    @Test fun unknownProductKeepsSourceUnitAndMappingUsesCurrentCatalogWithoutChangingProduct() = runBlocking {
        val vm = env.track(HomeViewModel(env.application))
        val item = ParsedItem("Nieznany", null, 15, "m", 1f, requestedUnit = "m")
        vm.runAndAwaitViewModelWork { vm.saveDraftOrder("Nieznany 15 m", ParsedNote(null, listOf(item), kind = ParsedInputKind.ORDER), listOf(item to item), false) }
        val order = db.orderDao().observeOpenOrders().first().single()
        val line = db.orderDao().getLinesNow(order.id).single()
        assertNull(line.productId); assertEquals("m", line.unit); assertEquals("NEEDS_MAPPING", line.verificationStatus)
        db.productDao().update(db.productDao().findById("product-1")!!.copy(unit = "m"))
        val orders = env.track(OrdersViewModel(env.application))
        orders.runAndAwaitViewModelWork { orders.updateLine(line.id, "product-1", "Produkt", 15, "szt.") }
        assertEquals("m", db.orderDao().getLinesNow(order.id).single().unit)
        assertEquals("m", db.productDao().findById("product-1")!!.unit)
        assertEquals(15.0, db.orderDao().getLinesNow(order.id).single().quantity, 0.0)
    }
}
