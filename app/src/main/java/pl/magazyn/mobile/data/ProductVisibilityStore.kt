package pl.magazyn.mobile.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Lokalna preferencja widoku; nie zmienia żadnego produktu ani jego historii. */
class ProductVisibilityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("product-visibility", Context.MODE_PRIVATE)
    private val _showHidden = MutableStateFlow(preferences.getBoolean(KEY_SHOW_HIDDEN, false))
    val showHidden: StateFlow<Boolean> = _showHidden

    fun setShowHidden(value: Boolean) {
        preferences.edit().putBoolean(KEY_SHOW_HIDDEN, value).apply()
        _showHidden.value = value
    }

    private companion object { const val KEY_SHOW_HIDDEN = "show-hidden-products" }
}
