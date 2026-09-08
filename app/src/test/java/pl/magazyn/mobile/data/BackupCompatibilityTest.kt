package pl.magazyn.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCompatibilityTest {
    @Test
    fun acceptsOlderAndCurrentDatabaseSchemas() {
        assertTrue(BackupCompatibility.isSupportedDatabaseVersion(1))
        assertTrue(BackupCompatibility.isSupportedDatabaseVersion(DATABASE_SCHEMA_VERSION - 1))
        assertTrue(BackupCompatibility.isSupportedDatabaseVersion(DATABASE_SCHEMA_VERSION))
        assertFalse(BackupCompatibility.isSupportedDatabaseVersion(0))
    }

    @Test
    fun rejectsOnlyActuallyNewerDatabaseSchema() {
        assertFalse(BackupCompatibility.isSupportedDatabaseVersion(DATABASE_SCHEMA_VERSION + 1))
    }

    @Test
    fun comparesLegacyVersionNamesNumericallyNotLexically() {
        assertTrue(BackupCompatibility.compareVersionNames("0.10.0", "0.9.13") > 0)
        assertTrue(BackupCompatibility.compareVersionNames("v0.9.13", "0.9.13") == 0)
        assertTrue(BackupCompatibility.compareVersionNames("0.9.9", "0.9.13") < 0)
        assertEquals(0, BackupCompatibility.compareVersionNames("1.2", "1.2.0"))
    }
}
