package pl.magazyn.mobile.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.data.StockBalanceEntity

@RunWith(AndroidJUnit4::class)
class RoomAgentDataSourceTest {
    private lateinit var environment: IsolatedApplicationEnvironment

    @Before fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        environment.database.seedCoreData(0.0)
    }

    @After fun cleanup() { environment.close() }

    @Test fun `reads actual zero and negative Room balances without writing`() = runBlocking {
        val database = environment.database
        val source = RoomAgentDataSource(database)
        assertEquals(listOf("product-1" to 0.0), source.currentStock(listOf("product-1")))
        database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", -2.5)))
        assertEquals(listOf("product-1" to -2.5), source.currentStock(listOf("product-1")))
        assertEquals(-2.5, database.stockDao().find("warehouse-main", "product-1")!!.quantity, 0.0)
        try { source.currentStock(listOf("missing")); fail("Unknown ID accepted") } catch (_: AgentFailure) { }
        database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", 0.0, false)))
        try { source.currentStock(listOf("product-1")); fail("Unknown balance accepted") } catch (_: AgentFailure) { }
    }

    @Test fun `catalog reflects Room and excludes phone numbers`() = runBlocking {
        val database = environment.database
        database.employeeDao().update(database.employeeDao().findById("employee-1")!!.copy(phoneNumbers = "+48 123456789"))
        val catalog = RoomAgentDataSource(database).catalog(1)
        assertEquals("employee-1", catalog.getJSONArray("people").getJSONObject(0).getString("id"))
        assertEquals("product-1", catalog.getJSONArray("products").getJSONObject(0).getString("id"))
        assertFalse(catalog.toString().contains("123456789"))
    }
}
