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
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.ShipyardStockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.data.CustodyEntity
import pl.magazyn.mobile.data.OrderEntity
import pl.magazyn.mobile.data.OrderLineEntity
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.IssueReturnEntity
import org.json.JSONArray
import org.json.JSONObject

@RunWith(AndroidJUnit4::class)
class RoomAgentDataSourceTest {
    private lateinit var environment: IsolatedApplicationEnvironment

    @Before fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        environment.database.seedCoreData(0.0)
    }

    @After fun cleanup() { environment.close() }

    @Test fun readsActualZeroAndNegativeRoomBalancesWithoutWriting() = runBlocking {
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

    @Test fun catalogReflectsRoomAndExcludesPhoneNumbers() = runBlocking {
        val database = environment.database
        database.employeeDao().update(database.employeeDao().findById("employee-1")!!.copy(phoneNumbers = "+48 123456789"))
        val catalog = RoomAgentDataSource(database).catalog(1)
        assertEquals("employee-1", catalog.getJSONArray("people").getJSONObject(0).getString("id"))
        assertEquals("product-1", catalog.getJSONArray("products").getJSONObject(0).getString("id"))
        assertFalse(catalog.toString().contains("123456789"))
    }

    @Test fun dynamicReadsUseActualCustodyShipyardOrdersAndIssueHistory() = runBlocking {
        val db = environment.database
        db.productDao().insert(ProductEntity("product-2", "Kask", unit = "szt."))
        db.shipyardDao().insert(ShipyardEntity("yard-1", "Ulstein", aliases = "Elektro", tags = "pokład"))
        db.shipyardDao().upsertStock(ShipyardStockBalanceEntity("yard-1", "product-1", -3.0))
        db.shipyardDao().upsertStock(ShipyardStockBalanceEntity("yard-1", "product-2", 4.0))
        db.movementDao().insertMovement(StockMovementEntity("m1", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-01-01", createdAtEpochMillis = 1))
        db.movementDao().insertMovement(StockMovementEntity("m2", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-02-01", createdAtEpochMillis = 2))
        db.movementDao().insertLine(StockMovementLineEntity("l1", "m1", "product-1", -2.0, "szt."))
        db.movementDao().insertLine(StockMovementLineEntity("l2", "m2", "product-1", -1.0, "szt."))
        db.movementDao().insertCustody(CustodyEntity("c1", "employee-1", "product-1", 2.0, "m1", "2026-01-01"))
        db.movementDao().insertCustody(CustodyEntity("c2", "employee-1", "product-1", 1.0, "m2", "2026-02-01"))
        db.movementDao().insertCustody(CustodyEntity("c3", "employee-1", "product-2", 1.0, "m2", "2026-02-01", "2026-02-05"))
        db.movementDao().insertIssueReturn(IssueReturnEntity("return-1", "l1", 1.0, "2026-01-02", 3))
        db.movementDao().insertMovement(StockMovementEntity("y1", "SHIPYARD_ISSUE", "warehouse-main", null, "Ulstein", "2026-01-01", 1, shipyardId = "yard-1"))
        db.movementDao().insertLine(StockMovementLineEntity("yl1", "y1", "product-1", -4.0, "szt."))
        db.orderDao().upsertOrders(listOf(
            OrderEntity("o1", null, "employee-1", "Jan", null, "DRAFT", "2026-03-01", 1),
            OrderEntity("o2", null, "employee-1", "Jan", null, "CANCELLED", "2026-03-02", 2),
            OrderEntity("o3", null, null, "Ulstein", "Ulstein", "DRAFT", "2026-03-03", 3, "yard-1"),
        ))
        db.orderDao().upsertLines(listOf(OrderLineEntity("ol1", "o1", "product-1", "Produkt", 2.0, "szt.", "VERIFIED")))
        val source = RoomAgentDataSource(db)
        assertEquals(2, source.personItems("employee-1").size)
        assertEquals(listOf("2026-02-01", "2026-01-01"), source.personItems("employee-1").map { it.issuedDate })
        assertEquals(listOf(-3.0, 4.0), source.shipyardStock("yard-1").map { it.quantity })
        assertEquals(listOf("o1"), source.activeOrders(AgentRecipientKey("person", "employee-1")).map { it.orderId })
        assertEquals(listOf("o3"), source.activeOrders(AgentRecipientKey("shipyard", "yard-1")).map { it.orderId })
        assertEquals(2, source.recentIssues(AgentRecipientKey("person", "employee-1"), 20).size)
        assertEquals("l2", source.recentIssues(AgentRecipientKey("person", "employee-1"), 1).single().lineId)
        assertEquals(1, source.recentIssues(AgentRecipientKey("shipyard", "yard-1"), 20).size)
        try { source.personItems("missing"); fail("Unknown person") } catch (_: AgentFailure) { }
        try { source.shipyardStock("missing"); fail("Unknown shipyard") } catch (_: AgentFailure) { }
        try { source.recentIssues(AgentRecipientKey("person", "employee-1"), 21); fail("Unbounded history") } catch (_: AgentFailure) { }
        val catalog = source.catalog(2).toString()
        assertTrue(catalog.contains("Elektro"))
        assertTrue(catalog.contains("pokład"))
    }

    @Test fun sessionCombinesRoomCustodyAndStockResultsBeforeReview() = runBlocking {
        val db = environment.database
        db.movementDao().insertMovement(StockMovementEntity("m", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-01-01", createdAtEpochMillis = 1))
        db.movementDao().insertCustody(CustodyEntity("c", "employee-1", "product-1", 1.0, "m", "2026-01-01"))
        val client = object : AgentClient {
            var received: JSONObject? = null
            override suspend fun authStatus() = JSONObject().put("account", JSONObject().put("type", "chatgpt"))
            override suspend fun fullSync(catalog: JSONObject) { assertEquals(1L, catalog.getLong("revision")) }
            override suspend fun message(text: String): AgentReply = reply("needs_data", JSONArray()
                .put(request("r1", "get_person_current_items", JSONObject().put("personId", "employee-1")))
                .put(request("r2", "get_current_stock", JSONObject().put("productIds", JSONArray().put("product-1")))))
            override suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply {
                assertEquals("same-session", sessionId)
                received = results
                return reply("proposal", JSONArray())
            }
            override suspend fun choice(sessionId: String, candidateId: String): AgentReply = error("Unexpected choice")
        }
        val repository = AgentRepository(client, RoomAgentDataSource(db)) { 1 }
        val result = repository.analyze("Jan rękawice")
        assertEquals(AgentStatus.PROPOSAL, result.status)
        val sent = client.received!!.getJSONArray("results")
        assertEquals("r1", sent.getJSONObject(0).getString("requestId"))
        assertEquals("2026-01-01", sent.getJSONObject(0).getJSONObject("data").getJSONArray("items").getJSONObject(0).getString("issuedDate"))
        assertEquals("r2", sent.getJSONObject(1).getString("requestId"))
        assertEquals(0.0, sent.getJSONObject(1).getJSONObject("data").getJSONArray("stocks").getJSONObject(0).getDouble("available"), 0.0)
        assertEquals("product-1", repository.review(result).items.single().productId)
    }

    @Test fun sessionReadsRealShipyardStockBeforeProposal() = runBlocking {
        val db = environment.database
        db.shipyardDao().insert(ShipyardEntity("yard-1", "Ulstein"))
        db.shipyardDao().upsertStock(ShipyardStockBalanceEntity("yard-1", "product-1", -4.0))
        val client = object : AgentClient {
            override suspend fun authStatus() = JSONObject().put("account", JSONObject().put("type", "chatgpt"))
            override suspend fun fullSync(catalog: JSONObject) { }
            override suspend fun message(text: String) = reply("needs_data", JSONArray()
                .put(request("yard-request", "get_shipyard_stock", JSONObject().put("shipyardId", "yard-1"))))
            override suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply {
                assertEquals("same-session", sessionId)
                val answer = results.getJSONArray("results").getJSONObject(0)
                assertEquals("yard-request", answer.getString("requestId"))
                assertEquals("get_shipyard_stock", answer.getString("tool"))
                assertEquals(-4.0, answer.getJSONObject("data").getJSONArray("stocks").getJSONObject(0).getDouble("quantity"), 0.0)
                return reply("proposal", JSONArray())
            }
            override suspend fun choice(sessionId: String, candidateId: String): AgentReply = error("Unexpected choice")
        }
        assertEquals(AgentStatus.PROPOSAL, AgentRepository(client, RoomAgentDataSource(db)) { 1 }.analyze("Na Ulstein").status)
    }

    @Test fun manualShipyardAssignmentOverridesUncertainHistoricalLabelsWithoutChangingThem() = runBlocking {
        val db = environment.database
        db.shipyardDao().insert(ShipyardEntity("yard-1", "Ulstein", aliases = "Stara"))
        db.shipyardDao().insert(ShipyardEntity("yard-2", "Vard", aliases = "Stara"))
        db.orderDao().upsertOrders(listOf(OrderEntity("o", null, null, "Stara", "Stara", "DRAFT", "2026-01-01", 1)))
        db.movementDao().insertMovement(StockMovementEntity("m", "SHIPYARD_ISSUE", "warehouse-main", null, "Stara", "2026-01-01", 1))
        db.movementDao().insertLine(StockMovementLineEntity("l", "m", "product-1", -2.0, "szt."))
        val source = RoomAgentDataSource(db)
        assertTrue(source.activeOrders(AgentRecipientKey("shipyard", "yard-1")).isEmpty())
        assertTrue(source.recentIssues(AgentRecipientKey("shipyard", "yard-1"), 20).isEmpty())
        assertEquals(1, db.orderDao().assignShipyard("o", "yard-2"))
        assertEquals(1, db.movementDao().assignShipyard("m", "yard-2"))
        assertEquals("Stara", db.orderDao().findById("o")!!.recipientLabel)
        assertEquals("Stara", db.movementDao().findMovement("m")!!.recipientLabel)
        assertEquals("yard-2", db.orderDao().findById("o")!!.shipyardId)
        assertEquals(listOf("o"), source.activeOrders(AgentRecipientKey("shipyard", "yard-2")).map { it.orderId })
        assertEquals(listOf("l"), source.recentIssues(AgentRecipientKey("shipyard", "yard-2"), 20).map { it.lineId })
    }

    private fun request(id: String, tool: String, args: JSONObject) = JSONObject().put("id", id).put("tool", tool).put("arguments", args)
    private fun reply(status: String, reads: JSONArray) = AgentProtocol.parse(JSONObject().put("schemaVersion", 1)
        .put("sessionId", "same-session").put("status", status).put("intent", "ORDER")
        .put("recipient", JSONObject().put("id", "employee-1").put("label", "Jan").put("kind", "person"))
        .put("deliveryDate", JSONObject.NULL).put("items", JSONArray().put(JSONObject().put("productId", "product-1")
            .put("label", "Produkt").put("quantity", 1).put("unit", "szt.").put("available", if (status == "proposal") 0 else JSONObject.NULL)))
        .put("warnings", JSONArray()).put("questions", JSONArray()).put("candidates", JSONArray()).put("needsData", reads)
        .put("error", JSONObject.NULL).toString())
}
