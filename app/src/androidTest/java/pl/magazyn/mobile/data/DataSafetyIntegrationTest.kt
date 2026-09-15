package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.awaitViewModelWork
import pl.magazyn.mobile.eventually
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.ImportedProductResolution
import pl.magazyn.mobile.ui.IssueRequest
import pl.magazyn.mobile.ui.OrdersViewModel
import pl.magazyn.mobile.ui.PeopleViewModel

@RunWith(AndroidJUnit4::class)
class DataSafetyIntegrationTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase

    @Before
    fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData(stock = 10.0)
    }

    @After
    fun cleanup() {
        environment.close()
    }

    @Test
    fun orderRealizationIsIdempotentIncludingRapidRepeatedCalls() = runBlocking {
        database.orderDao().upsertOrders(
            listOf(OrderEntity("order-1", null, "employee-1", "Kowalski Jan", null, "DRAFT", "2026-09-15", 1)),
        )
        database.orderDao().upsertLines(
            listOf(OrderLineEntity("order-line-1", "order-1", "product-1", "Produkt 52", 3.0, "szt.", "VERIFIED")),
        )
        val viewModel = OrdersViewModel(environment.application)

        withContext(Dispatchers.Main) {
            repeat(8) { viewModel.realize("order-1", "employee-1", "2026-09-15", ignoreWarnings = true) }
        }
        eventually("Zamówienie nie zostało zrealizowane") { database.orderDao().findById("order-1")?.status == "ISSUED" }
        database.awaitViewModelWork()

        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE note='Realizacja zamówienia'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals(7.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
        assertEquals("ISSUED", database.orderDao().findById("order-1")?.status)

        viewModel.realize("order-1", "employee-1", "2026-09-15", ignoreWarnings = true)
        database.awaitViewModelWork()
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE note='Realizacja zamówienia'"))
        assertEquals(7.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
    }

    @Test
    fun confirmDespiteWarningStillUsesIdempotentRealizationPath() = runBlocking {
        val historyMovement = StockMovementEntity("old-issue", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-09-15", createdAtEpochMillis = 1)
        database.movementDao().insertMovement(historyMovement)
        database.movementDao().insertLine(StockMovementLineEntity("old-line", "old-issue", "product-1", -1.0, "szt."))
        database.orderDao().upsertOrders(
            listOf(OrderEntity("warning-order", null, "employee-1", "Kowalski Jan", null, "DRAFT", "2026-09-15", 2)),
        )
        database.orderDao().upsertLines(
            listOf(OrderLineEntity("warning-line", "warning-order", "product-1", "Produkt 52", 2.0, "szt.", "VERIFIED")),
        )
        val viewModel = OrdersViewModel(environment.application)

        viewModel.realize("warning-order", "employee-1", "2026-09-15")
        eventually("Nie pojawiło się ostrzeżenie o ponownym wydaniu") { viewModel.issueWarning.value != null }
        withContext(Dispatchers.Main) {
            viewModel.confirmIssueDespiteWarning()
            viewModel.confirmIssueDespiteWarning()
        }
        eventually("Zamówienie po potwierdzeniu ostrzeżenia nie zostało zrealizowane") {
            database.orderDao().findById("warning-order")?.status == "ISSUED"
        }
        database.awaitViewModelWork()

        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE note='Realizacja zamówienia'"))
        assertEquals(7.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
    }

    @Test
    fun missingProductMakesMultiItemIssueAllOrNothing() = runBlocking {
        val viewModel = PeopleViewModel(environment.application)

        viewModel.issueToPerson(
            "employee-1",
            listOf(IssueRequest("product-1", 2), IssueRequest("missing-product", 1)),
            "2026-09-15",
        )
        database.awaitViewModelWork()

        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM custodies"))
        assertEquals(10.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
    }

    @Test
    fun unresolvedOrderLineDoesNotIssueOrChangeOrderStatus() = runBlocking {
        database.orderDao().upsertOrders(
            listOf(OrderEntity("unresolved-order", null, "employee-1", "Kowalski Jan", null, "DRAFT", "2026-09-15", 3)),
        )
        database.orderDao().upsertLines(
            listOf(
                OrderLineEntity("valid-line", "unresolved-order", "product-1", "Produkt 52", 2.0, "szt.", "VERIFIED"),
                OrderLineEntity("missing-line", "unresolved-order", null, "Brak produktu", 1.0, "szt.", "NEEDS_MAPPING"),
            ),
        )
        val viewModel = OrdersViewModel(environment.application)

        viewModel.realize("unresolved-order", "employee-1", "2026-09-15", ignoreWarnings = true)
        database.awaitViewModelWork()

        assertEquals("DRAFT", database.orderDao().findById("unresolved-order")?.status)
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals(10.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
    }

    @Test
    fun cancelledOrderCannotBeRealized() = runBlocking {
        database.orderDao().upsertOrders(
            listOf(OrderEntity("cancelled-order", null, "employee-1", "Kowalski Jan", null, "CANCELLED", "2026-09-15", 4)),
        )
        database.orderDao().upsertLines(
            listOf(OrderLineEntity("cancelled-line", "cancelled-order", "product-1", "Produkt 52", 2.0, "szt.", "VERIFIED")),
        )
        val viewModel = OrdersViewModel(environment.application)

        viewModel.realize("cancelled-order", "employee-1", "2026-09-15", ignoreWarnings = true)
        database.awaitViewModelWork()

        assertEquals("CANCELLED", database.orderDao().findById("cancelled-order")?.status)
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(10.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
    }

    @Test
    fun roomProductsStillResolveImportedVariantWithoutDependingOnQueryOrder() = runBlocking {
        database.productDao().insert(ProductEntity("product-54", "Produkt", "54", "szt."))
        val products = database.productDao().getAllNow()

        val exact = ImportParser.resolveImportedProduct("Produkt 52", products)
        val ambiguous = ImportParser.resolveImportedProduct("Produkt", products)
        val absent = ImportParser.resolveImportedProduct("Produkt 56", products)

        assertEquals("product-1", (exact as ImportedProductResolution.Matched).product.id)
        assertTrue(ambiguous is ImportedProductResolution.Ambiguous)
        assertTrue(absent is ImportedProductResolution.NotFound)
    }
}
