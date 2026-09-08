package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.ProductVisibilityStore

class SearchViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database
    private val visibility = ProductVisibilityStore(application)
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val products = combine(database.productDao().observeAllWithStock("warehouse-main"), visibility.showHidden) { products, showHidden -> if (showHidden) products else products.filterNot { it.isHidden } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
