package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import pl.magazyn.mobile.MagazynApplication

class HistoryViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database

    val entries = database.movementDao().observeHistory()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun lines(movementId: String) = database.movementDao().observeHistoryLines(movementId)
}
