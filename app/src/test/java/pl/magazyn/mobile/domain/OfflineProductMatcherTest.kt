package pl.magazyn.mobile.domain

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineProductMatcherTest {
    @Test
    fun exactVariantHasPriorityOverVariantTag() {
        val exact = offlineProductMatchScore("Bluza monterska", "52", "Bluza monterska", "52", "", "")!!
        val tagged = offlineProductMatchScore("Bluza monterska", "52", "Bluza monterska", "XXL", "", "52")!!

        assertTrue(exact > tagged)
    }

    @Test
    fun configuredSizeAliasCanResolveVariant() {
        val score = offlineProductMatchScore("Kurtka", "2XL", "Kurtka", "XXL", "", "2XL")

        assertTrue(score != null)
    }

    @Test
    fun differentNumericSizeIsNotSelectedAsNearestVariant() {
        val score = offlineProductMatchScore("Bluza monterska", "52", "Bluza monterska", "54", "", "")

        assertNull(score)
    }
}
