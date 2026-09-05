package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstNameNormalizationTest {
    @Test fun expandsCommonPolishShortNames() {
        assertEquals("Krzysztof", normalizeFirstName("Krzyś"))
        assertEquals("Grzegorz", normalizeFirstName("grzechu"))
        assertEquals("Jakub", normalizeFirstName("Kuba"))
        assertEquals("Arkadiusz", normalizeFirstName("Arek"))
    }

    @Test fun keepsAlreadyFullName() {
        assertEquals("Łukasz", normalizeFirstName("łukasz"))
    }
}
