package pl.magazyn.mobile.ui

import android.content.Intent
import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.data.ProductSubgroupChoice
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.matchesSearch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductsScreen(
    contentPadding: PaddingValues,
    startAdding: Boolean = false,
    initialProductId: String? = null,
    onProduct: (String) -> Unit = {},
    onAdd: () -> Unit = {},
    onBack: () -> Unit = {},
    viewModel: ProductsViewModel = viewModel(),
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val subgroups by viewModel.subgroups.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val searchTokens = ImportParser.key(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val visible = products.filter { product ->
        searchTokens.isEmpty() || matchesSearch(
            query, product.name, product.variant.orEmpty(), product.aliases, product.tags, product.category, product.groupName, product.subgroupName,
        )
    }
    if (startAdding || initialProductId != null) {
        val edited = initialProductId?.let { id -> products.firstOrNull { it.id == id } }
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            BackScreenHeader(
                title = if (startAdding) "Nowy przedmiot" else "Karta przedmiotu",
                subtitle = if (startAdding) null else "Dane, stan i ustawienia przedmiotu",
                onBack = onBack,
            )
            if (initialProductId != null && edited == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                Box(Modifier.weight(1f)) { ProductEditor(
                    product = edited,
                    groups = groups,
                    subgroups = subgroups,
                    categories = categories,
                    units = (listOf("szt.", "para", "opak.", "paczka", "kpl.", "metr", "rolka") + products.map { it.unit })
                        .filter(String::isNotBlank).distinctBy { it.lowercase() },
                    onCancel = onBack,
                    onSave = { existing, draft -> viewModel.saveProduct(existing, draft); onBack() },
                    onCorrectStock = { product, counted -> viewModel.correctStock(product, counted) },
                    onRemove = { product -> viewModel.removeProduct(product.id); onBack() },
                ) }
            }
        }
        return
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader("Przedmioty", "Dodaj", onAdd)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Szukaj po nazwie, aliasie lub tagu") }, singleLine = true)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { product ->
                OutlinedCard(
                    onClick = { onProduct(product.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = if (product.isHidden) CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)) else CardDefaults.outlinedCardColors(),
                ) {
                    Column {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Inventory2, null)
                            ProductInfo(
                                product.name, product.variant, product.groupName, product.subgroupName,
                                stockQuantity = product.stockQuantity.takeIf { product.stockKnown }, unit = product.unit,
                                modifier = Modifier.padding(start = 10.dp).weight(1f),
                            )
                        }
                        if (product.isHidden) Text(
                            "Ukryty",
                            modifier = Modifier.fillMaxWidth().padding(bottom = 7.dp),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductEditor(
    product: ProductWithStock?,
    groups: List<String>,
    subgroups: List<ProductSubgroupChoice>,
    categories: List<String>,
    units: List<String>,
    onCancel: () -> Unit,
    onSave: (ProductWithStock?, ProductDraft) -> Unit,
    onCorrectStock: (ProductWithStock, Long) -> Unit,
    onRemove: (ProductWithStock) -> Unit,
) {
    var name by rememberSaveable(product?.id) { mutableStateOf(product?.name.orEmpty()) }
    var variant by rememberSaveable(product?.id) { mutableStateOf(product?.variant.orEmpty()) }
    var unit by rememberSaveable(product?.id) { mutableStateOf(product?.unit ?: "szt.") }
    var category by rememberSaveable(product?.id) { mutableStateOf(product?.category.orEmpty()) }
    var groupName by rememberSaveable(product?.id) { mutableStateOf(product?.groupName.orEmpty()) }
    var subgroupName by rememberSaveable(product?.id) { mutableStateOf(product?.subgroupName.orEmpty()) }
    var aliases by rememberSaveable(product?.id) { mutableStateOf(product?.aliases.orEmpty()) }
    var tags by rememberSaveable(product?.id) { mutableStateOf(product?.tags.orEmpty()) }
    var photoUri by rememberSaveable(product?.id) { mutableStateOf(product?.photoUri.orEmpty()) }
    var returnable by rememberSaveable(product?.id) { mutableStateOf(product?.isReturnable ?: false) }
    var hidden by rememberSaveable(product?.id) { mutableStateOf(product?.isHidden ?: false) }
    var threshold by rememberSaveable(product?.id) {
        mutableStateOf(product?.lowStockThreshold?.toLong()?.takeIf { it != 0L }?.toString().orEmpty())
    }
    var initialQuantity by rememberSaveable(product?.id) { mutableStateOf("") }
    var repeatIssueWeeks by rememberSaveable(product?.id) {
        mutableStateOf(product?.repeatIssueWeeks?.takeIf { it != 0 }?.toString().orEmpty())
    }
    var counted by rememberSaveable(product?.id) { mutableStateOf(product?.stockQuantity?.toLong()?.toString().orEmpty()) }
    var confirmRemoval by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            photoUri = it.toString()
        }
    }

    Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
        if (product != null) {
            Text("Stan magazynowy", style = MaterialTheme.typography.titleMedium)
            Text(if (product.stockKnown) "Aktualnie: ${formatWholeQuantity(product.stockQuantity)} ${product.unit}" else "Aktualnie: stan nieustalony")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    counted,
                    { value -> counted = value.filterIndexed { index, character -> character.isDigit() || (character == '-' && index == 0) } },
                    Modifier.weight(0.8f).keepAboveKeyboard(),
                    label = { Text("Stan") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(onClick = { counted.toLongOrNull()?.let { onCorrectStock(product, it) } }, enabled = counted.toLongOrNull() != null, modifier = Modifier.weight(1.2f)) {
                    Text("Zapisz korektę stanu", textAlign = TextAlign.Center)
                }
            }
            HorizontalDivider(Modifier.padding(vertical = 5.dp))
        }
        ProductPhoto(photoUri, Modifier.fillMaxWidth().height(150.dp))
        OutlinedButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Default.Image, null)
            Text(if (photoUri.isBlank()) "Dodaj zdjęcie" else "Zmień zdjęcie")
        }
        OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Nazwa") }, singleLine = true)
        if (product == null) {
            OutlinedTextField(
                initialQuantity,
                { value -> initialQuantity = value.filterIndexed { index, character -> character.isDigit() || (character == '-' && index == 0) } },
                Modifier.fillMaxWidth().keepAboveKeyboard(),
                label = { Text("Stan początkowy") },
                placeholder = { Text("0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(variant, { variant = it }, Modifier.weight(1f).keepAboveKeyboard(), label = { Text("Wariant") }, singleLine = true)
            ChoiceField(unit, { unit = it }, "Jednostka *", units, Modifier.weight(1f))
        }
        Text("Klasyfikacja jest opcjonalna. Możesz wybrać istniejącą wartość albo wpisać nową.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            EditableChoiceField(groupName, { groupName = it }, "Grupa", groups, Modifier.weight(1f))
            EditableChoiceField(
                subgroupName,
                { subgroupName = it },
                "Podgrupa",
                subgroups.filter { groupName.isBlank() || it.groupName.isBlank() || it.groupName.equals(groupName, true) }.map { it.name }.distinct(),
                Modifier.weight(1f),
            )
        }
        EditableChoiceField(category, { category = it }, "Kategoria", categories)
        EditableChipInput(
            items = aliases.split(','),
            label = "Nowy alias",
            onAdd = { value, done ->
                val current = aliases.split(',').map(String::trim).filter(String::isNotBlank)
                val canAdd = value.isNotBlank() && current.none { it.equals(value, ignoreCase = true) }
                if (canAdd) aliases = (current + value).joinToString(", ")
                done(canAdd)
            },
            onRemove = { removed -> aliases = aliases.split(',').map(String::trim).filter { !it.equals(removed, true) && it.isNotBlank() }.joinToString(", ") },
        )
        EditableChipInput(
            items = tags.split(','),
            label = "Nowy tag",
            onAdd = { value, done ->
                val current = tags.split(',').map(String::trim).filter(String::isNotBlank)
                val canAdd = value.isNotBlank() && current.none { it.equals(value, ignoreCase = true) }
                if (canAdd) tags = (current + value).joinToString(", ")
                done(canAdd)
            },
            onRemove = { removed -> tags = tags.split(',').map(String::trim).filter { !it.equals(removed, true) && it.isNotBlank() }.joinToString(", ") },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                threshold,
                { value -> threshold = value.filter(Char::isDigit) },
                Modifier.weight(1f).keepAboveKeyboard(),
                label = { Text("Próg niskiego stanu") },
                placeholder = { Text("0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            OutlinedTextField(
                repeatIssueWeeks,
                { value -> repeatIssueWeeks = value.filter(Char::isDigit) },
                Modifier.weight(1f).keepAboveKeyboard(),
                label = { Text("Ponowne wydanie (tyg.)") },
                placeholder = { Text("0") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
        }
        Text("0 tygodni oznacza brak ograniczenia", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(returnable, { returnable = it })
            Text("Sprzęt powierzony — wymaga zwrotu", Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(hidden, { hidden = it })
            Column(Modifier.padding(start = 8.dp)) {
                Text("Ukryty przedmiot")
                Text("Nie będzie widoczny na zwykłych listach i przy ręcznym wyborze.", style = MaterialTheme.typography.labelSmall)
            }
        }
        Button(
            onClick = {
                onSave(
                    product,
                    ProductDraft(
                        name = name,
                        variant = variant,
                        unit = unit,
                        category = category,
                        groupName = groupName,
                        subgroupName = subgroupName,
                        aliases = aliases,
                        tags = tags,
                        photoUri = photoUri,
                        isReturnable = returnable,
                        lowStockThreshold = threshold.toLongOrNull() ?: 0L,
                        initialQuantity = initialQuantity.toLongOrNull() ?: 0L,
                        repeatIssueWeeks = repeatIssueWeeks.toIntOrNull() ?: 0,
                        isHidden = hidden,
                    ),
                )
            },
            enabled = name.isNotBlank() && unit.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Zapisz dane przedmiotu")
        }
        if (product != null) {
            TextButton(onClick = { confirmRemoval = true }, colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)) {
                Icon(Icons.Default.DeleteOutline, null)
                Text("Usuń przedmiot")
            }
        }
        TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.End)) { Text("Anuluj") }
        Spacer(Modifier.height(16.dp))
    }
    if (confirmRemoval && product != null) {
        AlertDialog(
            onDismissRequest = { confirmRemoval = false },
            title = { Text("Usunąć przedmiot?") },
            text = { Text("Przedmiot zniknie z aktywnego magazynu i podpowiedzi. Jego wcześniejsze ruchy pozostaną w historii.") },
            confirmButton = { Button(onClick = { onRemove(product) }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { confirmRemoval = false }) { Text("Anuluj") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChoiceField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = {},
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EditableChoiceField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    options: List<String>,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = options.filter { value.isBlank() || it.contains(value, true) }.take(8)
    ExposedDropdownMenuBox(expanded = expanded && suggestions.isNotEmpty(), onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValueChange(it); expanded = true },
            modifier = Modifier.menuAnchor().fillMaxWidth().keepAboveKeyboard(),
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = expanded && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }) {
            suggestions.forEach { option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { onValueChange(option); expanded = false })
            }
        }
    }
}

@Composable
private fun ProductPhoto(uri: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = if (uri.isBlank()) null else withContext(Dispatchers.IO) {
            runCatching { context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use(BitmapFactory::decodeStream) }.getOrNull()
        }
    }
    Surface(modifier, shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        if (bitmap != null) Image(bitmap!!.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Icon(Icons.Default.Inventory2, null) }
    }
}
