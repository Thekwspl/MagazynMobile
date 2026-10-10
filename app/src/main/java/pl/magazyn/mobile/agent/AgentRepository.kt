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
import pl.magazyn.mobile.domain.QuickInputMode
import pl.magazyn.mobile.domain.ParsedInputKind
import pl.magazyn.mobile.domain.RecognitionRules
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.data.ParserLearningRuleEntity

private fun csv(value: String) = value.split(',').map(String::trim).filter(String::isNotBlank)
private fun array(values: List<String>) = JSONArray(values)

object AgentCatalog {
    fun export(revision: Long, people: List<EmployeeSummary>, products: List<ProductEntity>,
               shipyards: List<ShipyardEntity>, leaders: List<ShipyardLeaderLink>, places: List<TaskPlaceView>,
               rules: List<ParserLearningRuleEntity> = emptyList()): JSONObject =
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
        ).put("recognitionRules", RecognitionRules.json(rules).apply {
            val learned = getJSONArray("learned")
            for (index in 0 until learned.length()) {
                val rule = learned.getJSONObject(index)
                val ids = when (rule.getString("type")) {
                    "PRODUCT" -> products.filter { !it.isArchived && !it.isHidden &&
                        ImportParser.key(it.name) == ImportParser.key(rule.getString("learnedName")) &&
                        (rule.isNull("learnedVariant") || ImportParser.key(it.variant.orEmpty()) == ImportParser.key(rule.getString("learnedVariant"))) }
                        .map { it.id }
                    "PERSON" -> people.filter { ImportParser.key(it.fullName) == ImportParser.key(rule.getString("learnedName")) }.map { it.id }
                    else -> emptyList()
                }
                rule.put("targetIds", JSONArray(ids))
            }
        })
}

interface AgentDataSource {
    suspend fun catalog(revision: Long): JSONObject
    suspend fun currentStock(ids: List<String>): List<Pair<String, Double>>
    suspend fun product(id: String): ProductEntity?
    suspend fun person(id: String): EmployeeSummary?
    suspend fun shipyard(id: String): ShipyardEntity?
    suspend fun taskPlace(id: String): TaskPlaceView? = throw AgentFailure("Odczyt miejsca zadania jest niedostępny.")
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
        }, database.learningRuleDao().observeAll().first())

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
    override suspend fun taskPlace(id: String) = database.taskStructureDao().getPlacesNow().firstOrNull { it.id == id && !it.isArchived }
        ?.let { TaskPlaceView(it.id, it.name, it.isArchived, "") }

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
    private var mode = QuickInputMode.ALL
    private var rawText = ""
    private var activeReply: AgentReply? = null
    suspend fun analyze(message: String, mode: QuickInputMode = QuickInputMode.ALL): AgentReply {
        this.mode = mode
        rawText = message
        activeReply = null
        val auth = client.authStatus()
        if (auth.optJSONObject("account")?.optString("type") != "chatgpt" && auth.optString("authMode") != "chatgpt")
            throw AgentFailure("Agent-service nie ma aktywnego logowania ChatGPT.")
        client.fullSync(source.catalog(nextRevision()))
        return advance(client.message(message, mode))
    }

    private fun validate(reply: AgentReply) {
        if (reply.status != AgentStatus.ERROR && mode.preferredKind != null && reply.intent != mode.preferredKind)
            throw AgentFailure("Agent zwrócił wynik niezgodny z wybranym trybem.")
        if (reply.noteText != null && reply.noteText != rawText) throw AgentFailure("Agent zmienił oryginalny tekst notatki.")
    }

    private fun requireCurrent(reply: AgentReply) {
        if (activeReply != reply) throw AgentFailure("Odpowiedź nie należy do aktualnej rundy sesji.")
    }

    suspend fun choose(reply: AgentReply, candidateId: String): AgentReply {
        requireCurrent(reply)
        if (reply.status != AgentStatus.NEEDS_USER_CHOICE || reply.candidates.none { it.id == candidateId })
            throw AgentFailure("Nieprawidłowy wybór kandydata.")
        val next = client.choice(reply.sessionId, candidateId)
        if (next.sessionId != reply.sessionId) throw AgentFailure("Agent zmienił sesję po wyborze.")
        return advance(next)
    }

    suspend fun answer(reply: AgentReply, answers: List<AgentClarificationAnswer>): AgentReply {
        requireCurrent(reply)
        if (reply.status != AgentStatus.NEEDS_USER_CHOICE || reply.clarifications.isEmpty() ||
            !areRequiredClarificationsAnswered(reply.clarifications, answers))
            throw AgentFailure("Uzupełnij wszystkie wymagane odpowiedzi.")
        val next = client.answers(reply.sessionId, answers)
        if (next.sessionId != reply.sessionId) throw AgentFailure("Agent zmienił sesję podczas wysyłania odpowiedzi.")
        return advance(next)
    }

    private suspend fun advance(first: AgentReply): AgentReply {
        var reply = first
        repeat(4) {
            validate(reply)
            if (reply.status != AgentStatus.NEEDS_DATA) { activeReply = reply; return reply }
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
        validate(reply)
        if (reply.status != AgentStatus.NEEDS_DATA) { activeReply = reply; return reply }
        throw AgentFailure("Agent przekroczył limit żądań odczytu.")
    }

    private fun recipientData(key: AgentRecipientKey) = JSONObject().put("recipientKind", key.kind).put("recipientId", key.id)

    suspend fun review(reply: AgentReply): pl.magazyn.mobile.domain.ParsedNote {
        if (reply.status != AgentStatus.PROPOSAL) throw AgentFailure("Brak propozycji do podglądu.")
        validate(reply)
        if (reply.intent != ParsedInputKind.ORDER) {
            val task = reply.task?.let { draft -> draft.copy(steps = draft.steps.map { step ->
                val place = step.placeId?.let { source.taskPlace(it) ?: throw AgentFailure("Miejsce zadania nie istnieje już lokalnie.") }
                step.copy(placeText = place?.name ?: step.placeText, people = step.people.map { person ->
                    val employee = person.employeeId?.let { source.person(it) ?: throw AgentFailure("Osoba zadania nie istnieje już lokalnie.") }
                    person.copy(displayText = employee?.fullName ?: person.displayText)
                })
            }) }
            val person = reply.contact?.let { pl.magazyn.mobile.domain.ParsedPerson(it.fullName, it.position, 1f) }
            return pl.magazyn.mobile.domain.ParsedNote(person, emptyList(), kind = reply.intent,
                phoneNumbers = reply.contact?.phoneNumbers.orEmpty(), taskDraft = task, analyzedByAi = true, agentProposal = true)
        }
        val recipient = reply.recipient ?: throw AgentFailure("Propozycja nie ma odbiorcy.")
        val person = if (recipient.kind == "person") source.person(recipient.id)
            ?: throw AgentFailure("Odbiorca nie istnieje już lokalnie.") else null
        val yard = if (recipient.kind == "shipyard") source.shipyard(recipient.id)
            ?: throw AgentFailure("Stocznia nie istnieje już lokalnie.") else null
        if (person == null && yard == null) throw AgentFailure("Nieznany rodzaj odbiorcy.")
        val items = reply.items.map { item ->
            val product = source.product(item.productId) ?: throw AgentFailure("Produkt nie istnieje już lokalnie: ${item.productId}.")
            if (product.isHidden || item.available == null || !item.available.isFinite() || !item.quantity.isFinite() || item.quantity <= 0 || item.quantity % 1 != 0.0 || item.quantity > Long.MAX_VALUE || item.unit != product.unit)
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
