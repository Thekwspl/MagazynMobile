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
                .put("name", yard.name).put("leaders", array(leaders.filter { it.shipyardId == yard.id }.map { it.employeeId }))) } },
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
    override suspend fun person(id: String) = database.employeeDao().observeSummaries().first().firstOrNull { it.id == id }
    override suspend fun shipyard(id: String) = database.shipyardDao().getAllNow().firstOrNull { it.id == id && !it.isArchived }
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
                if (request.tool != "get_current_stock") throw AgentFailure("Nieobsługiwane narzędzie agenta: ${request.tool}.")
                request.id to source.currentStock(request.productIds)
            }
            val next = client.toolResults(reply.sessionId, AgentProtocol.stockResults(requests))
            if (next.sessionId != reply.sessionId) throw AgentFailure("Agent zmienił sesję podczas odczytu stanu.")
            reply = next
        }
        throw AgentFailure("Agent przekroczył limit żądań odczytu stanu.")
    }

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
                recipientName = person?.fullName ?: yard?.name, notes = reply.warnings.joinToString("; "), productId = product.id,
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
