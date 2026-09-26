package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.eventually
import pl.magazyn.mobile.queryDouble
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.ui.InventoryCount
import pl.magazyn.mobile.ui.InventoryViewModel
import pl.magazyn.mobile.ui.IssueRequest
import pl.magazyn.mobile.ui.OperationLineRequest
import pl.magazyn.mobile.ui.OperationsViewModel
import pl.magazyn.mobile.ui.PeopleViewModel
import pl.magazyn.mobile.ui.ShipyardIssueRequest
import pl.magazyn.mobile.ui.ShipyardsViewModel
import pl.magazyn.mobile.ui.WarehouseOperationType

@RunWith(AndroidJUnit4::class)
class CoreWarehouseOperationsTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase

    @Before
    fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData()
    }

    @After
    fun cleanup() {
        environment.close()
    }

    @Test
    fun deliveryCreatesMovementLineAndIncreasesStock() = runBlocking {
        val viewModel = OperationsViewModel(environment.application)
        val collectorScope = CoroutineScope(Dispatchers.Main)
        val collector = collectorScope.launch { viewModel.warehouses.collect { } }
        try {
            eventually("Magazyn główny nie pojawił się w stanie ViewModelu") {
                viewModel.warehouses.value.any { it.id == "warehouse-main" }
            }

            viewModel.submit(
                WarehouseOperationType.DELIVERY,
                employeeId = null,
                shipyardId = null,
                lines = listOf(OperationLineRequest("product-1", 4)),
                effectiveDate = "2026-09-15",
            )
            eventually("Dostawa nie została zapisana") { movementCount("DELIVERY") == 1L }

            assertEquals(14.0, stock(), 0.0)
            assertEquals(4.0, movementDelta("DELIVERY"), 0.0)
        } finally {
            collector.cancel()
            collectorScope.cancel()
        }
    }

    @Test
    fun issueAndReturnForEmployeeUpdateStockHistoryAndCustody() = runBlocking {
        val viewModel = PeopleViewModel(environment.application)
        viewModel.issueToPerson("employee-1", listOf(IssueRequest("product-1", 3)), "2026-09-15")
        eventually("Wydanie pracownikowi nie zostało zapisane") { movementCount("ISSUE") == 1L }

        assertEquals(7.0, stock(), 0.0)
        assertEquals(-3.0, movementDelta("ISSUE"), 0.0)
        assertEquals(3.0, database.queryDouble("SELECT quantity FROM custodies WHERE employeeId='employee-1' AND returnedDate IS NULL"), 0.0)

        val issue = database.movementDao().observeEmployeeIssues("employee-1").first { it.isNotEmpty() }.first()
        viewModel.returnIssue("employee-1", issue, 2, "2026-09-16")
        eventually("Zwrot pracownika nie został zapisany") { movementCount("RETURN") == 1L }

        assertEquals(9.0, stock(), 0.0)
        assertEquals(2.0, movementDelta("RETURN"), 0.0)
        assertEquals(1.0, database.queryDouble("SELECT quantity FROM custodies WHERE employeeId='employee-1' AND returnedDate IS NULL"), 0.0)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM issue_returns"))
    }

    @Test
    fun issueAndReturnForShipyardUpdateBothStocksAndHistory() = runBlocking {
        val shipyard = ShipyardEntity("shipyard-1", "Ulstein")
        database.shipyardDao().insert(shipyard)
        val viewModel = ShipyardsViewModel(environment.application)

        viewModel.issue(shipyard, listOf(ShipyardIssueRequest("product-1", 4)), "2026-09-15")
        eventually("Wydanie do stoczni nie zostało zapisane") { movementCount("SHIPYARD_ISSUE") == 1L }
        assertEquals(6.0, stock(), 0.0)
        assertEquals(4.0, database.shipyardDao().findStock("shipyard-1", "product-1")?.quantity ?: Double.NaN, 0.0)
        assertEquals(-4.0, movementDelta("SHIPYARD_ISSUE"), 0.0)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='SHIPYARD_ISSUE' AND shipyardId='shipyard-1'"))

        viewModel.returnToMainWarehouse(shipyard, listOf(ShipyardIssueRequest("product-1", 2)), "2026-09-16")
        eventually("Zwrot ze stoczni nie został zapisany") { movementCount("SHIPYARD_RETURN") == 1L }
        assertEquals(8.0, stock(), 0.0)
        assertEquals(2.0, database.shipyardDao().findStock("shipyard-1", "product-1")?.quantity ?: Double.NaN, 0.0)
        assertEquals(2.0, movementDelta("SHIPYARD_RETURN"), 0.0)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='SHIPYARD_RETURN' AND shipyardId='shipyard-1'"))
    }

    @Test
    fun inventoryCorrectionSetsCountAndRecordsExactDelta() = runBlocking {
        val viewModel = InventoryViewModel(environment.application)
        viewModel.applyInventory("warehouse-main", listOf(InventoryCount("product-1", 6)), "2026-09-15")
        eventually("Korekta inwentaryzacyjna nie została zapisana") { movementCount("INVENTORY_CORRECTION") == 1L }

        assertEquals(6.0, stock(), 0.0)
        assertEquals(-4.0, movementDelta("INVENTORY_CORRECTION"), 0.0)
    }

    @Test
    fun issueCanProduceNegativeStock() = runBlocking {
        database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", 1.0)))
        val viewModel = PeopleViewModel(environment.application)

        viewModel.issueToPerson("employee-1", listOf(IssueRequest("product-1", 3)), "2026-09-15")
        eventually("Wydanie prowadzące do ujemnego stanu nie zostało zapisane") { movementCount("ISSUE") == 1L }

        assertEquals(-2.0, stock(), 0.0)
        assertEquals(-3.0, movementDelta("ISSUE"), 0.0)
    }

    private suspend fun stock(): Double = database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN

    private fun movementCount(type: String): Long =
        database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type=?", type)

    private fun movementDelta(type: String): Double = database.queryDouble(
        "SELECT l.quantityDelta FROM stock_movement_lines l JOIN stock_movements m ON m.id=l.movementId WHERE m.type=? LIMIT 1",
        type,
    )
}
