package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.magazyn.mobile.data.ProductEntity

class WarehouseOperationValidationTest {
    private data class Request(val productId: String, val quantity: Long)

    @Test
    fun resolvesEveryItemWhenAllProductsExist() {
        val requests = listOf(Request("first", 2), Request("second", 3))

        val result = resolveAllOperationProducts(
            requests,
            listOf(product("second"), product("first")),
            Request::productId,
        )

        assertEquals(listOf("first", "second"), result?.map { it.product.id })
        assertEquals(requests, result?.map { it.request })
    }

    @Test
    fun rejectsWholeListWhenFirstProductIsMissing() {
        val result = resolveAllOperationProducts(
            listOf(Request("missing", 1), Request("existing", 1)),
            listOf(product("existing")),
            Request::productId,
        )

        assertNull(result)
    }

    @Test
    fun rejectsWholeListWhenMiddleProductIsMissing() {
        val result = resolveAllOperationProducts(
            listOf(Request("first", 1), Request("missing", 1), Request("last", 1)),
            listOf(product("first"), product("last")),
            Request::productId,
        )

        assertNull(result)
    }

    @Test
    fun rejectsWholeListWhenLastProductIsMissing() {
        val result = resolveAllOperationProducts(
            listOf(Request("first", 1), Request("missing", 1)),
            listOf(product("first")),
            Request::productId,
        )

        assertNull(result)
    }

    @Test
    fun doesNotRejectExistingProductBecauseQuantityCanProduceNegativeStock() {
        val result = resolveAllOperationProducts(
            listOf(Request("existing", 100)),
            listOf(product("existing")),
            Request::productId,
        )

        assertTrue(result != null)
    }

    private fun product(id: String) = ProductEntity(
        id = id,
        name = "Produkt $id",
        unit = "szt.",
    )
}
