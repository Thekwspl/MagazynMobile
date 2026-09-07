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
import pl.magazyn.mobile.data.NotebookTaskStepEntity
import pl.magazyn.mobile.data.NotebookTaskStepPersonEntity
import pl.magazyn.mobile.data.TaskPlaceAliasEntity
import pl.magazyn.mobile.data.TaskPlaceEntity
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.ParsedTaskStep
import pl.magazyn.mobile.domain.normalizeDisplayName
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
    val places = database.taskStructureDao().observePlaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val steps = database.taskStructureDao().observeAllSteps()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val stepPeople = database.taskStructureDao().observeAllStepPeople()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createTask(text: String, description: String, dueDate: String?, priority: String, steps: List<ParsedTaskStep>, shipyardId: String?, productId: String?, orderId: String?) {
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
                    listOf(NotebookTaskEntity(taskId, notebookId, clean, false, 0, dueDate, priority, null, shipyardId, productId, orderId, "", description.trim())),
                )
                insertTaskSteps(taskId, steps)
            }
        }
    }

    fun updateTask(id: String, text: String, description: String, dueDate: String?, priority: String, steps: List<ParsedTaskStep>, shipyardId: String?, productId: String?, orderId: String?) {
        if (text.isBlank()) return
        viewModelScope.launch {
            database.withTransaction {
                database.notebookDao().updateTask(id, text.trim(), dueDate, priority, "", description.trim(), null, shipyardId, productId, orderId)
                database.notebookDao().deleteTaskEmployees(id)
                database.taskStructureDao().deleteSteps(id)
                insertTaskSteps(id, steps)
            }
        }
    }

    fun setCompleted(id: String, completed: Boolean) = viewModelScope.launch {
        database.withTransaction {
            database.notebookDao().setTaskCompleted(id, completed)
            val now = if (completed) System.currentTimeMillis() else null
            steps.value.filter { it.taskId == id }.forEach { step ->
                database.taskStructureDao().setStepCompleted(step.id, completed, now, null)
                database.taskStructureDao().setAllStepPeopleCompleted(step.id, completed, now, null)
            }
        }
    }

    fun setStepCompleted(stepId: String, completed: Boolean) = viewModelScope.launch {
        database.withTransaction {
            val now = if (completed) System.currentTimeMillis() else null
            database.taskStructureDao().setStepCompleted(stepId, completed, now, null)
            database.taskStructureDao().setAllStepPeopleCompleted(stepId, completed, now, null)
        }
    }

    fun setStepPersonCompleted(personId: String, completed: Boolean) = viewModelScope.launch {
        database.withTransaction {
            val now = if (completed) System.currentTimeMillis() else null
            database.taskStructureDao().setStepPersonCompleted(personId, completed, now, null)
            val stepId = database.taskStructureDao().findStepIdForPerson(personId) ?: return@withTransaction
            val allDone = database.taskStructureDao().countPeople(stepId) > 0 && database.taskStructureDao().countIncompletePeople(stepId) == 0
            database.taskStructureDao().setStepCompleted(stepId, allDone, if (allDone) now else null, null)
        }
    }

    fun createPlace(name: String, aliases: List<String> = emptyList(), onResult: (String?) -> Unit = {}) {
        val cleanName = normalizeDisplayName(name)
        if (cleanName.isBlank()) { onResult("Podaj nazwę miejsca"); return }
        viewModelScope.launch {
            val existing = database.taskStructureDao().findPlaceByName(cleanName)
            if (existing != null) { database.taskStructureDao().restorePlace(existing.id); onResult(null); return@launch }
            val normalizedAliases = aliases.map(ImportParser::key).filter(String::isNotBlank).distinct()
            val conflict = normalizedAliases.firstNotNullOfOrNull { database.taskStructureDao().findAlias(it) }
            val placeNameConflict = database.taskStructureDao().getPlacesNow().firstOrNull { ImportParser.key(it.name) in normalizedAliases }
            if (conflict != null || placeNameConflict != null) { onResult("Ten alias jest już przypisany do innego miejsca"); return@launch }
            database.withTransaction {
                val place = TaskPlaceEntity(UUID.randomUUID().toString(), cleanName)
                database.taskStructureDao().insertPlace(place)
                aliases.map(String::trim).filter(String::isNotBlank).distinctBy(ImportParser::key).forEach { alias ->
                    database.taskStructureDao().insertAlias(TaskPlaceAliasEntity(UUID.randomUUID().toString(), place.id, alias, ImportParser.key(alias)))
                }
            }
            onResult(null)
        }
    }

    fun addPlaceAlias(placeId: String, alias: String, onResult: (String?) -> Unit) {
        val clean = alias.trim()
        val normalized = ImportParser.key(clean)
        if (normalized.isBlank()) { onResult("Podaj alias"); return }
        viewModelScope.launch {
            val conflict = database.taskStructureDao().findAlias(normalized)
            val placeNameConflict = database.taskStructureDao().getPlacesNow().firstOrNull { ImportParser.key(it.name) == normalized && it.id != placeId }
            if ((conflict != null && conflict.placeId != placeId) || placeNameConflict != null) {
                onResult("Alias „$clean” jest już przypisany do innego miejsca")
            } else if (conflict == null) {
                database.taskStructureDao().insertAlias(TaskPlaceAliasEntity(UUID.randomUUID().toString(), placeId, clean, normalized))
                onResult(null)
            } else onResult(null)
        }
    }

    fun renamePlace(placeId: String, name: String, onResult: (String?) -> Unit) {
        val clean = normalizeDisplayName(name)
        if (clean.isBlank()) { onResult("Podaj nazwę miejsca"); return }
        viewModelScope.launch {
            val conflict = database.taskStructureDao().getPlacesNow().firstOrNull { it.id != placeId && ImportParser.key(it.name) == ImportParser.key(clean) }
            val aliasConflict = database.taskStructureDao().findAlias(ImportParser.key(clean))
            if (conflict != null || (aliasConflict != null && aliasConflict.placeId != placeId)) onResult("Ta nazwa jest już używana przez inne miejsce lub alias")
            else { database.taskStructureDao().renamePlace(placeId, clean); onResult(null) }
        }
    }

    fun removePlaceAlias(placeId: String, alias: String) = viewModelScope.launch {
        database.taskStructureDao().deleteAlias(placeId, ImportParser.key(alias))
    }

    fun archivePlace(placeId: String) = viewModelScope.launch {
        database.taskStructureDao().archivePlace(placeId)
    }

    fun deleteTask(id: String) = viewModelScope.launch {
        database.notebookDao().deleteTask(id)
    }

    private suspend fun insertTaskSteps(taskId: String, taskSteps: List<ParsedTaskStep>) {
        taskSteps.forEachIndexed { index, step ->
            val stepId = step.recordId ?: UUID.randomUUID().toString()
            database.taskStructureDao().insertSteps(listOf(NotebookTaskStepEntity(stepId, taskId, index, step.time, step.placeId, step.note, step.isCompleted, step.completedAtEpochMillis, step.completedBy)))
            database.taskStructureDao().insertStepPeople(step.people.mapIndexed { personIndex, person ->
                NotebookTaskStepPersonEntity(person.recordId ?: UUID.randomUUID().toString(), stepId, personIndex, person.employeeId, if (person.employeeId == null) person.displayText else "", person.note, person.isCompleted, person.completedAtEpochMillis, person.completedBy)
            })
        }
    }

}
