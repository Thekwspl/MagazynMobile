package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.runAndAwaitViewModelWork
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.ui.OrdersViewModel

@RunWith(AndroidJUnit4::class)
class PartialOrderFulfillmentTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase

    @Before
    fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData(stock = 10.0)
        database.productDao().insert(ProductEntity("product-2", "Produkt B", "54", "szt.", isReturnable = true))
        database.productDao().insert(ProductEntity("product-3", "Produkt C", "L", "szt.", isReturnable = true))
        database.stockDao().upsert(
            listOf(
                StockBalanceEntity("warehouse-main", "product-2", 10.0),
                StockBalanceEntity("warehouse-main", "product-3", 10.0),
            ),
        )
    }

    @After
    fun cleanup() {
        environment.close()
    }

    @Test
    fun partialIssueMovesOnlySelectedLinesAndLeavesTheRestDraft() = runBlocking {
        seedThreeLineOrder()
        val viewModel = OrdersViewModel(environment.application)

        issue(viewModel, setOf("line-a", "line-c"))

        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='ISSUE'"))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals(1L, movementLineCount("product-1"))
        assertEquals(0L, movementLineCount("product-2"))
        assertEquals(1L, movementLineCount("product-3"))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM custodies"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE productId='product-2'"))
        assertStock("product-1", 8.0)
        assertStock("product-2", 10.0)
        assertStock("product-3", 6.0)
        assertEquals("DRAFT", database.orderDao().findById("partial-order")?.status)
        assertEquals(listOf("line-b"), database.orderDao().getLinesNow("partial-order").map { it.id })
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM orders WHERE notebookId='partial-notebook' AND status='ISSUED'"))
    }

    @Test
    fun remainingLineCanBeIssuedLaterWithoutReissuingPreviousLines() = runBlocking {
        seedThreeLineOrder()
        val viewModel = OrdersViewModel(environment.application)
        issue(viewModel, setOf("line-a", "line-c"))
        database.orderDao().setPrepared("line-b", true)

        issue(viewModel, setOf("line-b"))

        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='ISSUE'"))
        assertEquals(3L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals(1L, movementLineCount("product-1"))
        assertEquals(1L, movementLineCount("product-2"))
        assertEquals(1L, movementLineCount("product-3"))
        assertStock("product-1", 8.0)
        assertStock("product-2", 7.0)
        assertStock("product-3", 6.0)
        assertEquals("ISSUED", database.orderDao().findById("partial-order")?.status)
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM orders WHERE notebookId='partial-notebook' AND status='ISSUED'"))
    }

    @Test
    fun noSelectionDoesNotWriteAnything() = runBlocking {
        seedThreeLineOrder()
        val viewModel = OrdersViewModel(environment.application)

        issue(viewModel, emptySet())

        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals("DRAFT", database.orderDao().findById("partial-order")?.status)
        assertStock("product-1", 10.0)
        assertStock("product-2", 10.0)
        assertStock("product-3", 10.0)
    }

    @Test
    fun repeatedPartialIssueCannotCreateDuplicateMovement() = runBlocking {
        seedThreeLineOrder()
        val viewModel = OrdersViewModel(environment.application)

        viewModel.runAndAwaitViewModelWork {
            repeat(8) {
                viewModel.realize(
                    "partial-order", "employee-1", null, "2026-09-15",
                    setOf("line-a", "line-c"), ignoreWarnings = true,
                )
            }
        }

        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='ISSUE'"))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertStock("product-1", 8.0)
        assertStock("product-3", 6.0)
    }

    @Test
    fun warningOnUnselectedLineDoesNotAffectSelectedIssue() = runBlocking {
        seedThreeLineOrder()
        database.movementDao().insertMovement(
            StockMovementEntity("previous-b", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-09-15", createdAtEpochMillis = 1),
        )
        database.movementDao().insertLine(StockMovementLineEntity("previous-b-line", "previous-b", "product-2", -1.0, "szt."))
        val viewModel = OrdersViewModel(environment.application)

        viewModel.runAndAwaitViewModelWork {
            viewModel.realize("partial-order", "employee-1", null, "2026-09-15", setOf("line-a"))
        }

        assertNull(viewModel.issueWarning.value)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE note='Realizacja zamówienia'"))
        assertStock("product-1", 8.0)
        assertStock("product-2", 10.0)
    }

    @Test
    fun issuingOneRecipientPartDoesNotTouchAnotherRecipientOrShipyardPart() = runBlocking {
        database.notebookDao().insertNotebook(OrderNotebookEntity("multi-notebook", "Wiele odbiorców", "VERIFIED", "ORDER", 1))
        database.orderDao().upsertOrders(
            listOf(
                OrderEntity("person-part", "multi-notebook", "employee-1", "Jan Kowalski", null, "DRAFT", "2026-09-15", 1),
                OrderEntity("shipyard-part", "multi-notebook", null, "Stocznia Alfa", "Stocznia Alfa", "DRAFT", "2026-09-15", 1),
            ),
        )
        database.orderDao().upsertLines(
            listOf(
                OrderLineEntity("person-line", "person-part", "product-1", "Produkt 52", 2.0, "szt.", "VERIFIED", true),
                OrderLineEntity("shipyard-line", "shipyard-part", "product-2", "Produkt B 54", 3.0, "szt.", "VERIFIED", true),
            ),
        )
        val viewModel = OrdersViewModel(environment.application)

        viewModel.runAndAwaitViewModelWork {
            viewModel.realize("person-part", "employee-1", null, "2026-09-15", setOf("person-line"), ignoreWarnings = true)
        }

        assertEquals("ISSUED", database.orderDao().findById("person-part")?.status)
        assertEquals("DRAFT", database.orderDao().findById("shipyard-part")?.status)
        assertEquals(0L, movementLineCount("product-2"))
        assertStock("product-2", 10.0)
    }

    @Test
    fun cancellingAfterPartialIssueKeepsIssuedPartAndCancelsDraftRemainder() = runBlocking {
        seedThreeLineOrder()
        val viewModel = OrdersViewModel(environment.application)
        issue(viewModel, setOf("line-a", "line-c"))

        viewModel.runAndAwaitViewModelWork { viewModel.cancelOrder("partial-order") }

        assertEquals("CANCELLED", database.orderDao().findById("partial-order")?.status)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM orders WHERE notebookId='partial-notebook' AND status='ISSUED'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM orders WHERE notebookId='partial-notebook' AND status='CANCELLED'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='ISSUE'"))
    }

    @Test
    fun selectedShipyardRecipientUsesExistingShipyardIssuePath() = runBlocking {
        database.shipyardDao().insert(ShipyardEntity("shipyard-1", "Stocznia Alfa"))
        database.orderDao().upsertOrders(
            listOf(OrderEntity("shipyard-order", null, null, "", null, "DRAFT", "2026-09-15", 1)),
        )
        database.orderDao().upsertLines(
            listOf(OrderLineEntity("shipyard-order-line", "shipyard-order", "product-1", "Produkt 52", 3.0, "szt.", "VERIFIED", true)),
        )
        val viewModel = OrdersViewModel(environment.application)

        viewModel.runAndAwaitViewModelWork {
            viewModel.updateOrder("shipyard-order", null, "Stocznia Alfa", "Stocznia Alfa", "2026-09-15")
        }
        assertEquals("Stocznia Alfa", database.orderDao().findById("shipyard-order")?.siteLabel)
        assertEquals("shipyard-1", database.orderDao().findById("shipyard-order")?.shipyardId)

        viewModel.runAndAwaitViewModelWork {
            viewModel.realize(
                "shipyard-order", null, "Stocznia Alfa", "2026-09-15",
                setOf("shipyard-order-line"), ignoreWarnings = true,
            )
        }

        assertEquals("ISSUED", database.orderDao().findById("shipyard-order")?.status)
        assertEquals("shipyard-1", database.orderDao().findById("shipyard-order")?.shipyardId)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='SHIPYARD_ISSUE' AND shipyardId='shipyard-1'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='SHIPYARD_ISSUE' AND recipientLabel='Stocznia Alfa'"))
        assertStock("product-1", 7.0)
        assertEquals(3.0, database.shipyardDao().findStock("shipyard-1", "product-1")?.quantity ?: Double.NaN, 0.0)
    }

    private suspend fun seedThreeLineOrder() {
        database.notebookDao().insertNotebook(OrderNotebookEntity("partial-notebook", "Pakiet A B C", "VERIFIED", "ORDER", 1))
        database.orderDao().upsertOrders(
            listOf(OrderEntity("partial-order", "partial-notebook", "employee-1", "Jan Kowalski", null, "DRAFT", "2026-09-15", 1)),
        )
        database.orderDao().upsertLines(
            listOf(
                OrderLineEntity("line-a", "partial-order", "product-1", "Produkt 52", 2.0, "szt.", "VERIFIED", true),
                OrderLineEntity("line-b", "partial-order", "product-2", "Produkt B 54", 3.0, "szt.", "VERIFIED", false),
                OrderLineEntity("line-c", "partial-order", "product-3", "Produkt C L", 4.0, "szt.", "VERIFIED", true),
            ),
        )
    }

    private suspend fun issue(viewModel: OrdersViewModel, selectedLineIds: Set<String>) {
        viewModel.runAndAwaitViewModelWork {
            viewModel.realize(
                "partial-order", "employee-1", null, "2026-09-15",
                selectedLineIds, ignoreWarnings = true,
            )
        }
    }

    private fun movementLineCount(productId: String): Long =
        database.queryLong("SELECT COUNT(*) FROM stock_movement_lines WHERE productId=?", productId)

    private suspend fun assertStock(productId: String, expected: Double) {
        assertEquals(expected, database.stockDao().find("warehouse-main", productId)?.quantity ?: Double.NaN, 0.0)
    }
}
