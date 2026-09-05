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
    viewModel: ProductsViewModel = viewModel(),
) {
    val products by viewModel.products.collectAsStateWithLifecycle()
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val subgroups by viewModel.subgroups.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var edited by remember { mutableStateOf<ProductWithStock?>(null) }
    var initialProductOpened by rememberSaveable(initialProductId) { mutableStateOf(false) }
    var showNew by rememberSaveable { mutableStateOf(startAdding) }
    val listState = rememberLazyListState()
    val editorSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val searchTokens = ImportParser.key(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val visible = products.filter { product ->
        searchTokens.isEmpty() || matchesSearch(
            query, product.name, product.variant.orEmpty(), product.aliases, product.tags, product.category, product.groupName, product.subgroupName,
        )
    }
    LaunchedEffect(initialProductId, products) {
        if (initialProductId != null && !initialProductOpened) {
            products.firstOrNull { it.id == initialProductId }?.let {
                edited = it
                initialProductOpened = true
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        ScreenHeader("Przedmioty", "Dodaj", { showNew = true })
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp), label = { Text("Szukaj po nazwie, aliasie lub tagu") }, singleLine = true)
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(visible, key = { it.id }) { product ->
                OutlinedCard(onClick = { edited = product }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProductPhoto(product.photoUri, Modifier.size(58.dp))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(listOfNotNull(product.name, product.variant).joinToString(" · "), fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(product.groupName, product.subgroupName, product.category, product.unit).filter(String::isNotBlank).joinToString(" · "),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(if (product.stockKnown) formatWholeQuantity(product.stockQuantity) else "?", style = MaterialTheme.typography.titleMedium)
                            Text(product.unit, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }
    }
    if (showNew || edited != null) {
        ModalBottomSheet(sheetState = editorSheetState, onDismissRequest = { showNew = false; edited = null }) {
            ProductEditor(
                product = edited,
                groups = groups,
                subgroups = subgroups,
                categories = categories,
                units = (listOf("szt.", "para", "opak.", "paczka", "kpl.", "metr", "rolka") + products.map { it.unit })
                    .filter(String::isNotBlank).distinctBy { it.lowercase() },
                onCancel = { showNew = false; edited = null },
                onSave = { existing, draft ->
                    viewModel.saveProduct(existing, draft)
                    showNew = false; edited = null
                },
                onCorrectStock = { product, counted ->
                    viewModel.correctStock(product, counted)
                    showNew = false; edited = null
                },
                onRemove = { product ->
                    viewModel.removeProduct(product.id)
                    showNew = false; edited = null
                },
            )
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
        Text(if (product == null) "Nowy przedmiot" else "Karta przedmiotu", style = MaterialTheme.typography.titleLarge)
        if (product != null) {
            Text("Stan magazynowy", style = MaterialTheme.typography.titleMedium)
            Text(if (product.stockKnown) "Aktualnie: ${formatWholeQuantity(product.stockQuantity)} ${product.unit}" else "Aktualnie: stan nieustalony")
            OutlinedTextField(
                counted,
                { value -> counted = value.filterIndexed { index, character -> character.isDigit() || (character == '-' && index == 0) } },
                Modifier.fillMaxWidth().keepAboveKeyboard(),
                label = { Text("Faktycznie policzony stan") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(onClick = { counted.toLongOrNull()?.let { onCorrectStock(product, it) } }, enabled = counted.toLongOrNull() != null, modifier = Modifier.fillMaxWidth()) {
                Text("Zapisz korektę stanu")
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
        EditableChoiceField(groupName, { groupName = it }, "Grupa", groups)
        EditableChoiceField(
            subgroupName,
            { subgroupName = it },
            "Podgrupa",
            subgroups.filter { groupName.isBlank() || it.groupName.isBlank() || it.groupName.equals(groupName, true) }.map { it.name }.distinct(),
        )
        EditableChoiceField(category, { category = it }, "Kategoria", categories)
        OutlinedTextField(aliases, { aliases = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Aliasy, oddzielone przecinkami") })
        OutlinedTextField(tags, { tags = it }, Modifier.fillMaxWidth().keepAboveKeyboard(), label = { Text("Tagi, oddzielone przecinkami") })
        OutlinedTextField(
            threshold,
            { value -> threshold = value.filter(Char::isDigit) },
            Modifier.fillMaxWidth().keepAboveKeyboard(),
            label = { Text("Próg niskiego stanu") },
            placeholder = { Text("0") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        OutlinedTextField(
            repeatIssueWeeks,
            { value -> repeatIssueWeeks = value.filter(Char::isDigit) },
            Modifier.fillMaxWidth().keepAboveKeyboard(),
            label = { Text("Ponowne wydanie po (tygodnie)") },
            placeholder = { Text("0") },
            supportingText = { Text("0 oznacza brak ograniczenia") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(returnable, { returnable = it })
            Text("Sprzęt powierzony — wymaga zwrotu", Modifier.padding(start = 8.dp))
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
) {
    var expanded by remember { mutableStateOf(false) }
    val suggestions = options.filter { value.isBlank() || it.contains(value, true) }.take(8)
    ExposedDropdownMenuBox(expanded = expanded && suggestions.isNotEmpty(), onExpandedChange = { expanded = it }) {
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
