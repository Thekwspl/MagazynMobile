package pl.magazyn.mobile.domain

import org.junit.Assert.assertTrue
import org.junit.Test

class SearchMatcherTest {
    @Test
    fun findsProductAfterVariantWasMovedOutOfName() {
        assertTrue(matchesSearch("des", "Deszczówka Góra", "50/M"))
        assertTrue(matchesSearch("des 50/m", "Deszczówka Góra", "50/M"))
    }

    @Test
    fun ignoresInvisibleSeparatorInsideImportedName() {
        assertTrue(matchesSearch("des", "De\u200Bszczówka Góra", "50/M"))
    }

    @Test
    fun findsDifferentRainwearNamesAndVariants() {
        val products = listOf(
            listOf("Deszczówka Góra", "50/M"),
            listOf("Deszczówka Dół", "56/L"),
            listOf("Kurtka przeciwdeszczowa", "XL"),
        )
        products.forEach { fields ->
            assertTrue(matchesSearch("des", *fields.toTypedArray()))
        }
        assertTrue(matchesSearch("des 50/m", "Deszczówka Góra", "50/M"))
    }

    @Test
    fun toleratesOneCorruptedCharacterInImportedRainwearName() {
        assertTrue(matchesSearch("des", "Dszczówka Góra", "50/M"))
        assertTrue(matchesSearch("deszczowka", "Deszczowka Góra", "50/M"))
    }

    @Test
    fun findsEveryRainwearVariantFromCurrentSpreadsheetAfterVariantSeparation() {
        listOf("50/M", "52/L", "54/L", "56/XL", "58/XL", "60/2XL", "62/3XL", "64/4XL").forEach { variant ->
            assertTrue("wariant $variant", matchesSearch("des", "Deszczówka Góra", variant))
            assertTrue("wariant $variant", matchesSearch("des $variant", "Deszczówka Komplet", variant))
        }
    }
}
