package pl.magazyn.mobile.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class AiKeyStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun saveApiKey(value: String) {
        val clean = value.trim()
        require(clean.isNotEmpty()) { "Klucz API nie może być pusty" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey())
        val encrypted = cipher.doFinal(clean.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun readApiKey(): String? = runCatching {
        val encrypted = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            getOrCreateSecretKey(),
            GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)),
        )
        cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)).toString(Charsets.UTF_8)
    }.getOrNull()

    fun hasApiKey(): Boolean = readApiKey()?.isNotBlank() == true

    fun clearApiKey() {
        preferences.edit().remove(KEY_CIPHERTEXT).remove(KEY_IV).apply()
    }

    var redactPhoneNumbers: Boolean
        get() = preferences.getBoolean(KEY_REDACT_PHONES, true)
        set(value) { preferences.edit().putBoolean(KEY_REDACT_PHONES, value).apply() }

    fun recordConnection(success: Boolean, model: String, message: String) {
        preferences.edit()
            .putLong(KEY_LAST_ATTEMPT, System.currentTimeMillis())
            .putBoolean(KEY_LAST_SUCCESS, success)
            .putString(KEY_LAST_MODEL, model)
            .putString(KEY_LAST_MESSAGE, message.take(300))
            .apply()
    }

    fun lastConnection(): AiConnectionStatus? {
        val time = preferences.getLong(KEY_LAST_ATTEMPT, 0L)
        if (time == 0L) return null
        return AiConnectionStatus(
            time, preferences.getBoolean(KEY_LAST_SUCCESS, false),
            preferences.getString(KEY_LAST_MODEL, "").orEmpty(),
            preferences.getString(KEY_LAST_MESSAGE, "").orEmpty(),
        )
    }

    private fun getOrCreateSecretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "ai_secure_preferences"
        const val KEY_ALIAS = "magazyn_mobile_gemini_key"
        const val KEY_CIPHERTEXT = "api_key_ciphertext"
        const val KEY_IV = "api_key_iv"
        const val KEY_REDACT_PHONES = "redact_phone_numbers"
        const val KEY_LAST_ATTEMPT = "last_attempt"
        const val KEY_LAST_SUCCESS = "last_success"
        const val KEY_LAST_MODEL = "last_model"
        const val KEY_LAST_MESSAGE = "last_message"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

data class AiConnectionStatus(val atEpochMillis: Long, val success: Boolean, val model: String, val message: String)
