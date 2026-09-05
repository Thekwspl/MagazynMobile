package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.NotebookTaskEntity
import pl.magazyn.mobile.data.OrderNotebookEntity

class TasksViewModel(application: Application) : AndroidViewModel(application) {
    private val database = (application as MagazynApplication).database

    val tasks = database.notebookDao().observeTasks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val people = database.employeeDao().observeSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val products = database.productDao().observeWithStock("warehouse-main")
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val shipyards = database.shipyardDao().observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val orders = database.orderDao().observeActiveSummaries()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createTask(text: String, dueDate: String?, priority: String, employeeId: String?, shipyardId: String?, productId: String?, orderId: String?) {
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val notebookId = UUID.randomUUID().toString()
            database.notebookDao().insertNotebook(
                OrderNotebookEntity(notebookId, clean, "ACTIVE", "TASK", System.currentTimeMillis()),
            )
            database.notebookDao().insertTasks(
                listOf(NotebookTaskEntity(UUID.randomUUID().toString(), notebookId, clean, false, 0, dueDate, priority, employeeId, shipyardId, productId, orderId)),
            )
        }
    }

    fun updateTask(id: String, text: String, dueDate: String?, priority: String, employeeId: String?, shipyardId: String?, productId: String?, orderId: String?) {
        if (text.isBlank()) return
        viewModelScope.launch {
            database.notebookDao().updateTask(id, text.trim(), dueDate, priority, employeeId, shipyardId, productId, orderId)
        }
    }

    fun setCompleted(id: String, completed: Boolean) = viewModelScope.launch {
        database.notebookDao().setTaskCompleted(id, completed)
    }

    fun deleteTask(id: String) = viewModelScope.launch {
        database.notebookDao().deleteTask(id)
    }
}
