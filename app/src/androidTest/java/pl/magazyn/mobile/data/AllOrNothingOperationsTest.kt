package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.eventually
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.runAndAwaitViewModelWork
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
class AllOrNothingOperationsTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase

    @Before
    fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData(stock = 10.0)
        database.shipyardDao().insert(ShipyardEntity("shipyard-1", "Ulstein"))
        database.shipyardDao().upsertStock(ShipyardStockBalanceEntity("shipyard-1", "product-1", 5.0))
    }

    @After
    fun cleanup() {
        environment.close()
    }

    @Test
    fun missingProductRejectsAllMultiItemWarehousePathsBeforeFirstWrite() = runBlocking {
        val people = PeopleViewModel(environment.application)
        people.runAndAwaitViewModelWork {
            people.issueToPerson(
                "employee-1",
                listOf(IssueRequest("product-1", 1), IssueRequest("missing", 1)),
                "2026-09-15",
            )
        }
        assertUnchanged()

        val shipyard = ShipyardEntity("shipyard-1", "Ulstein")
        val shipyards = ShipyardsViewModel(environment.application)
        shipyards.runAndAwaitViewModelWork {
            shipyards.issue(
                shipyard,
                listOf(ShipyardIssueRequest("product-1", 1), ShipyardIssueRequest("missing", 1)),
                "2026-09-15",
            )
        }
        assertUnchanged()

        shipyards.runAndAwaitViewModelWork {
            shipyards.returnToMainWarehouse(
                shipyard,
                listOf(ShipyardIssueRequest("product-1", 1), ShipyardIssueRequest("missing", 1)),
                "2026-09-15",
            )
        }
        assertUnchanged()

        val inventory = InventoryViewModel(environment.application)
        inventory.runAndAwaitViewModelWork {
            inventory.applyInventory(
                "warehouse-main",
                listOf(InventoryCount("product-1", 6), InventoryCount("missing", 2)),
                "2026-09-15",
            )
        }
        assertUnchanged()

        val operations = OperationsViewModel(environment.application)
        val collectorScope = CoroutineScope(Dispatchers.Main)
        val collector = collectorScope.launch { operations.warehouses.collect { } }
        try {
            eventually("Magazyn główny nie pojawił się w stanie ViewModelu") {
                operations.warehouses.value.any { it.id == "warehouse-main" }
            }
            operations.runAndAwaitViewModelWork {
                operations.submit(
                    WarehouseOperationType.DELIVERY,
                    employeeId = null,
                    shipyardId = null,
                    lines = listOf(OperationLineRequest("product-1", 1), OperationLineRequest("missing", 1)),
                    effectiveDate = "2026-09-15",
                )
            }
            assertUnchanged()

            operations.runAndAwaitViewModelWork {
                operations.submit(
                    WarehouseOperationType.SHIPYARD_RETURN,
                    employeeId = null,
                    shipyardId = "shipyard-1",
                    lines = listOf(OperationLineRequest("product-1", 1), OperationLineRequest("missing", 1)),
                    effectiveDate = "2026-09-15",
                )
            }
            assertUnchanged()
        } finally {
            collector.cancel()
            collectorScope.cancel()
        }
    }

    private suspend fun assertUnchanged() {
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movements"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM stock_movement_lines"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM custodies"))
        assertEquals(10.0, database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN, 0.0)
        assertEquals(5.0, database.shipyardDao().findStock("shipyard-1", "product-1")?.quantity ?: Double.NaN, 0.0)
    }
}
