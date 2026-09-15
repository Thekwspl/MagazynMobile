package pl.magazyn.mobile.data

import android.content.Context
import android.content.SharedPreferences
import java.util.WeakHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Lokalna preferencja widoku; nie zmienia żadnego produktu ani jego historii. */
class ProductVisibilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("product-visibility", Context.MODE_PRIVATE)
    private val sharedShowHidden = sharedStateFor(preferences)
    val showHidden: StateFlow<Boolean> = sharedShowHidden.asStateFlow()

    fun setShowHidden(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        sharedShowHidden.value = value
    }

    private companion object {
        const val KEY_SHOW_HIDDEN = "show-hidden-products"
        val sharedStates = WeakHashMap<SharedPreferences, MutableStateFlow<Boolean>>()

        fun sharedStateFor(preferences: SharedPreferences): MutableStateFlow<Boolean> = synchronized(sharedStates) {
            sharedStates.getOrPut(preferences) {
                MutableStateFlow(preferences.getBoolean(KEY_SHOW_HIDDEN, false))
            }
        }
    }
}
