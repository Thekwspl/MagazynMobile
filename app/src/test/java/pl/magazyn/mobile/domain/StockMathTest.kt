package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class StockMathTest {
    @Test
    fun correctionCanMoveNegativeStockToCountedValue() {
        assertEquals(7.0, StockMath.correctionDelta(-2.0, 5.0), 0.0)
    }

    @Test
    fun issueMayCreateNegativeStock() {
        assertEquals(-3.0, StockMath.afterIssue(2.0, 5.0), 0.0)
    }
}
