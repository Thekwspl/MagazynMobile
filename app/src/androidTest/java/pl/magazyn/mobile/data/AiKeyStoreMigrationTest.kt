package pl.magazyn.mobile.data

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment

@RunWith(AndroidJUnit4::class)
class AiKeyStoreMigrationTest {
    @Test
    fun legacySecretIsMovedOutOfBackupEligibleSettingsWithoutLosingValue() {
        IsolatedApplicationEnvironment.create("ai-secret-migration").use { environment ->
            val context = environment.application
            context.getSharedPreferences("ai_secure_preferences", Context.MODE_PRIVATE).edit()
                .putString("api_key_ciphertext", "synthetic-ciphertext")
                .putString("api_key_iv", "synthetic-iv")
                .putBoolean("redact_phone_numbers", false)
                .commit()

            AiKeyStore(context)

            val secrets = context.getSharedPreferences("ai_secret_preferences", Context.MODE_PRIVATE)
            val settings = context.getSharedPreferences("ai_preferences", Context.MODE_PRIVATE)
            val legacy = context.getSharedPreferences("ai_secure_preferences", Context.MODE_PRIVATE)
            assertEquals("synthetic-ciphertext", secrets.getString("api_key_ciphertext", null))
            assertEquals("synthetic-iv", secrets.getString("api_key_iv", null))
            assertFalse(settings.getBoolean("redact_phone_numbers", true))
            assertTrue(legacy.all.isEmpty())
        }
    }
}
