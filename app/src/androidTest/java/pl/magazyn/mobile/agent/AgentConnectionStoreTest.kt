package pl.magazyn.mobile.agent

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.data.AiKeyStore

@RunWith(AndroidJUnit4::class)
class AgentConnectionStoreTest {
    @Test fun encryptedCredentialCanBeRotatedAndRemovedWithoutTouchingGemini() {
        IsolatedApplicationEnvironment.create("agent-credential").use { environment ->
            val context = environment.application
            val ai = AiKeyStore(context)
            ai.saveApiKey("gemini-fixture")
            val store = AgentConnectionStore(context, false)
            val token = "fixture-secret-" + "a".repeat(64)
            store.save("https://agent.example.invalid", token)
            assertEquals("https://agent.example.invalid", store.status().endpoint)
            assertTrue(store.status().tokenSaved)
            assertFalse(store.status().toString().contains(token))
            val encrypted = context.getSharedPreferences("ai_secret_preferences", Context.MODE_PRIVATE)
            val endpoint = context.getSharedPreferences("agent_connection_preferences", Context.MODE_PRIVATE)
            assertFalse(encrypted.all.toString().contains(token))
            assertFalse(endpoint.all.toString().contains(token))
            assertEquals(token, ai.readAgentClientToken())
            store.save("https://agent.example.invalid", "new-" + token)
            assertEquals("new-" + token, ai.readAgentClientToken())
            store.clear()
            assertFalse(store.status().tokenSaved)
            assertEquals("", store.status().endpoint)
            assertNull(ai.readAgentClientToken())
            assertEquals("gemini-fixture", ai.readApiKey())
            assertFalse(encrypted.contains("agent_client_token_ciphertext"))
            assertFalse(endpoint.contains("endpoint"))
        }
    }
}
