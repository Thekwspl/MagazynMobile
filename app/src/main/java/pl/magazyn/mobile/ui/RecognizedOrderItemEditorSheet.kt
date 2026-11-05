package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.magazyn.mobile.data.EmployeeSummary
import pl.magazyn.mobile.data.JobPositionEntity
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.domain.ParsedItem
import pl.magazyn.mobile.domain.ProductUnits

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecognizedOrderItemEditorSheet(
    draft: RecognizedOrderItemDraft,
    products: List<ProductWithStock>,
    productIndex: RecognizedProductIndex,
    strictOfflineMatching: Boolean,
    people: List<EmployeeSummary>,
    shipyards: List<ShipyardEntity>,
    jobPositions: List<JobPositionEntity>,
    defaultRecipient: String,
    defaultIsShipyard: Boolean,
    onAddProductTags: (String, String) -> Unit,
    onCreatePerson: (String, String, String, String, String, (String, String) -> Unit) -> Unit,
    onDismiss: () -> Unit,
    onSave: (ParsedItem) -> Unit,
) {
    val item = draft.item
    val matches = remember(item.name, item.variant, item.productId, productIndex, strictOfflineMatching) {
        when {
            item.productId != null -> products.filter { it.id == item.productId }
            item.name.isBlank() -> emptyList()
            else -> productIndex.matches(item, strictOfflineMatching)
        }
    }
    val product = matches.singleOrNull()
    val query = item.recipientName.orEmpty()
    val person = remember(query, people) { recognizedPerson(query, people) }
    val yard = remember(query, shipyards) { recognizedRecipientShipyard(query, shipyards) }
    val hasExplicitRecipient = when (item.recipientKind) {
        "person" -> people.any { it.id == item.recipientId }
        "shipyard" -> shipyards.any { it.id == item.recipientId }
        else -> false
    }
    val usesDefault = query.isBlank() || isDefaultRecipient(query, defaultRecipient)
    val defaultResolves = if (defaultIsShipyard) recognizedRecipientShipyard(defaultRecipient, shipyards) != null
        else recognizedPerson(defaultRecipient, people) != null
    val recipientValid = when {
        hasExplicitRecipient -> true
        item.sourceRecipient != null || item.recipientId != null -> false
        usesDefault && defaultResolves -> true
        query.isBlank() -> draft.row != null && defaultRecipient.isBlank()
        else -> (person != null) xor (yard != null)
    }
    val quantity = draft.quantity
    val valid = item.name.isNotBlank() && product != null && !product.isHidden && recipientValid && quantity != null
    val focus = LocalFocusManager.current
    var creatingPerson by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.imePadding(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(if (draft.row == null) "Dodaj pozycję" else "Edytuj pozycję", style = MaterialTheme.typography.titleLarge)
                draft.initial.name.takeIf(String::isNotBlank)?.let {
                    Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                }
            }
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Zamknij edytor") }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            EditorRecipientFields(item, people, shipyards, defaultRecipient, recipientValid,
                onChange = draft::update, onCreatePerson = { creatingPerson = true })
            HorizontalDivider()
            EditorProductFields(item, matches, product, productIndex, draft::update, onAddProductTags)
            HorizontalDivider()
            Text("Ilość i jednostka", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    draft.quantityText, draft::updateQuantity,
                    Modifier.weight(1f).keepAboveKeyboard(), label = { Text("Ilość") },
                    singleLine = true, isError = quantity == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    ProductUnits.displayUnit(item, product?.unit), {}, Modifier.weight(1f),
                    label = { Text("Jednostka") }, readOnly = true, singleLine = true,
                )
            }
            if (quantity == null) EditorWarning("Podaj dodatnią liczbę całkowitą.")
            if (product != null && ProductUnits.needsConfirmation(item, product.unit)) {
                EditorWarning("Wiadomość podaje ${item.requestedUnit}, a Karta przedmiotu ma ${product.unit}.")
                OutlinedButton(onClick = { draft.confirmUnit(product.unit) }, enabled = quantity != null) {
                    Text("Potwierdzam ${quantity ?: "—"} ${product.unit}")
                }
            }
            item.sourceText?.let {
                Text("Fragment wiadomości", style = MaterialTheme.typography.titleSmall)
                Text(it, style = MaterialTheme.typography.bodyMedium)
                Text("Ilość w pierwotnej propozycji: ${draft.row?.source?.quantity ?: draft.initial.quantity}",
                    style = MaterialTheme.typography.labelSmall)
            }
            if (item.requiresSourceConfirmation) {
                EditorWarning("Fragment wymaga rozstrzygnięcia. Sprawdź przedmiot, wariant, ilość i odbiorcę.")
                OutlinedButton(onClick = draft::confirmSource, enabled = valid) { Text("Potwierdzam dane fragmentu") }
            }
            Button(
                onClick = {
                    if (valid && product != null && quantity != null) {
                        focus.clearFocus()
                        onSave(item.copy(
                            name = product.name, variant = product.variant, productId = product.id,
                            unit = product.unit, quantity = quantity,
                            recipientId = when {
                                hasExplicitRecipient -> item.recipientId
                                usesDefault && defaultResolves -> null
                                else -> person?.id ?: yard?.id
                            },
                            recipientKind = when {
                                hasExplicitRecipient -> item.recipientKind
                                usesDefault && defaultResolves -> null
                                person != null -> "person"
                                yard != null -> "shipyard"
                                else -> null
                            },
                        ))
                    }
                },
                enabled = valid, modifier = Modifier.fillMaxWidth(),
            ) { Text("Zapisz zmiany") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Anuluj") }
        }
    }
    if (creatingPerson) {
        NewOrderPersonDialog(
            jobPositions, query,
            onDismiss = { creatingPerson = false },
            onCreate = { first, last, phones, positions, aliases ->
                creatingPerson = false
                onCreatePerson(first, last, phones, positions, aliases) { id, fullName ->
                    draft.update(draft.item.copy(
                        recipientName = personDisplayName(last, first, fullName), recipientId = id, recipientKind = "person",
                    ))
                }
            },
        )
    }
}

@Composable
private fun EditorRecipientFields(
    item: ParsedItem,
    people: List<EmployeeSummary>,
    shipyards: List<ShipyardEntity>,
    defaultRecipient: String,
    recipientValid: Boolean,
    onChange: (ParsedItem) -> Unit,
    onCreatePerson: () -> Unit,
) {
    val query = item.recipientName.orEmpty()
    val personMatches = remember(query, people) { matchingPeople(query, people) }
    val yardMatches = remember(query, shipyards) { matchingShipyards(query, shipyards) }
    Text("Odbiorca", style = MaterialTheme.typography.titleSmall)
    item.sourceRecipient?.let { EditorWarning("Odbiorca z wiadomości: $it") }
    OutlinedTextField(
        query, { onChange(item.copy(recipientName = it.ifBlank { null }, recipientId = null, recipientKind = null)) },
        Modifier.fillMaxWidth().keepAboveKeyboard(),
        label = { Text("Osoba lub stocznia") }, singleLine = true,
        placeholder = { if (defaultRecipient.isNotBlank()) Text("Domyślnie: $defaultRecipient") },
    )
    if (!recipientValid) EditorWarning("Wybierz odbiorcę z listy.")
    if (item.recipientId == null) {
        SuggestionList(personMatches, key = { it.id }) { person ->
            OutlinedButton(onClick = {
                onChange(item.copy(recipientName = person.listDisplayName(), recipientId = person.id, recipientKind = "person"))
            }, Modifier.fillMaxWidth()) {
                Text(person.listDisplayName() + person.positions.takeIf(String::isNotBlank)?.let { " · $it" }.orEmpty())
            }
        }
        SuggestionList(yardMatches, key = { it.id }) { yard ->
            OutlinedButton(onClick = {
                onChange(item.copy(recipientName = yard.name, recipientId = yard.id, recipientKind = "shipyard"))
            }, Modifier.fillMaxWidth()) { Text(yard.name) }
        }
        TextButton(onClick = onCreatePerson) { Text("Dodaj nową osobę") }
    }
}

@Composable
private fun EditorProductFields(
    item: ParsedItem,
    matches: List<ProductWithStock>,
    product: ProductWithStock?,
    productIndex: RecognizedProductIndex,
    onChange: (ParsedItem) -> Unit,
    onAddProductTags: (String, String) -> Unit,
) {
    Text("Przedmiot", style = MaterialTheme.typography.titleSmall)
    OutlinedTextField(
        item.name, { onChange(item.copy(name = it, productId = null, confirmedCatalogUnit = null)) },
        Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Nazwa, alias lub tag") }, singleLine = true,
    )
    OutlinedTextField(
        item.variant.orEmpty(), { onChange(item.copy(variant = it.ifBlank { null }, productId = null, confirmedCatalogUnit = null)) },
        Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Wariant") }, singleLine = true,
    )
    when {
        matches.isEmpty() -> EditorWarning("Nie rozpoznano przedmiotu.")
        product == null -> EditorWarning("Kilka pasujących przedmiotów. Wybierz właściwy.")
        product.isHidden -> EditorWarning("Przedmiot jest ukryty. Wybierz aktywny przedmiot.")
    }
    if (item.productId == null && item.name.isNotBlank()) {
        val suggestions = remember(item.name, item.variant, matches, productIndex) {
            matches.ifEmpty { productIndex.matches(item.copy(variant = null), strictOfflineMatching = false) }
        }
        SuggestionList(suggestions, key = { it.id }) { candidate ->
            OutlinedButton(onClick = {
                onChange(item.copy(name = candidate.name, variant = candidate.variant, unit = candidate.unit,
                    productId = candidate.id, availableQuantity = null, confirmedCatalogUnit = null))
            }, Modifier.fillMaxWidth()) {
                ProductInfo(candidate.name, candidate.variant, candidate.groupName, candidate.subgroupName,
                    Modifier.weight(1f), stockQuantity = candidate.stockQuantity.takeIf { candidate.stockKnown }, unit = candidate.unit)
            }
        }
    }
    product?.let { selected ->
        Text(productTitle(selected.name, selected.variant), style = MaterialTheme.typography.bodyMedium)
        selected.stockQuantity.takeIf { selected.stockKnown }?.let {
            Text("Stan: ${formatWholeQuantity(it)} ${selected.unit}", style = MaterialTheme.typography.labelSmall)
        }
        var tags by remember(selected.id) { mutableStateOf("") }
        OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Dopisz tagi") })
        TextButton(
            onClick = { onAddProductTags(selected.id, tags); tags = "" },
            enabled = tags.isNotBlank() && !selected.isHidden,
        ) { Text("Zapisz tagi w Karcie przedmiotu") }
    }
}

@Composable
private fun EditorWarning(message: String) {
    Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}
