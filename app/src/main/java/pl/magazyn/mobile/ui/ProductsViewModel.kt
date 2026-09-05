package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.ProductCategoryEntity
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.ProductGroupEntity
import pl.magazyn.mobile.data.ProductSubgroupEntity
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.StockMovementEntity
import pl.magazyn.mobile.data.StockMovementLineEntity
import pl.magazyn.mobile.domain.StockMath
import pl.magazyn.mobile.domain.normalizeCommaSeparated
import pl.magazyn.mobile.domain.normalizeDisplayName

data class ProductDraft(
    val name: String,
    val variant: String,
    val unit: String,
    val category: String,
    val groupName: String,
    val subgroupName: String,
    val aliases: String,
    val tags: String,
    val photoUri: String,
    val isReturnable: Boolean,
    val lowStockThreshold: Long,
    val initialQuantity: Long,
    val repeatIssueWeeks: Int,
)

class ProductsViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    val products = database.productDao().observeWithStock("warehouse-main")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val groups = database.productDictionaryDao().observeGroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val subgroups = database.productDictionaryDao().observeSubgroups()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val categories = database.productDictionaryDao().observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun saveProduct(existing: ProductWithStock?, draft: ProductDraft) {
        val normalizedName = normalizeDisplayName(draft.name)
        if (normalizedName.isBlank() || draft.unit.isBlank()) return
        viewModelScope.launch {
            database.withTransaction {
                val productId = existing?.id ?: UUID.randomUUID().toString()
                val group = normalizeDisplayName(draft.groupName)
                val subgroup = normalizeDisplayName(draft.subgroupName)
                val category = normalizeDisplayName(draft.category)
                val item = ProductEntity(
                    id = productId,
                    name = normalizedName,
                    variant = draft.variant.trim().ifBlank { null },
                    unit = draft.unit.trim(),
                    category = category,
                    groupName = group,
                    subgroupName = subgroup,
                    aliases = normalizeCommaSeparated(draft.aliases),
                    tags = normalizeCommaSeparated(draft.tags),
                    photoUri = draft.photoUri,
                    isReturnable = draft.isReturnable,
                    lowStockThreshold = draft.lowStockThreshold.toDouble(),
                    repeatIssueWeeks = draft.repeatIssueWeeks.coerceAtLeast(0),
                )
                if (existing == null) database.productDao().insert(item) else database.productDao().update(item)
                saveDictionaryValues(group, subgroup, category)

                if (existing == null) {
                    database.stockDao().upsert(
                        listOf(StockBalanceEntity("warehouse-main", productId, draft.initialQuantity.toDouble())),
                    )
                    val movementId = UUID.randomUUID().toString()
                    database.movementDao().insertMovement(
                        StockMovementEntity(
                            id = movementId,
                            type = "INITIAL_STOCK",
                            warehouseId = "warehouse-main",
                            employeeId = null,
                            effectiveDate = LocalDate.now().toString(),
                            createdAtEpochMillis = System.currentTimeMillis(),
                            note = "Stan przy utworzeniu przedmiotu",
                        ),
                    )
                    database.movementDao().insertLine(
                        StockMovementLineEntity(
                            id = UUID.randomUUID().toString(),
                            movementId = movementId,
                            productId = productId,
                            quantityDelta = draft.initialQuantity.toDouble(),
                            unit = draft.unit.trim(),
                        ),
                    )
                }
            }
        }
    }

    fun correctStock(product: ProductWithStock, countedQuantity: Long) {
        viewModelScope.launch {
            database.withTransaction {
                val movementId = UUID.randomUUID().toString()
                val counted = countedQuantity.toDouble()
                val delta = StockMath.correctionDelta(product.stockQuantity, counted)
                database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", product.id, counted)))
                database.movementDao().insertMovement(
                    StockMovementEntity(
                        id = movementId,
                        type = "CORRECTION",
                        warehouseId = "warehouse-main",
                        employeeId = null,
                        effectiveDate = LocalDate.now().toString(),
                        createdAtEpochMillis = System.currentTimeMillis(),
                        note = "",
                    ),
                )
                database.movementDao().insertLine(
                    StockMovementLineEntity(
                        id = UUID.randomUUID().toString(),
                        movementId = movementId,
                        productId = product.id,
                        quantityDelta = delta,
                        unit = product.unit,
                    ),
                )
            }
        }
    }

    fun removeProduct(productId: String) {
        viewModelScope.launch { database.productDao().archive(productId) }
    }

    private suspend fun saveDictionaryValues(group: String, subgroup: String, category: String) {
        if (group.isNotBlank()) {
            database.productDictionaryDao().insertGroup(ProductGroupEntity(dictionaryId("group", group), group))
        }
        if (subgroup.isNotBlank()) {
            database.productDictionaryDao().insertSubgroup(
                ProductSubgroupEntity(dictionaryId("subgroup", "$group|$subgroup"), subgroup, group),
            )
        }
        if (category.isNotBlank()) {
            database.productDictionaryDao().insertCategory(ProductCategoryEntity(dictionaryId("category", category), category))
        }
    }

    private fun dictionaryId(prefix: String, value: String): String = prefix + "-" + value.lowercase()
        .replace(Regex("[^a-ząćęłńóśźż0-9]+"), "-")
        .trim('-')
}
