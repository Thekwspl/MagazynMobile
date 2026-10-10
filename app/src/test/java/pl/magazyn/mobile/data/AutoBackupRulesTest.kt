package pl.magazyn.mobile.data

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoBackupRulesTest {
    @Test
    fun manifestAndBothRuleFormatsProtectDataWithoutSecretsOrRestoreState() {
        val manifest = sourceFile("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").readText()
        val legacy = sourceFile("src/main/res/xml/backup_rules.xml", "app/src/main/res/xml/backup_rules.xml").readText()
        val modern = sourceFile("src/main/res/xml/data_extraction_rules.xml", "app/src/main/res/xml/data_extraction_rules.xml").readText()

        assertTrue(manifest.contains("android:allowBackup=\"true\""))
        assertTrue(manifest.contains("android:fullBackupContent=\"@xml/backup_rules\""))
        assertTrue(manifest.contains("android:dataExtractionRules=\"@xml/data_extraction_rules\""))
        listOf(legacy, modern).forEach { rules ->
            assertTrue(rules.contains("<include domain=\"database\" path=\"magazyn-mobile.db\""))
            assertTrue(rules.contains("<include domain=\"sharedpref\" path=\"product-visibility.xml\""))
            assertTrue(rules.contains("<include domain=\"sharedpref\" path=\"updates.xml\""))
            assertTrue(rules.contains("<include domain=\"sharedpref\" path=\"ai_preferences.xml\""))
            assertFalse(rules.contains("<exclude"))
            assertFalse(rules.contains("ai_secret_preferences.xml"))
            assertFalse(rules.contains("agent_connection_preferences.xml"))
            assertFalse(rules.contains("ai_secure_preferences.xml"))
            assertFalse(rules.contains("restore-journal.xml"))
            assertFalse(rules.contains("startup-diagnostics.xml"))
            assertFalse(rules.contains("restore-rollback/"))
        }
        assertTrue(modern.contains("<cloud-backup>"))
        assertTrue(modern.contains("<device-transfer>"))
    }

    private fun sourceFile(vararg candidates: String): File = candidates.asSequence()
        .map(::File)
        .firstOrNull(File::isFile)
        ?: error("Nie znaleziono pliku źródłowego: ${candidates.joinToString()}")
}
