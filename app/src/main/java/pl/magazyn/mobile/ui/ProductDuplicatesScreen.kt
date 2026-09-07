package pl.magazyn.mobile.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.room.withTransaction
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.ProductDuplicateDecisionEntity
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.ShipyardStockBalanceEntity
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.domain.isPotentialProductDuplicate
import pl.magazyn.mobile.domain.productDuplicateKey
import pl.magazyn.mobile.domain.normalizeCommaSeparated
import pl.magazyn.mobile.domain.combinedProductQuantity

data class ProductDuplicateGroup(val signature: String, val products: List<ProductWithStock>)

class ProductDuplicatesViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val groups = combine(
        database.productDao().observeWithStock("warehouse-main"),
        database.productMergeDao().observeDecisions(),
    ) { products, decisions ->
        val ignored = decisions.filter { it.decision in setOf("KEEP_SEPARATE", "SKIP") }.map { it.signature }.toSet()
        products.groupBy { productDuplicateKey(it.name, it.variant).signature }
            .filter { (signature, group) ->
                signature !in ignored && group.size > 1 && group.indices.any { left ->
                    ((left + 1) until group.size).any { right ->
                        val a = group[left]; val b = group[right]
                        isPotentialProductDuplicate(a.name, a.variant, a.groupName, a.subgroupName, a.category, b.name, b.variant, b.groupName, b.subgroupName, b.category)
                    }
                }
            }
            .map { ProductDuplicateGroup(it.key, it.value) }
            .sortedBy { it.products.first().name }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun merge(group: ProductDuplicateGroup, targetId: String) = viewModelScope.launch {
        database.withTransaction {
            val mergeDao = database.productMergeDao()
            val targetProduct = database.productDao().findById(targetId) ?: return@withTransaction
            val sources = group.products.filter { it.id != targetId }
            sources.forEach { source ->
                mergeDao.stockBalances(source.id).forEach { sourceBalance ->
                    val target = database.stockDao().find(sourceBalance.warehouseId, targetId)
                    database.stockDao().upsert(listOf(StockBalanceEntity(sourceBalance.warehouseId, targetId, combinedProductQuantity(target?.quantity ?: 0.0, listOf(sourceBalance.quantity)), (target?.isKnown == true) || sourceBalance.isKnown)))
                }
                mergeDao.shipyardBalances(source.id).forEach { sourceBalance ->
                    val target = database.shipyardDao().findStock(sourceBalance.shipyardId, targetId)
                    database.shipyardDao().upsertStock(ShipyardStockBalanceEntity(sourceBalance.shipyardId, targetId, combinedProductQuantity(target?.quantity ?: 0.0, listOf(sourceBalance.quantity))))
                }
                mergeDao.deleteStockBalances(source.id)
                mergeDao.deleteShipyardBalances(source.id)
                mergeDao.moveMovementLines(source.id, targetId)
                mergeDao.moveAmendments(source.id, targetId)
                mergeDao.moveCustodies(source.id, targetId)
                mergeDao.moveOrderLines(source.id, targetId)
                mergeDao.moveTaskLinks(source.id, targetId)
                mergeDao.archiveMergedProduct(source.id)
            }
            database.productDao().update(
                targetProduct.copy(
                    aliases = normalizeCommaSeparated((listOf(targetProduct.aliases) + sources.flatMap { listOf(it.name, it.aliases) }).joinToString(", ")),
                    tags = normalizeCommaSeparated((listOf(targetProduct.tags) + sources.map { it.tags }).joinToString(", ")),
                    photoUri = targetProduct.photoUri.ifBlank { sources.firstNotNullOfOrNull { it.photoUri.takeIf(String::isNotBlank) }.orEmpty() },
                    isReturnable = targetProduct.isReturnable || sources.any { it.isReturnable },
                    lowStockThreshold = maxOf(targetProduct.lowStockThreshold, sources.maxOfOrNull { it.lowStockThreshold } ?: 0.0),
                    repeatIssueWeeks = maxOf(targetProduct.repeatIssueWeeks, sources.maxOfOrNull { it.repeatIssueWeeks } ?: 0),
                ),
            )
            mergeDao.saveDecision(ProductDuplicateDecisionEntity(UUID.randomUUID().toString(), group.signature, "MERGED", System.currentTimeMillis()))
        }
    }

    fun decide(group: ProductDuplicateGroup, decision: String) = viewModelScope.launch {
        database.productMergeDao().saveDecision(ProductDuplicateDecisionEntity(UUID.randomUUID().toString(), group.signature, decision, System.currentTimeMillis()))
    }
}

@Composable
fun ProductDuplicatesScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onEditProduct: (String) -> Unit,
    viewModel: ProductDuplicatesViewModel = viewModel(),
) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    var merging by remember { mutableStateOf<ProductDuplicateGroup?>(null) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Wróć") }
            Column {
                Text("Możliwe duplikaty", style = MaterialTheme.typography.headlineSmall)
                Text("Ta sama nazwa i wariant, ale inne dane klasyfikacji", style = MaterialTheme.typography.bodySmall)
            }
        }
        HorizontalDivider()
        if (groups.isEmpty()) {
            Text("Brak nierozstrzygniętych duplikatów.", Modifier.padding(20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(groups, key = { it.signature }) { group ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ProductInfo(group.products.first().name, group.products.first().variant, group.products.first().groupName, group.products.first().subgroupName)
                        group.products.forEach { product ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(listOf(product.groupName, product.subgroupName, product.category).filter(String::isNotBlank).joinToString(" • ").ifBlank { "Bez klasyfikacji" }, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = { onEditProduct(product.id) }) { Text("Edytuj") }
                            }
                        }
                        Button(onClick = { merging = group }, Modifier.fillMaxWidth()) { Text("Scal pozycje") }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            TextButton(onClick = { viewModel.decide(group, "KEEP_SEPARATE") }) { Text("Zostaw jako osobne") }
                            TextButton(onClick = { viewModel.decide(group, "SKIP") }) { Text("Pomiń na razie") }
                        }
                    }
                }
            }
        }
    }
    merging?.let { group ->
        AlertDialog(
            onDismissRequest = { merging = null },
            title = { Text("Wybierz rekord główny") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Stany i wszystkie powiązania zostaną przeniesione do wybranej pozycji.")
                    group.products.forEach { product ->
                        OutlinedButton(onClick = { viewModel.merge(group, product.id); merging = null }, Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(product.name + product.variant?.let { "  $it" }.orEmpty(), fontWeight = FontWeight.SemiBold)
                                Text(listOf(product.groupName, product.subgroupName, product.category).filter(String::isNotBlank).joinToString(" • "), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { merging = null }) { Text("Anuluj") } },
        )
    }
}
