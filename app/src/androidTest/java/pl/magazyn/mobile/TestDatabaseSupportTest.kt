package pl.magazyn.mobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.data.AppDatabase
import pl.magazyn.mobile.data.OrderNotebookEntity

@RunWith(AndroidJUnit4::class)
class TestDatabaseSupportTest {
    @Test fun closeClearsViewModelAndJoinsItsRoomWorkBeforeClosingDatabase() = runBlocking {
        IsolatedApplicationEnvironment.create().use { environment ->
            val database = environment.database
            database.seedCoreData()
            val model = environment.track(CleanupProbe(database))
            model.started.await()
            val scopeJob = checkNotNull(model.viewModelScope.coroutineContext[Job])

            environment.close()

            assertTrue(model.wasCleared)
            assertTrue(scopeJob.isCompleted)
            assertTrue(model.finished.isCompleted)
            assertFalse(database.isOpen)
            assertFalse(environment.root.exists())
        }
    }

    @Test fun awaitingOperationWaitsForItsRoomWriteWithoutWaitingForExistingObserver() = runBlocking {
        IsolatedApplicationEnvironment.create().use { environment ->
            val database = environment.database
            database.seedCoreData()
            val model = environment.track(WorkProbe(database))
            model.observerStarted.await()
            val awaited = async { model.runAndAwaitViewModelWork { model.write() } }
            model.writeStarted.await()
            assertFalse(awaited.isCompleted)
            assertNull(database.notebookDao().findNote("awaited"))

            model.allowWrite.complete(Unit)
            awaited.await()

            assertEquals("Zapis zakończony", database.notebookDao().findNote("awaited")?.rawText)
            assertTrue(model.observer.isActive)
        }
    }

    private class CleanupProbe(private val database: AppDatabase) : ViewModel() {
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var wasCleared = false
        init {
            viewModelScope.launch {
                try {
                    started.complete(Unit)
                    awaitCancellation()
                } finally {
                    // Kończenie pracy wykonuje prawdziwy zapis Room. Baza musi być jeszcze otwarta.
                    withContext(NonCancellable) {
                        database.notebookDao().insertNotebook(OrderNotebookEntity("cleanup", "Koniec", "ACTIVE", "NOTE", 1))
                        finished.complete(Unit)
                    }
                }
            }
        }
        override fun onCleared() { wasCleared = true }
    }

    private class WorkProbe(private val database: AppDatabase) : ViewModel() {
        val observerStarted = CompletableDeferred<Unit>()
        val writeStarted = CompletableDeferred<Unit>()
        val allowWrite = CompletableDeferred<Unit>()
        val observer = viewModelScope.launch {
            observerStarted.complete(Unit)
            awaitCancellation()
        }
        fun write() {
            viewModelScope.launch {
                writeStarted.complete(Unit)
                allowWrite.await()
                database.notebookDao().insertNotebook(OrderNotebookEntity("awaited", "Zapis zakończony", "ACTIVE", "NOTE", 1))
            }
        }
    }
}
