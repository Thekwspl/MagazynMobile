package pl.magazyn.mobile.domain

import org.junit.Assert.*
import org.junit.Test

class ProductDuplicatesTest {
    @Test fun normalizesCaseAndRedundantSpacesWithoutFuzzyMatching() {
        assertEquals(productDuplicateKey("  Bluza   Monterska ", " 52 "), productDuplicateKey("bluza monterska", "52"))
        assertNotEquals(productDuplicateKey("Bluza monterska", "52"), productDuplicateKey("Bluza monterksa", "52"))
    }

    @Test fun detectsOnlyDifferentClassification() {
        assertTrue(isPotentialProductDuplicate("Bluza Monterska", "52", "Monterskie", "Bluza", "Ubranie", "bluza monterska", "52", "Odzież", "Robocza", "Ubranie"))
        assertFalse(isPotentialProductDuplicate("Bluza Monterska", "52", "Monterskie", "Bluza", "Ubranie", "bluza monterska", "52", "Monterskie", "Bluza", "Ubranie"))
    }

    @Test fun mergePlanIncludesEveryCurrentProductRelation() {
        assertEquals(setOf("stock_balances", "shipyard_stock_balances", "stock_movement_lines", "issue_amendments", "custodies", "order_lines", "notebook_tasks"), PRODUCT_MERGE_RELATIONS)
    }

    @Test fun mergeAddsMainAndShipyardStockInsteadOfDroppingIt() {
        assertEquals(12.0, combinedProductQuantity(5.0, listOf(3.0, 4.0)), 0.0)
        assertEquals(-1.0, combinedProductQuantity(2.0, listOf(-3.0)), 0.0)
    }

    @Test fun completionCanBeAppliedAndReverted() {
        assertTrue(TaskCompletionPolicy.shouldCompleteStep(listOf(true, true)))
        assertFalse(TaskCompletionPolicy.shouldCompleteStep(listOf(true, false)))
        assertFalse(TaskCompletionPolicy.shouldCompleteStep(emptyList()))
        assertTrue(TaskCompletionPolicy.shouldCompleteTask(listOf(true, true)))
        assertFalse(TaskCompletionPolicy.shouldCompleteTask(listOf(true, false)))
    }
}
