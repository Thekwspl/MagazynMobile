package pl.magazyn.mobile.domain

import pl.magazyn.mobile.data.ProductEntity

data class ResolvedOperationItem<T>(
    val request: T,
    val product: ProductEntity,
)

fun <T> resolveAllOperationProducts(
    requests: List<T>,
    products: List<ProductEntity>,
    productId: (T) -> String,
): List<ResolvedOperationItem<T>>? {
    val productsById = products.associateBy(ProductEntity::id)
    val resolved = ArrayList<ResolvedOperationItem<T>>(requests.size)
    for (request in requests) {
        val product = productsById[productId(request)] ?: return null
        resolved += ResolvedOperationItem(request, product)
    }
    return resolved
}
