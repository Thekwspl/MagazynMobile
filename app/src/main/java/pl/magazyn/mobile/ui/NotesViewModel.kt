package pl.magazyn.mobile.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.util.UUID
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.magazyn.mobile.MagazynApplication
import pl.magazyn.mobile.data.OrderNotebookEntity
import pl.magazyn.mobile.data.NotebookDao

internal fun plainNoteEntity(id: String, rawText: String, now: Long = System.currentTimeMillis()): OrderNotebookEntity {
    require(rawText.isNotBlank()) { "Notatka nie może być pusta." }
    return OrderNotebookEntity(id, rawText, "ACTIVE", "NOTE", now)
}

internal suspend fun NotebookDao.insertPlainNote(rawText: String, id: String = UUID.randomUUID().toString()): String {
    insertNotebook(plainNoteEntity(id, rawText))
    return id
}

class NotesViewModel(application: Application) : AndroidViewModel(application) {
    private val notebookDao = (application as MagazynApplication).database.notebookDao()

    val notes = notebookDao.observeNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun save(noteId: String?, rawText: String, onSaved: (String) -> Unit = {}) {
        if (rawText.isBlank()) return
        viewModelScope.launch {
            val id = noteId ?: UUID.randomUUID().toString()
            if (noteId == null) {
                notebookDao.insertPlainNote(rawText, id)
            } else {
                notebookDao.updateNoteText(id, rawText)
            }
            onSaved(id)
        }
    }

    fun delete(noteId: String, onDeleted: () -> Unit = {}) {
        viewModelScope.launch {
            // Archiwizacja usuwa notatkę z listy bez ryzyka kaskadowego usunięcia
            // ewentualnych danych, które mogłyby zostać z nią powiązane w przyszłości.
            notebookDao.setNoteStatus(noteId, "ARCHIVED")
            onDeleted()
        }
    }
}
