package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment

@RunWith(AndroidJUnit4::class)
class ProductVisibilityStoreTest {
    @Test
    fun settingChangeIsImmediatelyVisibleToEveryStoreInstance() {
        IsolatedApplicationEnvironment.create().use { environment ->
            val settingsStore = ProductVisibilityStore(environment.application)
            val productsStore = ProductVisibilityStore(environment.application)

            assertFalse(settingsStore.showHidden.value)
            assertFalse(productsStore.showHidden.value)

            settingsStore.setShowHidden(true)

            assertTrue(settingsStore.showHidden.value)
            assertTrue(productsStore.showHidden.value)
        }
    }
}
