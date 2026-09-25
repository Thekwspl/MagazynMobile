package pl.magazyn.mobile.agent

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.ShipyardLeaderLink
import pl.magazyn.mobile.data.TaskPlaceView

class AgentIntegrationTest {
    private val product = ProductEntity("g", "Rękawice", "XL", "para")
    private val person = EmployeeSummary("p", "Jan Kowalski", "Jan", "Kowalski", "123456789", "Jasio", "sekret", "Spawacz", true)

    private fun response(status: String, products: List<String> = listOf("g")): String = JSONObject()
        .put("schemaVersion", 1).put("sessionId", "s1").put("status", status).put("intent", "ORDER")
        .put("recipient", JSONObject().put("id", "p").put("label", "Jan Kowalski").put("kind", "person"))
        .put("deliveryDate", JSONObject.NULL)
        .put("items", JSONArray().apply { products.forEach { put(JSONObject().put("productId", it).put("label", "Rękawice XL")
            .put("quantity", 2).put("unit", "para").put("available", if (status == "proposal") 0 else JSONObject.NULL)) } })
        .put("warnings", JSONArray()).put("questions", JSONArray().put("Który produkt?"))
        .put("candidates", JSONArray().put(JSONObject().put("id", "g").put("label", "Rękawice XL").put("kind", "product")))
        .put("needsData", JSONArray().apply { if (status == "needs_data") put(JSONObject().put("id", "r1")
            .put("tool", "get_current_stock").put("arguments", JSONObject().put("productIds", JSONArray(products)))) })
        .put("error", if (status == "error") JSONObject().put("code", "X").put("message", "Błąd agenta") else JSONObject.NULL)
        .toString()

    @Test fun `protocol maps every status and rejects incompatible or broken payload`() {
        for (status in listOf("needs_data", "needs_user_choice", "proposal", "error")) {
            assertEquals(status.uppercase(), AgentProtocol.parse(response(status)).status.name)
        }
        assertEquals("Błąd agenta", AgentProtocol.parse(response("error")).error)
        for (bad in listOf("{", JSONObject(response("proposal")).put("schemaVersion", 2).toString(),
            JSONObject(response("proposal")).remove("items").toString())) {
            try { AgentProtocol.parse(bad); fail("Accepted invalid response") } catch (_: AgentFailure) { }
        }
    }

    @Test fun `catalog exports only matching fields with stable IDs`() {
        val json = AgentCatalog.export(10, listOf(person), listOf(product), listOf(ShipyardEntity("s", "Gdańsk")),
            listOf(ShipyardLeaderLink("s", "p")), listOf(TaskPlaceView("t", "Pokład", false, "Deck")))
        assertEquals("p", json.getJSONArray("people").getJSONObject(0).getString("id"))
        assertEquals("g", json.getJSONArray("products").getJSONObject(0).getString("id"))
        assertEquals("s", json.getJSONArray("shipyards").getJSONObject(0).getString("id"))
        assertEquals("t", json.getJSONArray("taskPlaces").getJSONObject(0).getString("id"))
        assertEquals("p", json.getJSONArray("shipyards").getJSONObject(0).getJSONArray("leaders").getString(0))
        assertFalse(json.toString().contains("123456789"))
        assertFalse(json.toString().contains("sekret"))
        assertFalse(json.toString().contains("hrappka", true))
    }

    private class Source(private val stocks: Map<String, Double>) : AgentDataSource {
        override suspend fun catalog(revision: Long) = JSONObject().put("revision", revision)
        override suspend fun currentStock(ids: List<String>): List<Pair<String, Double>> = ids.map {
            it to (stocks[it] ?: throw AgentFailure("Nieznany produkt: $it"))
        }
        override suspend fun product(id: String) = if (id == "g") ProductEntity("g", "Rękawice", "XL", "para") else null
        override suspend fun person(id: String) = if (id == "p") EmployeeSummary("p", "Jan Kowalski", "Jan", "Kowalski", "", "", "", "") else null
        override suspend fun shipyard(id: String): ShipyardEntity? = null
    }

    private inner class Client(private val initial: String) : AgentClient {
        var sent: JSONObject? = null
        var chosen: String? = null
        override suspend fun authStatus() = JSONObject().put("account", JSONObject().put("type", "chatgpt"))
        override suspend fun fullSync(catalog: JSONObject) { assertEquals(1L, catalog.getLong("revision")) }
        override suspend fun message(text: String) = AgentProtocol.parse(initial)
        override suspend fun toolResults(sessionId: String, results: JSONObject): AgentReply {
            assertEquals("s1", sessionId)
            sent = results
            return AgentProtocol.parse(response("proposal"))
        }
        override suspend fun choice(sessionId: String, candidateId: String): AgentReply {
            assertEquals("s1", sessionId)
            chosen = candidateId
            return AgentProtocol.parse(response("needs_data"))
        }
    }

    @Test fun `message needs data sends actual stock tagged with request and opens review only`() = runBlocking {
        for (quantity in listOf(0.0, -3.0, 7.0)) {
            val client = Client(response("needs_data"))
            val repository = AgentRepository(client, Source(mapOf("g" to quantity)), { 1 })
            val reply = repository.analyze("Rękawice")
            assertEquals(AgentStatus.PROPOSAL, reply.status)
            val result = client.sent!!.getJSONArray("results").getJSONObject(0)
            assertEquals("r1", result.getString("requestId"))
            assertEquals(quantity, result.getJSONObject("data").getJSONArray("stocks").getJSONObject(0).getDouble("available"), 0.0)
            assertEquals("g", repository.review(reply).items.single().productId)
        }
    }

    @Test fun `multiple IDs and unknown ID do not fabricate stock`() = runBlocking {
        val client = Client(response("needs_data", listOf("g", "o")))
        AgentRepository(client, Source(mapOf("g" to 0.0, "o" to -1.0)), { 1 }).analyze("dwa produkty")
        assertEquals(2, client.sent!!.getJSONArray("results").getJSONObject(0).getJSONObject("data").getJSONArray("stocks").length())
        try { AgentRepository(Client(response("needs_data", listOf("unknown"))), Source(emptyMap()), { 1 }).analyze("obcy")
            fail("Accepted unknown product") } catch (_: AgentFailure) { }
    }

    @Test fun `unsupported tool is rejected before any result and manual choice keeps session`() = runBlocking {
        val invalid = JSONObject(response("needs_data"))
        invalid.getJSONArray("needsData").getJSONObject(0).put("tool", "issue_items")
        val client = Client(invalid.toString())
        try { AgentRepository(client, Source(mapOf("g" to 1.0)), { 1 }).analyze("text")
            fail("Accepted write tool") } catch (_: AgentFailure) { }
        assertNull(client.sent)
        val choiceClient = Client(response("needs_user_choice"))
        val repo = AgentRepository(choiceClient, Source(mapOf("g" to 0.0)), { 1 })
        val pending = repo.analyze("text")
        assertNull(choiceClient.chosen)
        assertEquals(AgentStatus.PROPOSAL, repo.choose(pending, "g").status)
        assertEquals("g", choiceClient.chosen)
    }

    private fun oneResponse(reply: String?, status: Int = 200, pauseMs: Long = 0, test: suspend (Int) -> Unit) = runBlocking {
        ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            val worker = thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 1_000
                        socket.getInputStream().bufferedReader().readLine()
                        if (pauseMs > 0) Thread.sleep(pauseMs)
                        if (reply != null) {
                            val bytes = reply.toByteArray()
                            socket.getOutputStream().write(("HTTP/1.1 $status Test\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
                            socket.getOutputStream().write(bytes)
                        }
                    }
                }
            }
            try { test(server.localPort) } finally { worker.join(2_000) }
        }
    }

    @Test fun `HTTP adapter handles refused connection timeout error and invalid JSON`() {
        val port = ServerSocket(0).use { it.localPort }
        runBlocking { try { HttpAgentClient("http://127.0.0.1:$port", 200, 200).message("test"); fail("Connection accepted") }
            catch (e: AgentFailure) { assertTrue(e.message!!.contains("połączyć")) } }
        oneResponse("{}", 503) { p ->
            try { HttpAgentClient("http://127.0.0.1:$p").authStatus(); fail("HTTP accepted") }
            catch (e: AgentFailure) { assertTrue(e.message!!.contains("503")) }
        }
        oneResponse("broken") { p ->
            try { HttpAgentClient("http://127.0.0.1:$p").message("test"); fail("JSON accepted") }
            catch (e: AgentFailure) { assertTrue(e.message!!.contains("JSON")) }
        }
        oneResponse(null, pauseMs = 200) { p ->
            try { HttpAgentClient("http://127.0.0.1:$p", 100, 30).authStatus(); fail("Timeout accepted") }
            catch (e: AgentFailure) { assertTrue(e.message!!.contains("czas")) }
        }
        try { HttpAgentClient("http://192.168.1.2:8787"); fail("Cleartext LAN accepted") } catch (_: AgentFailure) { }
    }
}
