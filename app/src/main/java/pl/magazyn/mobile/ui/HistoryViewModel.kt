package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database

    val entries = database.movementDao().observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyardLeaders = database.shipyardDao().observeAllLeaderLinks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun assignShipyard(movementId: String, shipyardId: String) {
        viewModelScope.launch {
            if (database.shipyardDao().findActive(shipyardId) != null)
                database.movementDao().assignShipyard(movementId, shipyardId)
        }
    }

    fun lines(movementId: String) = database.movementDao().observeHistoryLines(movementId)
}
