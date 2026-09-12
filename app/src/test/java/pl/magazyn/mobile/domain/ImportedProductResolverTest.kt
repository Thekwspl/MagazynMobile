package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.magazyn.mobile.data.ProductEntity

class ImportedProductResolverTest {
    private val size52 = product("52", "Bluza Monterska", "52")
    private val size54 = product("54", "Bluza Monterska", "54")

    @Test
    fun sameNameWithExactVariantSelectsOnlyThatVariant() {
        val result = ImportParser.resolveImportedProduct("Bluza Monterska / 52", listOf(size54, size52))

        assertEquals("52", (result as ImportedProductResolution.Matched).product.id)
    }

    @Test
    fun sameNameWithSecondExactVariantSelectsSecondVariant() {
        val result = ImportParser.resolveImportedProduct("Bluza Monterska 54", listOf(size52, size54))

        assertEquals("54", (result as ImportedProductResolution.Matched).product.id)
    }

    @Test
    fun nameWithoutVariantIsAmbiguousWhenSeveralVariantsExist() {
        val result = ImportParser.resolveImportedProduct("Bluza Monterska", listOf(size52, size54))

        assertTrue(result is ImportedProductResolution.Ambiguous)
    }

    @Test
    fun unknownExplicitVariantDoesNotSelectAnotherVariant() {
        val result = ImportParser.resolveImportedProduct("Bluza Monterska 56", listOf(size52, size54))

        assertSame(ImportedProductResolution.NotFound, result)
    }

    @Test
    fun uniqueProductWithoutVariantIsMatched() {
        val helmet = product("helmet", "Kask Biały", null)

        val result = ImportParser.resolveImportedProduct("  KASK  BIAŁY ", listOf(helmet))

        assertEquals("helmet", (result as ImportedProductResolution.Matched).product.id)
    }

    @Test
    fun uniqueAliasCanBeCombinedWithExactVariant() {
        val aliased = size52.copy(aliases = "BM")

        val result = ImportParser.resolveImportedProduct("bm 52", listOf(size54, aliased))

        assertEquals("52", (result as ImportedProductResolution.Matched).product.id)
    }

    @Test
    fun uniqueAliasMatchesItsProduct() {
        val helmet = product("helmet", "Kask Biały", null).copy(aliases = "kask")

        val result = ImportParser.resolveImportedProduct("KASK", listOf(size52, helmet))

        assertEquals("helmet", (result as ImportedProductResolution.Matched).product.id)
    }

    @Test
    fun sharedAliasIsAmbiguous() {
        val result = ImportParser.resolveImportedProduct(
            "monterska",
            listOf(size52.copy(aliases = "monterska"), size54.copy(aliases = "MONTERSKA")),
        )

        assertTrue(result is ImportedProductResolution.Ambiguous)
    }

    @Test
    fun aliasCannotOverrideDifferentExplicitVariant() {
        val incorrectlyAliased = size52.copy(aliases = "Bluza Monterska 56")

        val result = ImportParser.resolveImportedProduct("Bluza Monterska 56", listOf(incorrectlyAliased, size54))

        assertSame(ImportedProductResolution.NotFound, result)
    }

    @Test
    fun duplicateExactNameAndVariantIsAmbiguous() {
        val duplicate = size52.copy(id = "52-duplicate")

        val result = ImportParser.resolveImportedProduct("Bluza Monterska 52", listOf(size52, duplicate))

        assertTrue(result is ImportedProductResolution.Ambiguous)
    }

    @Test
    fun stockProductTextResolvesToCorrectVariantRegardlessOfDatabaseOrder() {
        val thin = product("thin", "Marker Biały", "Cienki")
        val thick = product("thick", "Marker Biały", "Gruby")

        val result = ImportParser.resolveImportedProduct("Marker Biały / Gruby", listOf(thin, thick))

        assertEquals("thick", (result as ImportedProductResolution.Matched).product.id)
    }

    private fun product(id: String, name: String, variant: String?) = ProductEntity(
        id = id,
        name = name,
        variant = variant,
        unit = "szt.",
    )
}
