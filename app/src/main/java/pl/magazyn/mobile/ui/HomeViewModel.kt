package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.SeedData
import pl.magazyn.mobile.data.NotebookTaskEntity
import pl.magazyn.mobile.data.OrderNotebookEntity
import pl.magazyn.mobile.data.OrderEntity
import pl.magazyn.mobile.data.OrderLineEntity
import pl.magazyn.mobile.data.EmployeeEntity
import pl.magazyn.mobile.data.PendingImportDetail
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.data.ShipyardStockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.NegativeStockItem
import pl.magazyn.mobile.domain.NoteParser
import pl.magazyn.mobile.domain.AiCatalogItem
import pl.magazyn.mobile.domain.GeminiNoteAnalyzer
import pl.magazyn.mobile.domain.ParsedNote
import pl.magazyn.mobile.data.AiKeyStore
import pl.magazyn.mobile.data.ParserLearningRuleEntity
import pl.magazyn.mobile.domain.ParsedItem
import pl.magazyn.mobile.domain.ParsedInputKind
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.normalizeDisplayName
import pl.magazyn.mobile.domain.normalizePersonName
import pl.magazyn.mobile.domain.normalizeFirstName
import pl.magazyn.mobile.domain.normalizeFullPersonName
import pl.magazyn.mobile.domain.normalizePhoneNumbers

data class HomeUiState(
    val employeeCount: Int = 0,
    val productCount: Int = 0,
    val negativeStockCount: Int = 0,
    val openOrderCount: Int = 0,
    val pendingImportCount: Int = 0,
)

data class AiAnalysisUiState(
    val isLoading: Boolean = false,
    val result: ParsedNote? = null,
    val error: String? = null,
)

data class NoteReviewUiState(
    val rawText: String,
    val note: ParsedNote,
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    private val parser = NoteParser()
    private val aiKeyStore = AiKeyStore(application)
    private val aiAnalyzer = GeminiNoteAnalyzer()
    private val _aiAnalysis = MutableStateFlow(AiAnalysisUiState())
    val aiAnalysis: StateFlow<AiAnalysisUiState> = _aiAnalysis.asStateFlow()
    private val _quickInput = MutableStateFlow("")
    val quickInput: StateFlow<String> = _quickInput.asStateFlow()
    private val _noteReview = MutableStateFlow<NoteReviewUiState?>(null)
    val noteReview: StateFlow<NoteReviewUiState?> = _noteReview.asStateFlow()
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val tasks = database.notebookDao().observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val negativeItems = database.stockDao().observeNegativeItems()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val pendingImportDetails = database.importDao().observePendingDetails()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val products = database.productDao().observeWithStock("warehouse-main")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyardLeaderLinks = database.shipyardDao().observeAllLeaderLinks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val learningRules = database.learningRuleDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val uiState = combine(
        database.employeeDao().observeActiveCount(),
        database.productDao().observeActiveCount(),
        database.stockDao().observeNegativeCount(),
        database.orderDao().observeOpenOrders(),
        database.importDao().observePendingCount(),
    ) { employees, products, negative, orders, pendingImports ->
        HomeUiState(
            employeeCount = employees,
            productCount = products,
            negativeStockCount = negative,
            openOrderCount = orders.size,
            pendingImportCount = pendingImports,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init {
        viewModelScope.launch { SeedData(database).ensureCreated() }
    }

    fun updateQuickInput(value: String) {
        _quickInput.value = value
    }

    fun openReview(note: ParsedNote) {
        _noteReview.value = NoteReviewUiState(_quickInput.value, note)
    }

    fun openManualOrder() {
        _noteReview.value = NoteReviewUiState(
            rawText = "",
            note = ParsedNote(person = null, items = emptyList(), kind = ParsedInputKind.ORDER),
        )
    }

    fun closeReview(completed: Boolean = false) {
        _noteReview.value = null
        if (completed) _quickInput.value = ""
    }

    fun recognize(text: String): ParsedNote {
        val parsed = parser.parse(text)
        val activeRules = learningRules.value.filter { it.isEnabled }
        val learnedItems = parsed.items.map { item ->
            item.copy(recipientName = item.recipientName?.let(::normalizeFullPersonName))
        }.map { item ->
            activeRules.firstOrNull { it.ruleType == "PRODUCT" && it.triggerKey == ImportParser.key(item.name) }?.let { rule ->
                item.copy(name = rule.learnedName, variant = rule.learnedVariant ?: item.variant, unit = rule.learnedUnit)
            } ?: item
        }.map { item ->
            val personRule = item.recipientName?.let { name -> activeRules.firstOrNull { it.ruleType == "PERSON" && it.triggerKey == ImportParser.key(name) } }
            if (personRule == null) item else item.copy(recipientName = personRule.learnedName)
        }
        val expandedItems = learnedItems.flatMap { item ->
            val itemKey = ImportParser.key(item.name)
            val bundleMatches = products.value.filter { product ->
                product.aliases.split(',').map { ImportParser.key(it) }.any { it.isNotBlank() && it == itemKey }
            }
            if (bundleMatches.size < 2) listOf(item) else bundleMatches.map { product ->
                item.copy(name = product.name, variant = product.variant ?: item.variant, unit = product.unit)
            }
        }
        val learnedPeople = parsed.people.map { person ->
            val personRule = activeRules.firstOrNull { it.ruleType == "PERSON" && it.triggerKey == ImportParser.key(person.fullName) }
            val positionRule = person.position?.let { position -> activeRules.firstOrNull { it.ruleType == "POSITION" && it.triggerKey == ImportParser.key(position) } }
            person.copy(fullName = normalizeFullPersonName(personRule?.learnedName ?: person.fullName), position = positionRule?.learnedName ?: person.position)
        }
        val patternKind = activeRules.firstOrNull { it.ruleType == "PATTERN" && text.contains(it.sourceLabel, ignoreCase = true) }
            ?.learnedName?.uppercase()?.let { runCatching { ParsedInputKind.valueOf(it) }.getOrNull() }
        val textKey = ImportParser.key(text)
        val words = text.split(Regex("\\s+")).map { ImportParser.key(it) }.filter { it.length >= 4 }
        val detectedByName = shipyards.value.filter { yard ->
            val yardKey = ImportParser.key(yard.name)
            textKey.contains(yardKey) || words.any { word -> yardKey.startsWith(word) || word.startsWith(yardKey) }
        }.maxByOrNull { it.name.length }
        val detectedByLeader = people.value.firstOrNull { person ->
            val labels = listOf(person.fullName) + person.aliases.split(',')
            labels.map { ImportParser.key(it) }.filter { it.length >= 4 }.any { label -> textKey.contains(label) }
        }?.let { person ->
            val shipyardId = shipyardLeaderLinks.value.firstOrNull { it.employeeId == person.id }?.shipyardId
            shipyards.value.firstOrNull { it.id == shipyardId }
        }
        val detectedShipyard = detectedByName?.name ?: detectedByLeader?.name
        return parsed.copy(person = learnedPeople.firstOrNull() ?: parsed.person, people = learnedPeople, items = expandedItems, kind = patternKind ?: parsed.kind, shipyardName = detectedShipyard)
    }

    fun analyzeWithAi(text: String) {
        val apiKey = aiKeyStore.readApiKey()
        if (apiKey.isNullOrBlank()) {
            _aiAnalysis.value = AiAnalysisUiState(error = "Najpierw zapisz klucz w Ustawieniach AI (przycisk +).")
            return
        }
        if (text.isBlank()) return
        viewModelScope.launch {
            _aiAnalysis.value = AiAnalysisUiState(isLoading = true)
            runCatching {
                aiAnalyzer.analyze(
                    apiKey = apiKey,
                    rawText = text,
                    catalog = products.value.map {
                        AiCatalogItem(it.name, it.variant, it.unit, it.aliases, it.tags)
                    },
                    shipyards = shipyards.value.map { yard ->
                        val leaderIds = shipyardLeaderLinks.value.filter { it.shipyardId == yard.id }.map { it.employeeId }.toSet()
                        val leaders = people.value.filter { it.id in leaderIds }.joinToString(", ") { person ->
                            listOf(person.fullName, person.aliases).filter(String::isNotBlank).joinToString(" / ")
                        }
                        if (leaders.isBlank()) yard.name else "${yard.name} | prowadzący: $leaders"
                    },
                    redactPhoneNumbers = aiKeyStore.redactPhoneNumbers,
                )
            }.onSuccess {
                aiKeyStore.recordConnection(true, aiAnalyzer.lastModel, "Analiza zakończona powodzeniem")
                _aiAnalysis.value = AiAnalysisUiState(result = it)
            }.onFailure {
                aiKeyStore.recordConnection(false, aiAnalyzer.lastModel, it.message ?: "Nieznany błąd")
                _aiAnalysis.value = AiAnalysisUiState(error = it.message ?: "Nie udało się przeanalizować notatki.")
            }
        }
    }

    fun consumeAiResult() {
        _aiAnalysis.value = AiAnalysisUiState()
    }

    fun addPhoneNumber(employeeId: String, number: String) {
        viewModelScope.launch {
            val employee = database.employeeDao().findById(employeeId) ?: return@launch
            val combined = listOf(employee.phoneNumbers, number).filter(String::isNotBlank).joinToString(", ")
            database.employeeDao().update(employee.copy(phoneNumbers = normalizePhoneNumbers(combined)))
        }
    }

    fun addProductTags(productId: String, tags: String) {
        viewModelScope.launch {
            val product = database.productDao().findById(productId) ?: return@launch
            val combined = listOf(product.tags, tags).filter(String::isNotBlank).joinToString(", ")
            database.productDao().update(product.copy(tags = pl.magazyn.mobile.domain.normalizeCommaSeparated(combined)))
        }
    }

    fun saveTasks(rawText: String, tasks: List<String>) {
        val cleanTasks = tasks.map(String::trim).filter(String::isNotBlank)
        if (cleanTasks.isEmpty()) return
        viewModelScope.launch {
            val notebookId = java.util.UUID.randomUUID().toString()
            database.notebookDao().insertNotebook(
                OrderNotebookEntity(
                    id = notebookId,
                    rawText = rawText,
                    status = "ACTIVE",
                    detectedType = "TASK",
                    createdAtEpochMillis = System.currentTimeMillis(),
                ),
            )
            database.notebookDao().insertTasks(
                cleanTasks.mapIndexed { index, task ->
                    NotebookTaskEntity(java.util.UUID.randomUUID().toString(), notebookId, task, false, index)
                },
            )
        }
    }

    fun saveDraftOrder(rawText: String, note: ParsedNote, approvedPairs: List<Pair<ParsedItem, ParsedItem>>, rememberCorrections: Boolean) {
        if (approvedPairs.isEmpty()) return
        viewModelScope.launch {
            database.withTransaction {
                if (rememberCorrections) approvedPairs.forEach { (source, corrected) ->
                    val triggerKey = ImportParser.key(source.name)
                    if (triggerKey.isNotBlank()) {
                        val existingRule = database.learningRuleDao().findByTrigger(triggerKey, "PRODUCT")
                        database.learningRuleDao().upsert(
                            ParserLearningRuleEntity(
                                id = existingRule?.id ?: UUID.randomUUID().toString(),
                                triggerKey = triggerKey,
                                sourceLabel = source.name,
                                learnedName = corrected.name,
                                learnedVariant = corrected.variant,
                                learnedUnit = corrected.unit,
                                confirmations = (existingRule?.confirmations ?: 0) + 1,
                                updatedAtEpochMillis = System.currentTimeMillis(),
                                ruleType = "PRODUCT",
                            ),
                        )
                    }
                    val sourcePerson = source.recipientName
                    val correctedPerson = corrected.recipientName
                    if (!sourcePerson.isNullOrBlank() && !correctedPerson.isNullOrBlank() && !sourcePerson.equals(correctedPerson, true)) {
                        val personKey = ImportParser.key(sourcePerson)
                        val existing = database.learningRuleDao().findByTrigger(personKey, "PERSON")
                        database.learningRuleDao().upsert(
                            ParserLearningRuleEntity(
                                id = existing?.id ?: UUID.randomUUID().toString(), triggerKey = personKey,
                                sourceLabel = sourcePerson, learnedName = correctedPerson, learnedVariant = null,
                                learnedUnit = "", confirmations = (existing?.confirmations ?: 0) + 1,
                                updatedAtEpochMillis = System.currentTimeMillis(), ruleType = "PERSON",
                            ),
                        )
                    }
                }
                val approvedItems = approvedPairs.map { it.second }
                val now = System.currentTimeMillis()
                val notebookId = UUID.randomUUID().toString()
                database.notebookDao().insertNotebook(
                    OrderNotebookEntity(
                        id = notebookId,
                        rawText = rawText,
                        status = "VERIFIED",
                        detectedType = "ORDER",
                        createdAtEpochMillis = now,
                    ),
                )
                val employees = database.employeeDao().getAllNow()
                val catalog = database.productDao().getAllNow()
                val activeShipyards = database.shipyardDao().getAllNow().filterNot { it.isArchived }
                approvedItems.groupBy { it.recipientName ?: note.shipyardName ?: "Bez odbiorcy" }
                    .forEach { (recipient, items) ->
                        val recipientKey = ImportParser.key(recipient)
                        val employee = employees.firstOrNull {
                            ImportParser.key(it.fullName) == recipientKey || it.aliases.split(',').any { alias -> ImportParser.key(alias) == recipientKey }
                        }
                        val recipientShipyard = activeShipyards.firstOrNull { ImportParser.key(it.name) == recipientKey }
                        val orderId = UUID.randomUUID().toString()
                        database.orderDao().upsertOrders(
                            listOf(
                                OrderEntity(
                                    id = orderId,
                                    notebookId = notebookId,
                                    employeeId = employee?.id,
                                    recipientLabel = recipient,
                                    siteLabel = recipientShipyard?.name ?: if (employee != null) note.shipyardName else null,
                                    status = "DRAFT",
                                    plannedIssueDate = note.suggestedIssueDate ?: java.time.LocalDate.now().toString(),
                                    createdAtEpochMillis = now,
                                ),
                            ),
                        )
                        database.orderDao().upsertLines(
                            items.map { item ->
                                val itemKey = ImportParser.key(item.name)
                                val product = catalog.firstOrNull {
                                    val names = listOf(it.name) + it.aliases.split(',') + it.tags.split(',')
                                    names.any { name -> ImportParser.key(name) == itemKey } &&
                                        (item.variant.isNullOrBlank() || it.variant.equals(item.variant, true))
                                }
                                OrderLineEntity(
                                    id = UUID.randomUUID().toString(),
                                    orderId = orderId,
                                    productId = product?.id,
                                    rawText = listOfNotNull(item.name, item.variant, item.notes.takeIf(String::isNotBlank)).joinToString(" · "),
                                    quantity = item.quantity.toDouble(),
                                    unit = item.unit,
                                    verificationStatus = if (product == null) "NEEDS_MAPPING" else "VERIFIED",
                                )
                            },
                        )
                    }
            }
        }
    }

    fun setTaskCompleted(id: String, completed: Boolean) {
        viewModelScope.launch { database.notebookDao().setTaskCompleted(id, completed) }
    }

    fun correctNegativeStock(item: NegativeStockItem, actualQuantity: Long) {
        viewModelScope.launch {
            database.withTransaction {
                val current = database.stockDao().find(item.warehouseId, item.productId)?.quantity ?: item.quantity
                val movementId = UUID.randomUUID().toString()
                database.stockDao().upsert(listOf(StockBalanceEntity(item.warehouseId, item.productId, actualQuantity.toDouble(), true)))
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = "INVENTORY_CORRECTION",
                        warehouseId = item.warehouseId,
                        employeeId = null,
                        effectiveDate = java.time.LocalDate.now().toString(),
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = "Naprawa problemu z ekranu Wymaga uwagi",
                    ),
                )
                database.movementDao().insertLine(
                    StockMovementLineEntity(UUID.randomUUID().toString(), movementId, item.productId, actualQuantity - current, item.unit),
                )
            }
        }
    }

    fun resolvePendingImports(items: List<PendingImportDetail>, productId: String?, newProductName: String, unit: String) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            database.withTransaction {
                val sourceAliases = items.map { it.rawProductName.trim() }.filter(String::isNotBlank).distinct()
                val existingProduct = productId?.let { database.productDao().findById(it) }
                val product = existingProduct?.let { existing ->
                    val aliases = (existing.aliases.split(",") + sourceAliases)
                        .map(String::trim).filter(String::isNotBlank)
                        .distinctBy(ImportParser::key).joinToString(", ")
                    existing.copy(aliases = aliases).also { database.productDao().update(it) }
                } ?: ProductEntity(
                    id = UUID.randomUUID().toString(),
                    name = normalizeDisplayName(newProductName).ifBlank { normalizeDisplayName(items.first().rawProductName) },
                    unit = unit.ifBlank { "szt." },
                    category = "Pozostałe",
                    aliases = sourceAliases.joinToString(", "),
                ).also { database.productDao().insert(it) }

                items.forEach { item ->
                    if (!database.importDao().isSourceRowPending(item.sourceKey)) return@forEach
                    when (item.kind) {
                    "PEOPLE" -> {
                        val firstName = normalizeFirstName(item.recipientFirstName)
                        val lastName = normalizePersonName(item.recipientLastName)
                        val employeeKey = ImportParser.key("$firstName $lastName")
                        val employee = database.employeeDao().getAllNow().firstOrNull {
                            ImportParser.key("${it.firstName} ${it.lastName}") == employeeKey
                        } ?: EmployeeEntity(
                            id = UUID.randomUUID().toString(),
                            fullName = "$firstName $lastName".trim(),
                            firstName = firstName,
                            lastName = lastName,
                        ).also { database.employeeDao().insert(it) }
                        insertResolvedMovement("HISTORICAL_ISSUE_IMPORT", employee.id, "", item, product)
                    }
                    "SHIPYARDS" -> {
                        val shipyardName = normalizeDisplayName(item.recipientLabel)
                        require(shipyardName.isNotBlank()) { "W tym wpisie brakuje nazwy stoczni" }
                        val shipyardKey = ImportParser.key(shipyardName)
                        val existing = database.shipyardDao().getAllNow().firstOrNull { ImportParser.key(it.name) == shipyardKey }
                        val shipyard = existing?.copy(isArchived = false) ?: ShipyardEntity(UUID.randomUUID().toString(), shipyardName)
                        if (existing == null) database.shipyardDao().insert(shipyard) else database.shipyardDao().restoreById(shipyard.id)
                        insertResolvedMovement("HISTORICAL_SHIPYARD_IMPORT", null, shipyard.name, item, product)
                        val current = database.shipyardDao().findStock(shipyard.id, product.id)?.quantity ?: 0.0
                        database.shipyardDao().upsertStock(ShipyardStockBalanceEntity(shipyard.id, product.id, current + item.quantity))
                    }
                    else -> error("Nieobsługiwany rodzaj importu: ${item.kind}")
                    }
                    database.importDao().deletePendingRow(item.sourceKey)
                }
            }
        }
    }

    private suspend fun insertResolvedMovement(
        type: String,
        employeeId: String?,
        recipientLabel: String,
        item: PendingImportDetail,
        product: ProductEntity,
    ) {
        val movementId = UUID.randomUUID().toString()
        database.movementDao().insertMovement(
            StockMovementEntity(
                id = movementId,
                type = type,
                warehouseId = "warehouse-main",
                employeeId = employeeId,
                recipientLabel = recipientLabel,
                effectiveDate = item.effectiveDate,
                createdAtEpochMillis = System.currentTimeMillis(),
                note = "Uzupełniono mapowanie importu: ${item.sourceFileName} (bez zmiany magazynu głównego)",
            ),
        )
        database.movementDao().insertLine(
            StockMovementLineEntity(UUID.randomUUID().toString(), movementId, product.id, -item.quantity.toDouble(), product.unit),
        )
    }
}
