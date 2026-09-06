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
import pl.magazyn.mobile.data.NotebookTaskEmployeeEntity
import pl.magazyn.mobile.data.OrderNotebookEntity
import androidx.room.withTransaction

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

    fun createTask(text: String, dueDate: String?, priority: String, place: String, employeeIds: List<String>, shipyardId: String?, productId: String?, orderId: String?) {
        val clean = text.trim()
        if (clean.isBlank()) return
        viewModelScope.launch {
            val notebookId = UUID.randomUUID().toString()
            val taskId = UUID.randomUUID().toString()
            database.withTransaction {
                database.notebookDao().insertNotebook(
                    OrderNotebookEntity(notebookId, clean, "ACTIVE", "TASK", System.currentTimeMillis()),
                )
                database.notebookDao().insertTasks(
                    listOf(NotebookTaskEntity(taskId, notebookId, clean, false, 0, dueDate, priority, employeeIds.firstOrNull(), shipyardId, productId, orderId, place.trim())),
                )
                database.notebookDao().insertTaskEmployees(employeeIds.distinct().map { NotebookTaskEmployeeEntity(taskId, it) })
            }
        }
    }

    fun updateTask(id: String, text: String, dueDate: String?, priority: String, place: String, employeeIds: List<String>, shipyardId: String?, productId: String?, orderId: String?) {
        if (text.isBlank()) return
        viewModelScope.launch {
            database.withTransaction {
                database.notebookDao().updateTask(id, text.trim(), dueDate, priority, place.trim(), employeeIds.firstOrNull(), shipyardId, productId, orderId)
                database.notebookDao().deleteTaskEmployees(id)
                database.notebookDao().insertTaskEmployees(employeeIds.distinct().map { NotebookTaskEmployeeEntity(id, it) })
            }
        }
    }

    fun setCompleted(id: String, completed: Boolean) = viewModelScope.launch {
        database.notebookDao().setTaskCompleted(id, completed)
    }

    fun deleteTask(id: String) = viewModelScope.launch {
        database.notebookDao().deleteTask(id)
    }
}
