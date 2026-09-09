package pl.magazyn.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.activity.compose.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.domain.ParsedNote
import pl.magazyn.mobile.domain.ParsedInputKind

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    contentPadding: PaddingValues,
    onOrders: () -> Unit = {},
    onTasks: () -> Unit = {},
    onReview: () -> Unit = {},
    onQuickIssue: () -> Unit = {},
    onPerson: (String) -> Unit = {},
    onProduct: (String) -> Unit = {},
    onDuplicates: () -> Unit = {},
    viewModel: HomeViewModel = viewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val tasks by viewModel.tasks.collectAsStateWithLifecycle()
    val negativeItems by viewModel.negativeItems.collectAsStateWithLifecycle()
    val pendingImportDetails by viewModel.pendingImportDetails.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val aiAnalysis by viewModel.aiAnalysis.collectAsStateWithLifecycle()
    val duplicateDecisions by viewModel.duplicateDecisions.collectAsStateWithLifecycle()
    val query by viewModel.quickInput.collectAsStateWithLifecycle()
    var attentionDetails by remember { mutableStateOf<AttentionDetails?>(null) }
    var showNotifications by remember { mutableStateOf(false) }
    val duplicateCandidates = remember(products, duplicateDecisions) {
        val ignoredSignatures = duplicateDecisions.filter { it.decision in setOf("KEEP_SEPARATE", "SKIP") }.map { it.signature }.toSet()
        val productDuplicates = products.groupBy { pl.magazyn.mobile.domain.productDuplicateKey(it.name, it.variant).signature }
            .filterKeys { !it.startsWith("|") && it !in ignoredSignatures }.values.filter { group -> group.size > 1 && group.indices.any { left -> ((left + 1) until group.size).any { right ->
                val a = group[left]; val b = group[right]
                pl.magazyn.mobile.domain.isPotentialProductDuplicate(a.name, a.variant, a.groupName, a.subgroupName, a.category, b.name, b.variant, b.groupName, b.subgroupName, b.category)
            } } }.flatMap { group ->
                group.map { DuplicateCandidate("PRODUCT", it.id, it.name + it.variant?.let { value -> " · $value" }.orEmpty(), "Możliwy duplikat przedmiotu · ${group.size} podobne rekordy") }
            }
        productDuplicates
    }

    LaunchedEffect(aiAnalysis.result) {
        aiAnalysis.result?.let {
            viewModel.openReview(it)
            viewModel.consumeAiResult()
            onReview()
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(contentPadding),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item { WarehouseHeader(onNotifications = { showNotifications = true }) }
        item {
            Column(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                    SmartInput(
                        value = query,
                        onChange = viewModel::updateQuickInput,
                        aiLoading = aiAnalysis.isLoading,
                        aiError = aiAnalysis.error,
                        onRecognizeLocal = {
                            viewModel.openReview(viewModel.recognize(query))
                            onReview()
                        },
                        onRecognizeAi = { viewModel.analyzeWithAi(query) },
                    )
                    QuickButtons(
                        onNewOrder = {
                            viewModel.openManualOrder()
                            onReview()
                        },
                        onQuickIssue = onQuickIssue,
                    )
                    SectionHeader("Do zrobienia", "Wszystkie ${uiState.openOrderCount + tasks.count { !it.isCompleted }}", onTasks)
                    tasks.take(6).forEach { task ->
                        Row(Modifier.fillMaxWidth().clickable(onClick = onTasks), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(task.isCompleted, { viewModel.setTaskCompleted(task.id, it) })
                            Column(Modifier.weight(1f)) {
                                Text(task.text, color = if (task.isCompleted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                task.dueDate?.let { Text(formatDisplayDate(it), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }
                            }
                        }
                    }
                    if (uiState.openOrderCount > 0) ActiveOrderCard(uiState.openOrderCount, onOrders)
                    if (uiState.openOrderCount == 0 && tasks.isEmpty()) EmptyOrdersCard()
                    if (uiState.negativeStockCount > 0 || uiState.pendingImportCount > 0 || duplicateCandidates.isNotEmpty()) {
                        SectionHeader("Wymaga uwagi", "Szczegóły")
                        if (uiState.negativeStockCount > 0) AttentionRow(
                            Icons.Default.Warning,
                            "${uiState.negativeStockCount} produkty mają stan ujemny",
                            true,
                        ) { attentionDetails = AttentionDetails.NEGATIVE_STOCK }
                        if (uiState.pendingImportCount > 0) {
                            AttentionRow(
                                Icons.Default.Rule,
                                "${uiState.pendingImportCount} pozycji importu wymaga mapowania",
                                true,
                            ) { attentionDetails = AttentionDetails.PENDING_IMPORT }
                        }
                        if (duplicateCandidates.isNotEmpty()) AttentionRow(
                            Icons.Default.ContentCopy,
                            "${duplicateCandidates.size} rekordów może być duplikatami",
                            true,
                        ) { onDuplicates() }
                    } else {
                        SectionHeader("Wymaga uwagi", "")
                        Text("Nic nie wymaga uwagi", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        "${uiState.productCount} przedmioty · ${uiState.employeeCount} osoby",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
            }
        }
    }
    attentionDetails?.let { kind ->
        ModalBottomSheet(onDismissRequest = { attentionDetails = null }) {
            AttentionDetailsSheet(
                kind = kind,
                negativeItems = negativeItems,
                pendingItems = pendingImportDetails,
                products = products,
                duplicates = duplicateCandidates,
                onResolvePending = viewModel::resolvePendingImports,
                onCorrectNegative = viewModel::correctNegativeStock,
                onOpenDuplicate = { candidate ->
                    attentionDetails = null
                    if (candidate.kind == "PERSON") onPerson(candidate.id) else onProduct(candidate.id)
                },
                onClose = { attentionDetails = null },
            )
        }
    }
    if (showNotifications) {
        AlertDialog(
            onDismissRequest = { showNotifications = false },
            title = { Text("Powiadomienia") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (uiState.negativeStockCount == 0 && uiState.pendingImportCount == 0 && uiState.openOrderCount == 0 && tasks.none { !it.isCompleted } && duplicateCandidates.isEmpty()) {
                        Text("Brak nowych powiadomień.")
                    }
                    if (uiState.negativeStockCount > 0) Text("• ${uiState.negativeStockCount} produkty mają stan ujemny")
                    if (uiState.pendingImportCount > 0) Text("• ${uiState.pendingImportCount} pozycji importu wymaga mapowania")
                    if (duplicateCandidates.isNotEmpty()) Text("• ${duplicateCandidates.size} rekordów może być duplikatami")
                    if (uiState.openOrderCount > 0) Text("• ${uiState.openOrderCount} aktywne zamówienia")
                    val taskCount = tasks.count { !it.isCompleted }
                    if (taskCount > 0) Text("• $taskCount zadania do wykonania")
                }
            },
            confirmButton = { TextButton(onClick = { showNotifications = false }) { Text("Zamknij") } },
        )
    }
}

@Composable
fun ParsedNoteReviewScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: HomeViewModel,
) {
    val review by viewModel.noteReview.collectAsStateWithLifecycle()
    val people by viewModel.people.collectAsStateWithLifecycle()
    val jobPositions by viewModel.jobPositions.collectAsStateWithLifecycle()
    // Rozpoznanie może wskazać archiwalnie używany, ukryty produkt; decyzję zostawia użytkownikowi.
    val products by viewModel.allProducts.collectAsStateWithLifecycle()
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    val taskPlaces by viewModel.taskPlaces.collectAsStateWithLifecycle()
    val current = review
    if (current == null) {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val note = current.note
    val matchedPerson = note.person?.let { parsed -> recognizedPerson(parsed.fullName, people) }
    BackHandler {
        viewModel.closeReview()
        onBack()
    }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Surface(tonalElevation = 2.dp) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { viewModel.closeReview(); onBack() }) {
                    Icon(Icons.Default.ArrowBack, "Wróć")
                }
                Column {
                    Text(
                        when (note.kind) {
                            ParsedInputKind.ORDER -> if (current.rawText.isBlank() && note.items.isEmpty()) "Nowe zamówienie" else "Rozpoznane zamówienie"
                            ParsedInputKind.TASK -> "Rozpoznana lista zadań"
                            ParsedInputKind.CONTACT -> "Rozpoznane dane osoby"
                            ParsedInputKind.NOTE -> "Rozpoznana notatka"
                        },
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        if (note.analyzedByAi) "Propozycja AI · sprawdź każdą pozycję" else "Sprawdź dane przed zapisaniem",
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
        if (note.kind == ParsedInputKind.ORDER && current.rawText.isNotBlank()) {
            Surface(tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.heightIn(max = 112.dp).verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 8.dp)) {
                    Text("Oryginalna wiadomość", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(current.rawText, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (note.kind == ParsedInputKind.TASK && note.taskDraft != null) {
                TaskDraftReviewContent(
                    rawText = current.rawText,
                    initial = note.taskDraft,
                    people = people,
                    places = taskPlaces,
                    onSave = { draft ->
                        viewModel.saveTaskDraft(current.rawText, draft)
                        viewModel.closeReview(completed = true)
                        onBack()
                    },
                )
            } else ParsedNoteReviewContent(
                rawText = if (note.kind == ParsedInputKind.ORDER) "" else current.rawText,
                note = note,
                matchedPersonName = matchedPerson?.listDisplayName(),
                people = people,
                products = products,
                shipyards = shipyards,
                jobPositions = jobPositions,
                onAddPhone = { number -> matchedPerson?.let { viewModel.addPhoneNumber(it.id, number) } },
                onAddProductTags = viewModel::addProductTags,
                onCreatePerson = viewModel::createPersonForRecognizedOrder,
                onSaveTasks = {
                    viewModel.saveTasks(current.rawText, note.tasks)
                    viewModel.closeReview(completed = true)
                    onBack()
                },
                onSaveOrder = { itemPairs, rememberCorrections, shipyardName, plannedIssueDate ->
                    viewModel.saveDraftOrder(current.rawText, note.copy(shipyardName = shipyardName, suggestedIssueDate = plannedIssueDate), itemPairs, rememberCorrections)
                    viewModel.closeReview(completed = true)
                    onBack()
                },
            )
        }
    }
}

private enum class AttentionDetails { NEGATIVE_STOCK, PENDING_IMPORT, DUPLICATES }
private data class DuplicateCandidate(val kind: String, val id: String, val title: String, val subtitle: String)

@Composable
private fun WarehouseHeader(onNotifications: () -> Unit) {
    val date = remember {
        formatDisplayDate(java.time.LocalDate.now().toString())
    }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF17324A)).padding(horizontal = 17.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Dzień dobry", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(date, color = Color(0xFFCBDCE7), style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onNotifications) { Icon(Icons.Default.Notifications, "Powiadomienia", tint = Color.White) }
        }
    }
}

@Composable
private fun SmartInput(
    value: String,
    onChange: (String) -> Unit,
    aiLoading: Boolean,
    aiError: String?,
    onRecognizeLocal: () -> Unit,
    onRecognizeAi: () -> Unit,
) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(11.dp)) {
            OutlinedTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                maxLines = Int.MAX_VALUE,
                label = { Text("Szybkie pole") },
                placeholder = { Text("Wklej zamówienie, zadanie albo wpisz osobę, miejsce lub przedmiot…") },
            )
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onRecognizeLocal, enabled = value.isNotBlank() && !aiLoading, modifier = Modifier.weight(1f)) {
                    Text(if (pl.magazyn.mobile.domain.TaskTextParser().looksLikeTask(value)) "Utwórz zadanie z tekstu" else "Offline")
                }
                Button(onClick = onRecognizeAi, enabled = value.isNotBlank() && !aiLoading, modifier = Modifier.weight(1f)) {
                    if (aiLoading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Default.AutoAwesome, null)
                    Spacer(Modifier.width(5.dp))
                    Text(if (aiLoading) "Analizuję" else "Analizuj AI")
                }
            }
            aiError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
            Text("AI tworzy tylko propozycję — niczego nie zapisuje automatycznie.", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 5.dp))
        }
    }
}

@Composable
private fun QuickButtons(onNewOrder: () -> Unit, onQuickIssue: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ActionCard(
            title = "Nowe zamówienie",
            icon = Icons.Default.NoteAdd,
            primary = true,
            modifier = Modifier.weight(1f),
            onClick = onNewOrder,
        )
        ActionCard(
            title = "Szybkie wydanie",
            icon = Icons.Default.ArrowUpward,
            primary = false,
            modifier = Modifier.weight(1f),
            onClick = onQuickIssue,
        )
    }
}

@Composable
private fun ActionCard(
    title: String,
    icon: ImageVector,
    primary: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    val colors = if (primary) {
        CardDefaults.cardColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
    } else {
        CardDefaults.cardColors(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
    Card(modifier.height(92.dp).clickable(onClick = onClick), colors = colors) {
        Column(
            Modifier.fillMaxSize().padding(14.dp),
            Arrangement.SpaceBetween,
        ) {
            Icon(icon, null)
            Text(title, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun SectionHeader(title: String, action: String, onAction: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (action.isNotBlank()) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun ActiveOrderCard(count: Int, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(13.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                Column {
                    Text("Aktywne zamówienia: $count", fontWeight = FontWeight.SemiBold)
                    Text("Dotknij, aby przejść do kompletowania", style = MaterialTheme.typography.labelMedium)
                }
                Icon(Icons.Default.ChevronRight, null)
            }
        }
    }
}

@Composable
private fun EmptyOrdersCard() {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Text("Brak otwartych zamówień", Modifier.padding(14.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun AttentionRow(icon: ImageVector, text: String, warning: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, null, tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        Text(text, Modifier.weight(1f), color = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (onClick != null) Icon(Icons.Default.ChevronRight, "Pokaż szczegóły")
    }
}

@Composable
private fun AttentionDetailsSheet(
    kind: AttentionDetails,
    negativeItems: List<pl.magazyn.mobile.data.NegativeStockItem>,
    pendingItems: List<pl.magazyn.mobile.data.PendingImportDetail>,
    products: List<pl.magazyn.mobile.data.ProductWithStock>,
    duplicates: List<DuplicateCandidate>,
    onResolvePending: (List<pl.magazyn.mobile.data.PendingImportDetail>, String?, String, String) -> Unit,
    onCorrectNegative: (pl.magazyn.mobile.data.NegativeStockItem, Long) -> Unit,
    onOpenDuplicate: (DuplicateCandidate) -> Unit,
    onClose: () -> Unit,
) {
    var selectedPending by remember { mutableStateOf<pl.magazyn.mobile.data.PendingImportDetail?>(null) }
    var selectedNegative by remember { mutableStateOf<pl.magazyn.mobile.data.NegativeStockItem?>(null) }
    var pendingSearch by rememberSaveable { mutableStateOf("") }
    val pendingGroups = pendingItems.groupBy { pl.magazyn.mobile.domain.ImportParser.key(it.rawProductName) }
        .values
        .filter { group -> pendingSearch.isBlank() || group.any { item -> pl.magazyn.mobile.domain.matchesSearch(pendingSearch, item.rawProductName, item.sourceFileName, item.recipientLabel, item.recipientFirstName, item.recipientLastName) } }
        .sortedBy { pl.magazyn.mobile.domain.ImportParser.key(it.first().rawProductName) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    when (kind) {
                        AttentionDetails.NEGATIVE_STOCK -> "Ujemne stany"
                        AttentionDetails.PENDING_IMPORT -> "Pozycje wymagające mapowania"
                        AttentionDetails.DUPLICATES -> "Możliwe duplikaty"
                    },
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    when (kind) {
                        AttentionDetails.NEGATIVE_STOCK -> "Przedmioty wydane poniżej zera"
                        AttentionDetails.PENDING_IMPORT -> "Aplikacja nie rozpoznała tych nazw przedmiotów"
                        AttentionDetails.DUPLICATES -> "Otwórz rekord, porównaj dane i popraw albo usuń niewłaściwy"
                    },
                    style = MaterialTheme.typography.labelMedium,
                )
            }
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zamknij") }
        }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 520.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            if (kind == AttentionDetails.NEGATIVE_STOCK) {
                items(negativeItems.size) { index ->
                    val item = negativeItems[index]
                    DetailLine(
                        item.name + item.variant?.let { " · $it" }.orEmpty(),
                        listOf(productSecondaryLine(item.groupName, item.subgroupName), item.warehouseName, "${formatWholeQuantity(item.quantity)} ${item.unit}").filter(String::isNotBlank).joinToString(" · "),
                    ) { selectedNegative = item }
                }
            } else if (kind == AttentionDetails.PENDING_IMPORT) {
                item {
                    OutlinedTextField(
                        pendingSearch,
                        { pendingSearch = it },
                        Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        label = { Text("Szukaj nierozpoznanej nazwy") },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        singleLine = true,
                    )
                    Text("${pendingGroups.size} różnych nazw · ${pendingItems.size} pozycji. Jedna poprawka naprawia wszystkie identyczne nazwy.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(pendingGroups.size) { index ->
                    val group = pendingGroups[index]
                    val item = group.first()
                    val recipient = listOf(item.recipientLabel, item.recipientFirstName, item.recipientLastName)
                        .filter(String::isNotBlank).joinToString(" ")
                    DetailLine(
                        item.rawProductName + if (group.size > 1) " · ${group.size} wpisów" else "",
                        listOf(item.sourceFileName, if (group.size == 1) "wiersz ${item.sourceRowNumber}" else "naprawa grupowa", recipient)
                            .filter(String::isNotBlank).joinToString(" · "),
                    ) { selectedPending = item }
                }
            } else {
                items(duplicates.size) { index ->
                    val candidate = duplicates[index]
                    DetailLine(candidate.title, candidate.subtitle) { onOpenDuplicate(candidate) }
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
    selectedPending?.let { item ->
        PendingMappingDialog(
            item = item,
            products = products,
            onDismiss = { selectedPending = null },
            onResolve = { productId, name, unit ->
                val sameNameItems = pendingItems.filter { pl.magazyn.mobile.domain.ImportParser.key(it.rawProductName) == pl.magazyn.mobile.domain.ImportParser.key(item.rawProductName) }
                onResolvePending(sameNameItems, productId, name, unit)
                selectedPending = null
            },
        )
    }
    selectedNegative?.let { item ->
        var value by rememberSaveable(item.productId, item.warehouseId) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { selectedNegative = null },
            title = { Text("Popraw stan") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ProductInfo(item.name, item.variant, item.groupName, item.subgroupName)
                    Text("Obecny stan: ${formatWholeQuantity(item.quantity)} ${item.unit}")
                    OutlinedTextField(
                        value,
                        { input -> value = input.filterIndexed { index, character -> character.isDigit() || (character == '-' && index == 0) } },
                        label = { Text("Prawidłowy stan") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                    )
                    Text("Korekta zapisze się w historii. Powód nie jest wymagany.", style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = { Button(onClick = { value.toLongOrNull()?.let { onCorrectNegative(item, it) }; selectedNegative = null }, enabled = value.toLongOrNull() != null) { Text("Zapisz poprawkę") } },
            dismissButton = { TextButton(onClick = { selectedNegative = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun DetailLine(title: String, subtitle: String, onClick: (() -> Unit)? = null) {
    Column(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 9.dp),
    ) {
        Text(title, fontWeight = FontWeight.SemiBold)
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (onClick != null) Text("Dotknij, aby naprawić lub przypisać", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
    }
    HorizontalDivider()
}

@Composable
private fun PendingMappingDialog(
    item: pl.magazyn.mobile.data.PendingImportDetail,
    products: List<pl.magazyn.mobile.data.ProductWithStock>,
    onDismiss: () -> Unit,
    onResolve: (String?, String, String) -> Unit,
) {
    var query by rememberSaveable(item.sourceKey) { mutableStateOf(item.rawProductName) }
    var selectedProductId by rememberSaveable(item.sourceKey) { mutableStateOf<String?>(null) }
    var unit by rememberSaveable(item.sourceKey) { mutableStateOf("szt.") }
    var unitMenu by remember { mutableStateOf(false) }
    val matches = products.filter {
        pl.magazyn.mobile.domain.matchesSearch(query, it.name, it.variant.orEmpty(), it.aliases, it.tags)
    }.take(8)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Przypisz przedmiot") },
        text = {
            Column(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Z pliku: ${item.rawProductName}", fontWeight = FontWeight.SemiBold)
                Text("Wybór zostanie zastosowany do wszystkich oczekujących wpisów z tą samą nazwą.", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(query, { query = it; selectedProductId = null }, Modifier.fillMaxWidth(), label = { Text("Szukaj lub popraw nazwę") })
                if (matches.isNotEmpty()) {
                    Text("Istniejące przedmioty", style = MaterialTheme.typography.labelMedium)
                    matches.forEach { product ->
                        OutlinedCard(
                            onClick = { selectedProductId = product.id },
                            modifier = Modifier.fillMaxWidth(),
                            colors = if (selectedProductId == product.id) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer) else CardDefaults.outlinedCardColors(),
                        ) {
                    ProductInfo(product.name, product.variant, product.groupName, product.subgroupName, Modifier.fillMaxWidth().padding(9.dp), stockQuantity = product.stockQuantity.takeIf { product.stockKnown }, unit = product.unit)
                        }
                    }
                } else {
                    Text("Nie znaleziono podobnego przedmiotu. Możesz utworzyć nowy.", style = MaterialTheme.typography.bodySmall)
                }
                Box {
                    OutlinedButton(onClick = { unitMenu = true }, Modifier.fillMaxWidth()) { Text("Jednostka nowego przedmiotu: $unit") }
                    DropdownMenu(expanded = unitMenu, onDismissRequest = { unitMenu = false }) {
                        listOf("szt.", "para", "opak.", "pudełko", "karton", "komplet").forEach { choice ->
                            DropdownMenuItem(text = { Text(choice) }, onClick = { unit = choice; unitMenu = false })
                        }
                    }
                }
            }
        },
        confirmButton = {
            Column(horizontalAlignment = Alignment.End) {
                Button(
                    onClick = { onResolve(selectedProductId, query, unit) },
                    enabled = selectedProductId != null,
                ) { Text("Połącz z wybranym") }
                TextButton(onClick = { onResolve(null, query, unit) }, enabled = query.isNotBlank()) { Text("Utwórz nowy przedmiot") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ParsedNoteReviewContent(
    rawText: String,
    note: ParsedNote,
    matchedPersonName: String?,
    people: List<pl.magazyn.mobile.data.EmployeeSummary>,
    products: List<pl.magazyn.mobile.data.ProductWithStock>,
    shipyards: List<pl.magazyn.mobile.data.ShipyardEntity>,
    jobPositions: List<pl.magazyn.mobile.data.JobPositionEntity>,
    onAddPhone: (String) -> Unit,
    onAddProductTags: (String, String) -> Unit,
    onCreatePerson: (String, String, String, String, String, (String, String) -> Unit) -> Unit,
    onSaveTasks: () -> Unit,
    onSaveOrder: (List<Pair<pl.magazyn.mobile.domain.ParsedItem, pl.magazyn.mobile.domain.ParsedItem>>, Boolean, String?, String) -> Unit,
) {
    val addedPhones = remember(note) { mutableStateListOf<String>() }
    val sourceItems = remember(note) { mutableStateListOf<pl.magazyn.mobile.domain.ParsedItem>().apply { addAll(note.items) } }
    val editedItems = remember(note) { mutableStateListOf<pl.magazyn.mobile.domain.ParsedItem>().apply { addAll(note.items) } }
    val approvedItems = remember(note) { mutableStateMapOf<Int, Boolean>().apply { note.items.indices.forEach { put(it, true) } } }
    val quantityTexts = remember(note) { mutableStateMapOf<Int, String>().apply { note.items.forEachIndexed { index, item -> put(index, item.quantity.toString()) } } }
    val tagTexts = remember(note) { mutableStateMapOf<Int, String>() }
    var editingItem by remember(note) { mutableStateOf<Int?>(null) }
    var newPersonItemIndex by remember(note) { mutableStateOf<Int?>(null) }
    var rememberCorrections by rememberSaveable(note.items.size) { mutableStateOf(true) }
    var shipyardName by rememberSaveable(note) { mutableStateOf(note.shipyardName) }
    var defaultRecipientName by rememberSaveable(note) { mutableStateOf(note.shipyardName.orEmpty()) }
    var defaultRecipientIsShipyard by rememberSaveable(note) { mutableStateOf(note.shipyardName != null) }
    var shipyardMenu by remember { mutableStateOf(false) }
    var plannedIssueDate by rememberSaveable(note) { mutableStateOf(note.suggestedIssueDate ?: java.time.LocalDate.now().toString()) }
    var showPlannedDatePicker by remember { mutableStateOf(false) }
    val recognizedShipyard = shipyardName?.let { name -> shipyards.firstOrNull { it.name.equals(name, true) } }
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (rawText.isNotBlank()) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Oryginalna wiadomość", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(rawText, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        matchedPersonName?.let { name ->
            ReviewRow(name, note.person?.position?.let { p -> "stanowisko: $p" } ?: "rozpoznana osoba")
        }
        if (note.kind == ParsedInputKind.ORDER) {
            Text("Domyślny odbiorca", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !defaultRecipientIsShipyard, onClick = { defaultRecipientIsShipyard = false; defaultRecipientName = "" }, label = { Text("Osoba") }, leadingIcon = if (!defaultRecipientIsShipyard) { { Icon(Icons.Default.Person, null) } } else null)
                FilterChip(selected = defaultRecipientIsShipyard, onClick = { defaultRecipientIsShipyard = true; defaultRecipientName = shipyardName.orEmpty() }, label = { Text("Stocznia") }, leadingIcon = if (defaultRecipientIsShipyard) { { Icon(Icons.Default.Business, null) } } else null)
            }
            if (defaultRecipientIsShipyard) {
                Box(Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { shipyardMenu = true }, Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Business, null); Spacer(Modifier.width(7.dp))
                        Text(defaultRecipientName.ifBlank { "Wybierz stocznię" })
                    }
                    DropdownMenu(expanded = shipyardMenu, onDismissRequest = { shipyardMenu = false }) {
                        DropdownMenuItem(text = { Text("Bez domyślnego odbiorcy") }, onClick = { defaultRecipientName = ""; shipyardMenu = false })
                        shipyards.forEach { shipyard -> DropdownMenuItem(text = { Text(shipyard.name) }, onClick = { defaultRecipientName = shipyard.name; shipyardName = shipyard.name; shipyardMenu = false }) }
                    }
                }
            } else {
                OutlinedTextField(defaultRecipientName, { defaultRecipientName = it }, Modifier.fillMaxWidth(), label = { Text("Osoba / pracownik") }, singleLine = true)
                matchingPeople(defaultRecipientName, people).take(4).forEach { person ->
                    TextButton(onClick = { defaultRecipientName = person.listDisplayName() }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Person, null); Spacer(Modifier.width(6.dp)); Text(person.listDisplayName(), Modifier.weight(1f)) }
                }
            }
            OutlinedButton(
                onClick = {
                    val recipient = defaultRecipientName.trim().takeIf(String::isNotBlank) ?: return@OutlinedButton
                    editedItems.indices.filter { editedItems[it].recipientName.isNullOrBlank() }.forEach { i -> editedItems[i] = editedItems[i].copy(recipientName = recipient) }
                },
                enabled = defaultRecipientName.isNotBlank(), modifier = Modifier.fillMaxWidth(),
            ) { Text("Zastosuj do wszystkich bez odbiorcy") }
            OutlinedButton(onClick = { showPlannedDatePicker = true }, Modifier.fillMaxWidth()) {
                Icon(Icons.Default.CalendarMonth, null)
                Spacer(Modifier.width(7.dp))
                Text(if (note.suggestedIssueDate != null) "Proponowana data wydania: ${formatDisplayDate(plannedIssueDate)}" else "Data wydania: ${formatDisplayDate(plannedIssueDate)}")
            }
            if (note.suggestedIssueDate != null) Text("Data została rozpoznana z zapisu DD.MM w notatce — sprawdź ją przed utworzeniem szkicu.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
        note.phoneNumbers.forEach { number ->
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(number, fontWeight = FontWeight.SemiBold)
                    Text(
                        matchedPersonName?.let { "Numer dla: $it" } ?: "Nie znaleziono jednoznacznie osoby w bazie",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                if (matchedPersonName != null) {
                    TextButton(
                        onClick = { onAddPhone(number); addedPhones.add(number) },
                        enabled = number !in addedPhones,
                    ) { Text(if (number in addedPhones) "Dodano" else "Dodaj do profilu") }
                }
            }
            HorizontalDivider()
        }
        if (note.tasks.isNotEmpty()) {
            Text("Lista zadań", style = MaterialTheme.typography.titleMedium)
            note.tasks.forEach { task ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(false, null)
                    Text(task)
                }
            }
            Button(onClick = onSaveTasks, modifier = Modifier.fillMaxWidth()) { Text("Zapisz listę zadań") }
        }
        editedItems.forEachIndexed { index, item ->
            val productMatches = matchingProducts(item, products, strictOfflineMatching = !note.analyzedByAi)
            val productMatch = productMatches.singleOrNull()
            val recipientQuery = item.recipientName.orEmpty()
            val personMatches = matchingPeople(recipientQuery, people)
            val personMatch = recognizedPerson(recipientQuery, people)
            val shipyardMatches = matchingShipyards(recipientQuery, shipyards)
            val recipientShipyard = recognizedRecipientShipyard(recipientQuery, shipyards)
            val details = listOfNotNull(
                item.recipientName?.let { name -> "dla: ${personMatch?.listDisplayName() ?: recipientShipyard?.name ?: name}" }
                    ?: recognizedShipyard?.let { "dla stoczni: ${it.name} (domyślnie)" },
                item.variant?.let { v -> "rozmiar $v" },
                formatWholeQuantity(item.quantity) + " " + item.unit,
                item.notes.takeIf(String::isNotBlank),
            ).joinToString(" · ")
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(approvedItems[index] == true, { approvedItems[index] = it })
                    Column(Modifier.weight(1f)) {
                        Text(item.name, fontWeight = FontWeight.SemiBold)
                        Text(details, style = MaterialTheme.typography.labelMedium)
                        if (productMatches.isEmpty()) Text("Nie rozpoznano przedmiotu", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        else if (productMatch == null) Text("Kilka pasujących przedmiotów — wybierz właściwy", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        else {
                            Text("Proponowany przedmiot", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            ProductInfo(productMatch.name, productMatch.variant, productMatch.groupName, productMatch.subgroupName)
                            if (productMatch.isHidden) Text("Ten przedmiot jest ukryty — możesz wybrać inną aktywną pozycję.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                        }
                        when {
                            recipientQuery.isBlank() && defaultRecipientName.isNotBlank() -> Text("Odbiorca domyślny: ${defaultRecipientName}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            recipientQuery.isBlank() -> Text("Brak odbiorcy przy tej pozycji", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            personMatch != null -> Text("Osoba: ${personMatch.listDisplayName()}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            recipientShipyard != null -> Text("Stocznia: ${recipientShipyard.name}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                            else -> {
                                Text("Nie rozpoznano odbiorcy w bazie", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                                TextButton(onClick = { newPersonItemIndex = index }) {
                                    Icon(Icons.Default.PersonAdd, null)
                                    Spacer(Modifier.width(5.dp))
                                    Text("Dodaj nową osobę")
                                }
                            }
                        }
                    }
                    TextButton(onClick = { editingItem = if (editingItem == index) null else index }) {
                        Text(if (editingItem == index) "Zamknij" else "Popraw")
                    }
                }
                if (editingItem == index) {
                    ElevatedCard(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("1. Odbiorca", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(
                            item.recipientName.orEmpty(),
                            { editedItems[index] = item.copy(recipientName = it.ifBlank { null }) },
                            Modifier.fillMaxWidth().keepAboveKeyboard(),
                            label = { Text("Osoba lub stocznia") },
                            placeholder = { defaultRecipientName.takeIf(String::isNotBlank)?.let { Text("Domyślnie: $it") } },
                        )
                        if (recipientQuery.isBlank() && recognizedShipyard != null) {
                            Text("Bez wyboru odbiorcą będzie ${recognizedShipyard.name}.", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (personMatches.isNotEmpty()) {
                            Text("Proponowane osoby", style = MaterialTheme.typography.labelMedium)
                            personMatches.take(4).forEach { person ->
                                OutlinedButton(onClick = { editedItems[index] = item.copy(recipientName = person.listDisplayName()) }, Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Person, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(person.listDisplayName() + person.positions.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty(), Modifier.weight(1f))
                                }
                            }
                        }
                        if (shipyardMatches.isNotEmpty()) {
                            Text("Proponowane stocznie", style = MaterialTheme.typography.labelMedium)
                            shipyardMatches.take(4).forEach { shipyard ->
                                OutlinedButton(onClick = { editedItems[index] = item.copy(recipientName = shipyard.name) }, Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Business, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(shipyard.name)
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 3.dp))
                        Text("2. Przedmiot", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        OutlinedTextField(item.name, { editedItems[index] = item.copy(name = it) }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Nazwa, alias lub tag") })
                        if (productMatches.isNotEmpty()) {
                            Text("Proponowane przedmioty", style = MaterialTheme.typography.labelMedium)
                            productMatches.take(5).forEach { product ->
                                OutlinedButton(onClick = { editedItems[index] = item.copy(name = product.name, variant = product.variant, unit = product.unit) }, Modifier.fillMaxWidth()) {
                                    ProductInfo(product.name, product.variant, product.groupName, product.subgroupName, Modifier.weight(1f), stockQuantity = product.stockQuantity.takeIf { product.stockKnown }, unit = product.unit)
                                }
                            }
                        }
                        if (productMatch != null) {
                            OutlinedTextField(tagTexts[index].orEmpty(), { tagTexts[index] = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Dopisz tagi do wybranego przedmiotu") })
                            TextButton(onClick = { onAddProductTags(productMatch.id, tagTexts[index].orEmpty()); tagTexts[index] = "" }, enabled = !tagTexts[index].isNullOrBlank()) { Text("Zapisz tagi") }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 3.dp))
                        Text("3. Szczegóły", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(item.variant.orEmpty(), { editedItems[index] = item.copy(variant = it.ifBlank { null }) }, Modifier.weight(1f).keepAboveKeyboard(), label = { Text("Wariant") })
                            OutlinedTextField(
                                quantityTexts[index].orEmpty(),
                                { value ->
                                    val digits = value.filter(Char::isDigit)
                                    quantityTexts[index] = digits
                                    digits.toLongOrNull()?.let { editedItems[index] = item.copy(quantity = it.coerceAtLeast(1)) }
                                },
                                Modifier.weight(1f).keepAboveKeyboard(),
                                label = { Text("Ilość") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                        }
                        OutlinedTextField(item.unit, { editedItems[index] = item.copy(unit = it) }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Jednostka") })
                        OutlinedTextField(item.notes, { editedItems[index] = item.copy(notes = it) }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Uwagi") })
                    }
                    }
                }
                HorizontalDivider()
            }
        }
        if (note.kind == ParsedInputKind.ORDER) {
            OutlinedButton(
                onClick = {
                    val added = pl.magazyn.mobile.domain.ParsedItem(
                        name = "",
                        variant = null,
                        quantity = 1,
                        unit = "szt.",
                        confidence = 1f,
                        recipientName = null,
                        notes = "",
                    )
                    val newIndex = editedItems.size
                    sourceItems.add(added)
                    editedItems.add(added)
                    approvedItems[newIndex] = true
                    quantityTexts[newIndex] = "1"
                    editingItem = newIndex
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, null)
                Spacer(Modifier.width(7.dp))
                Text("Dodaj kolejną pozycję")
            }
        }
        if (editedItems.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(rememberCorrections, { rememberCorrections = it })
                Column {
                    Text("Zapamiętaj moje poprawki", style = MaterialTheme.typography.bodyMedium)
                    Text("Zostaną użyte przez parser offline przy podobnych notatkach.", style = MaterialTheme.typography.labelSmall)
                }
            }
            Button(
                onClick = {
                        onSaveOrder(
                            editedItems.mapIndexedNotNull { index, corrected ->
                            if (approvedItems[index] == true) sourceItems.getOrNull(index)?.let { source ->
                                val withDefault = if (corrected.recipientName.isNullOrBlank() && defaultRecipientName.isNotBlank()) corrected.copy(recipientName = defaultRecipientName.trim()) else corrected
                                val storageRecipient = withDefault.recipientName?.let { recipient -> recognizedPerson(recipient, people)?.fullName ?: recipient }
                                source to withDefault.copy(recipientName = storageRecipient)
                            } else null
                        },
                        rememberCorrections,
                        recognizedShipyard?.name,
                        plannedIssueDate,
                    )
                },
                enabled = approvedItems.values.any { it } && approvedItems.keys.filter { approvedItems[it] == true }.all {
                    !quantityTexts[it].isNullOrBlank() && editedItems.getOrNull(it)?.name?.isNotBlank() == true
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Utwórz szkic z zatwierdzonych pozycji") }
        }
        Spacer(Modifier.height(16.dp))
    }
    if (showPlannedDatePicker) {
        val initial = runCatching { java.time.LocalDate.parse(plannedIssueDate) }.getOrDefault(java.time.LocalDate.now())
        val state = rememberDatePickerState(initialSelectedDateMillis = initial.atStartOfDay(java.time.ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showPlannedDatePicker = false },
            confirmButton = { TextButton(onClick = {
                state.selectedDateMillis?.let { plannedIssueDate = java.time.Instant.ofEpochMilli(it).atZone(java.time.ZoneId.of("UTC")).toLocalDate().toString() }
                showPlannedDatePicker = false
            }) { Text("Wybierz") } },
            dismissButton = { TextButton(onClick = { showPlannedDatePicker = false }) { Text("Anuluj") } },
        ) { DatePicker(state) }
    }
    newPersonItemIndex?.let { index ->
        val recognizedName = editedItems.getOrNull(index)?.recipientName.orEmpty()
        NewOrderPersonDialog(
            jobPositions = jobPositions,
            initialRecipient = recognizedName,
            onDismiss = { newPersonItemIndex = null },
            onCreate = { first, last, phones, positions, aliases ->
                onCreatePerson(first, last, phones, positions, aliases) { _, fullName ->
                    editedItems.getOrNull(index)?.let { current ->
                        editedItems[index] = current.copy(recipientName = personDisplayName(last, first, fullName))
                    }
                    newPersonItemIndex = null
                }
            },
        )
    }
}

@Composable
private fun TaskDraftReviewContent(
    rawText: String,
    initial: pl.magazyn.mobile.domain.ParsedTaskDraft,
    people: List<pl.magazyn.mobile.data.EmployeeSummary>,
    places: List<pl.magazyn.mobile.data.TaskPlaceView>,
    onSave: (pl.magazyn.mobile.domain.ParsedTaskDraft) -> Unit,
) {
    var title by remember(initial) { mutableStateOf(initial.title) }
    var date by remember(initial) { mutableStateOf(initial.date.orEmpty()) }
    var description by remember(initial) { mutableStateOf(initial.description) }
    val steps = remember(initial) { mutableStateListOf<pl.magazyn.mobile.domain.ParsedTaskStep>().apply { addAll(initial.steps) } }
    Column(Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("Oryginalna wiadomość", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge); Text(rawText) } }
        Text("Podgląd zadania", style = MaterialTheme.typography.titleLarge)
        Text("Elementy oznaczone kolorem wymagają sprawdzenia. Nic nie zostanie zapisane przed zatwierdzeniem.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(title, { title = it }, Modifier.fillMaxWidth(), label = { Text("Tytuł") }, singleLine = true)
        OutlinedTextField(date, { date = it }, Modifier.fillMaxWidth(), label = { Text("Data (RRRR-MM-DD, opcjonalnie)") }, singleLine = true)
        OutlinedTextField(description, { description = it }, Modifier.fillMaxWidth(), label = { Text("Opis / nierozstrzygnięte informacje") }, minLines = 2)
        steps.forEachIndexed { stepIndex, step ->
            OutlinedCard(Modifier.fillMaxWidth()) { Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Etap ${stepIndex + 1}", Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                    ConfidenceLabel(step.confidence)
                    IconButton(onClick = { steps.removeAt(stepIndex) }) { Icon(Icons.Default.DeleteOutline, "Usuń") }
                }
                OutlinedTextField(step.time.orEmpty(), { steps[stepIndex] = step.copy(time = it.ifBlank { null }) }, Modifier.fillMaxWidth(), label = { Text("Godzina") }, singleLine = true)
                var placeQuery by remember(initial, stepIndex) { mutableStateOf(step.placeText) }
                OutlinedTextField(placeQuery, { placeQuery = it; steps[stepIndex] = step.copy(placeId = null, placeText = it, confidence = pl.magazyn.mobile.domain.ParseConfidence.REVIEW) }, Modifier.fillMaxWidth(), label = { Text("Miejsce lub alias") }, singleLine = true)
                if (step.placeId == null && placeQuery.isNotBlank()) {
                    val placeMatches = places.filter { place -> (listOf(place.name) + place.aliases.split(',')).any { pl.magazyn.mobile.domain.ImportParser.key(it).contains(pl.magazyn.mobile.domain.ImportParser.key(placeQuery)) } }.take(4)
                    placeMatches.forEach { place -> TextButton(onClick = { placeQuery = place.name; steps[stepIndex] = step.copy(placeId = place.id, placeText = place.name, confidence = pl.magazyn.mobile.domain.ParseConfidence.CERTAIN) }, Modifier.fillMaxWidth()) { Text("${place.name}${place.aliases.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty()}") } }
                    if (placeMatches.isEmpty()) Text("Nie znaleziono miejsca — po zatwierdzeniu zostanie dodane jako nowe.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                }
                OutlinedTextField(step.note, { steps[stepIndex] = step.copy(note = it) }, Modifier.fillMaxWidth(), label = { Text("Notatka etapu") })
                step.people.forEachIndexed { personIndex, person ->
                    Column(Modifier.fillMaxWidth().padding(start = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(person.displayText, { value ->
                                val updated = step.people.toMutableList(); updated[personIndex] = person.copy(employeeId = null, displayText = value, confidence = pl.magazyn.mobile.domain.ParseConfidence.REVIEW); steps[stepIndex] = step.copy(people = updated)
                            }, Modifier.weight(1f), label = { Text("Osoba") }, singleLine = true)
                            ConfidenceLabel(person.confidence)
                        }
                        if (person.employeeId == null) {
                            Text("Nie znaleziono pracownika", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                            matchingPeople(person.displayText, people).take(4).forEach { employee ->
                                TextButton(onClick = {
                                    val updated = step.people.toMutableList(); updated[personIndex] = person.copy(employeeId = employee.id, displayText = employee.listDisplayName(), confidence = pl.magazyn.mobile.domain.ParseConfidence.CERTAIN); steps[stepIndex] = step.copy(people = updated)
                                }, Modifier.fillMaxWidth()) { Text(employee.listDisplayName()) }
                            }
                        }
                        OutlinedTextField(person.note, { value -> val updated = step.people.toMutableList(); updated[personIndex] = person.copy(note = value); steps[stepIndex] = step.copy(people = updated) }, Modifier.fillMaxWidth(), label = { Text("Notatka osoby") })
                    }
                }
                OutlinedButton(onClick = { steps[stepIndex] = step.copy(people = step.people + pl.magazyn.mobile.domain.ParsedTaskPerson(null, "", confidence = pl.magazyn.mobile.domain.ParseConfidence.REVIEW)) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.PersonAdd, null); Text("Dodaj osobę / podpunkt") }
            } }
        }
        OutlinedButton(onClick = { steps += pl.magazyn.mobile.domain.ParsedTaskStep(confidence = pl.magazyn.mobile.domain.ParseConfidence.REVIEW) }, Modifier.fillMaxWidth()) { Icon(Icons.Default.Add, null); Text("Dodaj etap") }
        Button(onClick = { onSave(initial.copy(title = title.trim(), date = date.trim().ifBlank { null }, description = description.trim(), steps = steps.toList())) }, enabled = title.isNotBlank(), modifier = Modifier.fillMaxWidth()) { Text("Utwórz zadanie") }
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun ConfidenceLabel(confidence: pl.magazyn.mobile.domain.ParseConfidence) {
    val text = when (confidence) {
        pl.magazyn.mobile.domain.ParseConfidence.CERTAIN -> "Pewny"
        pl.magazyn.mobile.domain.ParseConfidence.LIKELY -> "Prawdopodobny"
        pl.magazyn.mobile.domain.ParseConfidence.REVIEW -> "Sprawdź"
    }
    Text(text, color = if (confidence == pl.magazyn.mobile.domain.ParseConfidence.REVIEW) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
}

private fun matchingProducts(
    item: pl.magazyn.mobile.domain.ParsedItem,
    products: List<pl.magazyn.mobile.data.ProductWithStock>,
    strictOfflineMatching: Boolean,
): List<pl.magazyn.mobile.data.ProductWithStock> {
    if (!strictOfflineMatching) {
        val nameKey = pl.magazyn.mobile.domain.ImportParser.key(item.name)
        val variantKey = pl.magazyn.mobile.domain.ImportParser.key(item.variant.orEmpty())
        return products.mapNotNull { product ->
            val labels = listOf(product.name) + product.aliases.split(',') + product.tags.split(',')
            val nameScore = when {
                pl.magazyn.mobile.domain.ImportParser.key(product.name) == nameKey -> 4
                labels.any { pl.magazyn.mobile.domain.ImportParser.key(it) == nameKey } -> 3
                pl.magazyn.mobile.domain.matchesSearch(item.name, product.name, product.aliases, product.tags) -> 1
                else -> 0
            }
            if (nameScore == 0) null else {
                val variantMatches = variantKey.isBlank() ||
                    pl.magazyn.mobile.domain.ImportParser.key(product.variant.orEmpty()) == variantKey ||
                    (product.aliases.split(',') + product.tags.split(',')).any { pl.magazyn.mobile.domain.ImportParser.key(it) == variantKey }
                if (!variantMatches) null else product to nameScore
            }
        }.sortedWith(compareByDescending<Pair<pl.magazyn.mobile.data.ProductWithStock, Int>> { it.second }.thenBy { it.first.name }.thenBy { it.first.variant.orEmpty() }).map { it.first }
    }
    val scored = products.mapNotNull { product ->
        pl.magazyn.mobile.domain.offlineProductMatchScore(
            item.name, item.variant, product.name, product.variant, product.aliases, product.tags,
        )?.let { score -> product to score }
    }
    val bestScore = scored.maxOfOrNull { it.second } ?: return emptyList()
    return scored.filter { it.second == bestScore }
        .sortedWith(compareBy<Pair<pl.magazyn.mobile.data.ProductWithStock, Int>> { it.first.name }.thenBy { it.first.variant.orEmpty() })
        .map { it.first }
}

private fun matchingPeople(query: String, people: List<pl.magazyn.mobile.data.EmployeeSummary>): List<pl.magazyn.mobile.data.EmployeeSummary> {
    val tokens = query.split(Regex("\\s+")).map { pl.magazyn.mobile.domain.ImportParser.key(it) }.filter(String::isNotBlank)
    if (tokens.isEmpty()) return emptyList()
    return people.map { person ->
        val searchable = pl.magazyn.mobile.domain.ImportParser.key(listOf(person.fullName, person.aliases, person.tags, person.positions).joinToString(" "))
        person to tokens.count { searchable.contains(it) }
    }.filter { it.second > 0 }.sortedWith(compareByDescending<Pair<pl.magazyn.mobile.data.EmployeeSummary, Int>> { it.second }.thenBy { it.first.lastName }).map { it.first }
}

private fun recognizedPerson(query: String, people: List<pl.magazyn.mobile.data.EmployeeSummary>): pl.magazyn.mobile.data.EmployeeSummary? {
    val key = pl.magazyn.mobile.domain.ImportParser.key(query)
    if (key.isBlank()) return null
    return people.firstOrNull { person ->
        val labels = listOf(person.fullName, "${person.lastName} ${person.firstName}") + person.aliases.split(',')
        labels.any { pl.magazyn.mobile.domain.ImportParser.key(it) == key }
    }
}

private fun matchingShipyards(query: String, shipyards: List<pl.magazyn.mobile.data.ShipyardEntity>): List<pl.magazyn.mobile.data.ShipyardEntity> {
    val tokens = query.split(Regex("\\s+")).map { pl.magazyn.mobile.domain.ImportParser.key(it) }.filter(String::isNotBlank)
    if (tokens.isEmpty()) return emptyList()
    return shipyards.filter { shipyard ->
        val searchable = pl.magazyn.mobile.domain.ImportParser.key(shipyard.name)
        tokens.all { searchable.contains(it) }
    }.sortedBy { it.name }
}

private fun recognizedRecipientShipyard(query: String, shipyards: List<pl.magazyn.mobile.data.ShipyardEntity>): pl.magazyn.mobile.data.ShipyardEntity? {
    val key = pl.magazyn.mobile.domain.ImportParser.key(query)
    if (key.isBlank()) return null
    return shipyards.firstOrNull { pl.magazyn.mobile.domain.ImportParser.key(it.name) == key }
}

@Composable
private fun ReviewRow(title: String, subtitle: String) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            Arrangement.SpaceBetween,
            Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.labelMedium)
            }
            Text("Sprawdź", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
        }
        HorizontalDivider()
    }
}
