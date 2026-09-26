package pl.magazyn.mobile.agent

import android.content.Context
import kotlinx.coroutines.flow.first
import org.json.JSONArray
import org.json.JSONObject
import pl.magazyn.mobile.data.AppDatabase
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.ShipyardLeaderLink
import pl.magazyn.mobile.data.TaskPlaceView
import pl.magazyn.mobile.data.ShipyardResolver

private fun csv(value: String) = value.split(',').map(String::trim).filter(String::isNotBlank)
private fun array(values: List<String>) = JSONArray(values)

object AgentCatalog {
    fun export(revision: Long, people: List<EmployeeSummary>, products: List<ProductEntity>,
               shipyards: List<ShipyardEntity>, leaders: List<ShipyardLeaderLink>, places: List<TaskPlaceView>): JSONObject =
        AgentProtocol.catalog(revision,
            JSONArray().apply { people.forEach { put(JSONObject().put("id", it.id).put("firstName", it.firstName)
                .put("lastName", it.lastName).put("aliases", array(csv(it.aliases))).put("positions", array(csv(it.positions)))) } },
            JSONArray().apply { products.filterNot { it.isArchived }.forEach { put(JSONObject().put("id", it.id)
                .put("name", it.name).put("variant", it.variant ?: "").put("unit", it.unit)
                .put("aliases", array(csv(it.aliases))).put("tags", array(csv(it.tags))).put("hidden", it.isHidden)) } },
            JSONArray().apply { shipyards.filterNot { it.isArchived }.forEach { yard -> put(JSONObject().put("id", yard.id)
                .put("name", yard.name).put("aliases", array(csv(yard.aliases))).put("tags", array(csv(yard.tags)))
                .put("leaders", array(leaders.filter { it.shipyardId == yard.id }.map { it.employeeId }))) } },
            JSONArray().apply { places.filterNot { it.isArchived }.forEach { put(JSONObject().put("id", it.id)
                .put("name", it.name).put("aliases", array(csv(it.aliases)))) } },
        )
}

interface AgentDataSource {
    suspend fun catalog(revision: Long): JSONObject
    suspend fun currentStock(ids: List<String>): List<Pair<String, Double>>
    suspend fun product(id: String): ProductEntity?
    suspend fun person(id: String): EmployeeSummary?
    suspend fun shipyard(id: String): ShipyardEntity?
    suspend fun personItems(id: String): List<AgentPossession> = throw AgentFailure("Odczyt rzeczy osoby jest niedostępny.")
    suspend fun shipyardStock(id: String): List<AgentYardBalance> = throw AgentFailure("Odczyt stoczni jest niedostępny.")
    suspend fun activeOrders(key: AgentRecipientKey): List<AgentOrderRecord> = throw AgentFailure("Odczyt zamówień jest niedostępny.")
    suspend fun recentIssues(key: AgentRecipientKey, limit: Int): List<AgentIssueRecord> = throw AgentFailure("Odczyt wydań jest niedostępny.")
}

class RoomAgentDataSource(private val database: AppDatabase) : AgentDataSource {
    override suspend fun catalog(revision: Long) = AgentCatalog.export(revision,
        database.employeeDao().observeSummaries().first(), database.productDao().getAllNow(),
        database.shipyardDao().getAllNow(), database.shipyardDao().observeAllLeaderLinks().first(),
        database.taskStructureDao().getPlacesNow().let { places ->
            val aliases = database.taskStructureDao().getAliasesNow()
            places.map { place -> TaskPlaceView(place.id, place.name, place.isArchived,
                aliases.filter { it.placeId == place.id }.joinToString(",") { it.alias }) }
        })

    override suspend fun currentStock(ids: List<String>): List<Pair<String, Double>> {
        if (ids.isEmpty() || ids.any(String::isBlank)) throw AgentFailure("Agent nie wskazał poprawnych ID produktów.")
        val products = database.productDao().findByIds(ids).filterNot { it.isArchived }.map { it.id }.toSet()
        return ids.distinct().map { id ->
            if (id !in products) throw AgentFailure("Nieznany produkt agenta: $id.")
            val balance = database.stockDao().find("warehouse-main", id)
            if (balance == null || !balance.isKnown) throw AgentFailure("Brak znanego stanu magazynowego dla produktu $id.")
            id to balance.quantity
        }
    }
    override suspend fun product(id: String) = database.productDao().findById(id)?.takeUnless { it.isArchived }
    override suspend fun person(id: String) = database.employeeDao().findById(id)?.takeUnless { it.isArchived }?.let {
        EmployeeSummary(it.id, it.fullName, it.firstName, it.lastName, "", "", "", "")
    }
    override suspend fun shipyard(id: String) = database.shipyardDao().findActive(id)

    override suspend fun personItems(id: String): List<AgentPossession> {
        if (person(id) == null) throw AgentFailure("Nieznana aktywna osoba: $id.")
        return database.movementDao().agentCustodies(id).map { AgentPossession(it.productId, it.quantity, it.unit, it.issuedDate) }
    }
    override suspend fun shipyardStock(id: String): List<AgentYardBalance> {
        if (shipyard(id) == null) throw AgentFailure("Nieznana aktywna stocznia: $id.")
        return database.shipyardDao().agentStock(id).map { AgentYardBalance(it.productId, it.quantity, it.unit) }
    }
    private suspend fun validate(key: AgentRecipientKey) {
        if (key.kind == "person") {
            if (person(key.id) == null) throw AgentFailure("Nieznana aktywna osoba: ${key.id}.")
        } else if (key.kind == "shipyard") {
            if (shipyard(key.id) == null) throw AgentFailure("Nieznana aktywna stocznia: ${key.id}.")
        } else throw AgentFailure("Niepoprawny rodzaj odbiorcy.")
    }
    private suspend fun legacyLabels(id: String): List<String> {
        val yards = database.shipyardDao().getAllNow().filterNot { it.isArchived }
        val yard = yards.first { it.id == id }
        return (listOf(yard.name) + csv(yard.aliases) + csv(yard.tags)).distinct().filter { label ->
            ShipyardResolver.resolve(null, label, yards).confirmedId == id
        }
    }
    override suspend fun activeOrders(key: AgentRecipientKey): List<AgentOrderRecord> {
        validate(key)
        val dao = database.orderDao()
        val orders = if (key.kind == "person") dao.agentPersonOrders(key.id) else {
            val labels = legacyLabels(key.id)
            (dao.agentShipyardOrders(key.id) + if (labels.isEmpty()) emptyList() else dao.agentLegacyShipyardOrders(labels))
                .filter { it.shipyardId == key.id || it.shipyardId == null &&
                    it.siteLabel == it.recipientLabel && it.recipientLabel in labels }
        }
        return orders.groupBy { it.notebookId ?: it.id }.values.take(20).map { sections ->
            val first = sections.first()
            AgentOrderRecord(first.notebookId ?: first.id, first.status, first.plannedIssueDate,
                sections.flatMap { dao.agentOrderLines(it.id) }.take(30).map { AgentOrderItem(it.productId, it.quantity, it.unit) })
        }
    }
    override suspend fun recentIssues(key: AgentRecipientKey, limit: Int): List<AgentIssueRecord> {
        validate(key)
        if (limit !in 1..20) throw AgentFailure("Limit wydań musi być w zakresie 1–20.")
        val dao = database.movementDao()
        val issues = if (key.kind == "person") dao.agentPersonIssues(key.id, limit) else {
            val labels = legacyLabels(key.id)
            (dao.agentShipyardIssues(key.id, limit) + if (labels.isEmpty()) emptyList() else dao.agentLegacyShipyardIssues(labels, limit))
                .sortedWith(compareByDescending<pl.magazyn.mobile.data.AgentIssue> { it.issuedDate }.thenByDescending { it.movementId }).take(limit)
        }
        return issues.map { AgentIssueRecord(it.movementId, it.lineId, it.productId, it.quantity, it.unit, it.issuedDate) }
    }
}

class AgentRepository(private val client: AgentClient, private val source: AgentDataSource, private val nextRevision: () -> Long) {
    suspend fun analyze(message: String): AgentReply {
        val auth = client.authStatus()
        if (auth.optJSONObject("account")?.optString("type") != "chatgpt" && auth.optString("authMode") != "chatgpt")
            throw AgentFailure("Agent-service nie ma aktywnego logowania ChatGPT.")
        client.fullSync(source.catalog(nextRevision()))
        return advance(client.message(message))
    }

    suspend fun choose(reply: AgentReply, candidateId: String): AgentReply {
        if (reply.status != AgentStatus.NEEDS_USER_CHOICE || reply.candidates.none { it.id == candidateId })
            throw AgentFailure("Nieprawidłowy wybór kandydata.")
        return advance(client.choice(reply.sessionId, candidateId))
    }

    private suspend fun advance(first: AgentReply): AgentReply {
        var reply = first
        repeat(4) {
            if (reply.status != AgentStatus.NEEDS_DATA) return reply
            val requests = reply.needsData.map { request ->
                val data: JSONObject
                val tool: String
                when (request) {
                    is AgentToolRequest.Stock -> {
                        tool = "get_current_stock"
                        data = JSONObject().put("stocks", JSONArray().apply { source.currentStock(request.productIds).forEach { (id, value) ->
                            put(JSONObject().put("productId", id).put("available", value))
                        } })
                    }
                    is AgentToolRequest.PersonItems -> {
                        tool = "get_person_current_items"
                        data = JSONObject().put("personId", request.personId).put("items", JSONArray().apply {
                            source.personItems(request.personId).forEach { put(JSONObject().put("productId", it.productId)
                                .put("quantity", it.quantity).put("unit", it.unit).put("issuedDate", it.issuedDate)) }
                        })
                    }
                    is AgentToolRequest.ShipyardStock -> {
                        tool = "get_shipyard_stock"
                        data = JSONObject().put("shipyardId", request.shipyardId).put("stocks", JSONArray().apply {
                            source.shipyardStock(request.shipyardId).forEach { put(JSONObject().put("productId", it.productId)
                                .put("quantity", it.quantity).put("unit", it.unit)) }
                        })
                    }
                    is AgentToolRequest.ActiveOrders -> {
                        tool = "get_active_orders"
                        data = recipientData(request.recipient).put("orders", JSONArray().apply {
                            source.activeOrders(request.recipient).forEach { order -> put(JSONObject()
                                .put("orderId", order.orderId).put("status", order.status).put("plannedIssueDate", order.plannedIssueDate)
                                .put("items", JSONArray().apply { order.items.forEach { put(JSONObject().put("productId", it.productId)
                                    .put("quantity", it.quantity).put("unit", it.unit)) } })) }
                        })
                    }
                    is AgentToolRequest.RecentIssues -> {
                        tool = "get_recent_issues"
                        data = recipientData(request.recipient).put("issues", JSONArray().apply {
                            source.recentIssues(request.recipient, request.limit).forEach { put(JSONObject()
                                .put("movementId", it.movementId).put("lineId", it.lineId).put("productId", it.productId)
                                .put("quantity", it.quantity).put("unit", it.unit).put("issuedDate", it.issuedDate)) }
                        })
                    }
                }
                AgentProtocol.result(request.id, tool, data)
            }
            val next = client.toolResults(reply.sessionId, AgentProtocol.toolResults(requests))
            if (next.sessionId != reply.sessionId) throw AgentFailure("Agent zmienił sesję podczas odczytu stanu.")
            reply = next
        }
        throw AgentFailure("Agent przekroczył limit żądań odczytu.")
    }

    private fun recipientData(key: AgentRecipientKey) = JSONObject().put("recipientKind", key.kind).put("recipientId", key.id)

    suspend fun review(reply: AgentReply): pl.magazyn.mobile.domain.ParsedNote {
        if (reply.status != AgentStatus.PROPOSAL) throw AgentFailure("Brak propozycji do podglądu.")
        val recipient = reply.recipient ?: throw AgentFailure("Propozycja nie ma odbiorcy.")
        val person = if (recipient.kind == "person") source.person(recipient.id)
            ?: throw AgentFailure("Odbiorca nie istnieje już lokalnie.") else null
        val yard = if (recipient.kind == "shipyard") source.shipyard(recipient.id)
            ?: throw AgentFailure("Stocznia nie istnieje już lokalnie.") else null
        if (person == null && yard == null) throw AgentFailure("Nieznany rodzaj odbiorcy.")
        val items = reply.items.map { item ->
            val product = source.product(item.productId) ?: throw AgentFailure("Produkt nie istnieje już lokalnie: ${item.productId}.")
            if (item.available == null || !item.available.isFinite() || !item.quantity.isFinite() || item.quantity <= 0 || item.quantity % 1 != 0.0 || item.quantity > Long.MAX_VALUE || item.unit != product.unit)
                throw AgentFailure("Propozycja zawiera nieobsługiwaną ilość lub jednostkę produktu ${item.productId}.")
            pl.magazyn.mobile.domain.ParsedItem(product.name, product.variant, item.quantity.toLong(), product.unit, 1f,
                recipientName = person?.fullName ?: yard?.name,
                notes = (listOf("Dostępne: ${item.available} ${product.unit}") + reply.warnings).joinToString("; "), productId = product.id,
                recipientId = recipient.id, recipientKind = recipient.kind)
        }
        val parsedPerson = person?.let { pl.magazyn.mobile.domain.ParsedPerson(it.fullName, null, 1f) }
        return pl.magazyn.mobile.domain.ParsedNote(parsedPerson, items, kind = pl.magazyn.mobile.domain.ParsedInputKind.ORDER,
            analyzedByAi = true, shipyardName = yard?.name, suggestedIssueDate = reply.deliveryDate, agentProposal = true)
    }
}

class AgentRevision(context: Context) {
    private val prefs = context.getSharedPreferences("agent_catalog_revision", Context.MODE_PRIVATE)
    fun next(): Long {
        val next = maxOf(System.currentTimeMillis(), prefs.getLong("last", 0) + 1)
        if (!prefs.edit().putLong("last", next).commit()) throw AgentFailure("Nie udało się zachować wersji katalogu.")
        return next
    }
}
