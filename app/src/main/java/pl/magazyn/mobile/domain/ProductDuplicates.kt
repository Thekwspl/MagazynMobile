package pl.magazyn.mobile.domain

data class ProductDuplicateKey(val normalizedName: String, val normalizedVariant: String) {
    val signature: String get() = "$normalizedName|$normalizedVariant"
}

fun productDuplicateKey(name: String, variant: String?): ProductDuplicateKey = ProductDuplicateKey(
    normalizedName = name.trim().replace(Regex("\\s+"), " ").lowercase(),
    normalizedVariant = variant.orEmpty().trim().replace(Regex("\\s+"), " ").lowercase(),
)

fun isPotentialProductDuplicate(
    firstName: String,
    firstVariant: String?,
    firstGroup: String,
    firstSubgroup: String,
    firstCategory: String,
    secondName: String,
    secondVariant: String?,
    secondGroup: String,
    secondSubgroup: String,
    secondCategory: String,
): Boolean {
    if (productDuplicateKey(firstName, firstVariant) != productDuplicateKey(secondName, secondVariant)) return false
    fun metadata(group: String, subgroup: String, category: String) = listOf(group, subgroup, category)
        .map { it.trim().replace(Regex("\\s+"), " ").lowercase() }
    return metadata(firstGroup, firstSubgroup, firstCategory) != metadata(secondGroup, secondSubgroup, secondCategory)
}

object TaskCompletionPolicy {
    fun shouldCompleteStep(personStatuses: List<Boolean>): Boolean = personStatuses.isNotEmpty() && personStatuses.all { it }
    fun shouldCompleteTask(stepStatuses: List<Boolean>): Boolean = stepStatuses.isNotEmpty() && stepStatuses.all { it }
}

/** Wszystkie relacje produktu, które muszą zostać przepięte podczas scalenia. */
val PRODUCT_MERGE_RELATIONS = setOf(
    "stock_balances",
    "shipyard_stock_balances",
    "stock_movement_lines",
    "issue_amendments",
    "custodies",
    "order_lines",
    "notebook_tasks",
)

fun combinedProductQuantity(targetQuantity: Double, sourceQuantities: Iterable<Double>): Double =
    sourceQuantities.fold(targetQuantity, Double::plus)
