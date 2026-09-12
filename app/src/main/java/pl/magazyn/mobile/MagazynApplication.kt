package pl.magazyn.mobile

import android.app.Application
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import pl.magazyn.mobile.data.AppDatabase
import pl.magazyn.mobile.data.BackupManager
import pl.magazyn.mobile.data.MIGRATION_1_2
import pl.magazyn.mobile.data.MIGRATION_2_3
import pl.magazyn.mobile.data.MIGRATION_3_4
import pl.magazyn.mobile.data.MIGRATION_4_5
import pl.magazyn.mobile.data.MIGRATION_5_6
import pl.magazyn.mobile.data.MIGRATION_6_7
import pl.magazyn.mobile.data.MIGRATION_7_8
import pl.magazyn.mobile.data.MIGRATION_8_9
import pl.magazyn.mobile.data.MIGRATION_9_10
import pl.magazyn.mobile.data.MIGRATION_10_11
import pl.magazyn.mobile.data.MIGRATION_11_12
import pl.magazyn.mobile.data.MIGRATION_12_13
import pl.magazyn.mobile.data.MIGRATION_13_14
import pl.magazyn.mobile.data.MIGRATION_14_15
import pl.magazyn.mobile.data.MIGRATION_15_16
import pl.magazyn.mobile.data.MIGRATION_16_17
import pl.magazyn.mobile.data.MIGRATION_17_18
import pl.magazyn.mobile.data.MIGRATION_18_19
import pl.magazyn.mobile.data.MIGRATION_19_20
import pl.magazyn.mobile.data.MIGRATION_20_21
import pl.magazyn.mobile.data.MIGRATION_21_22
import pl.magazyn.mobile.data.RestoreJournal
import pl.magazyn.mobile.data.RestoredDatabaseHealthCheck

class MagazynApplication : Application() {
    private val startupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        StartupDiagnostics.install(this)
        RestoreJournal.recoverBeforeDatabaseOpen(this, getDatabasePath(DATABASE_NAME))
            ?.let { StartupDiagnostics.recordProblem(this, it) }
        if (RestoreJournal.isHealthCheckRequired(this)) {
            startupScope.launch {
                try {
                    RestoredDatabaseHealthCheck.verify(database)
                    RestoreJournal.confirmAfterHealthCheck(this@MagazynApplication)
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(
                            this@MagazynApplication,
                            "Kopia została przywrócona i sprawdzona poprawnie.",
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                } catch (error: Throwable) {
                    runCatching { RestoreJournal.markHealthCheckFailed(this@MagazynApplication) }
                    runCatching {
                        StartupDiagnostics.recordProblem(
                            this@MagazynApplication,
                            "Kontrola przywróconej bazy nie powiodła się: ${error.message ?: error.javaClass.simpleName}",
                        )
                    }
                    BackupManager(this@MagazynApplication).restartApplication()
                }
            }
        }
    }

    private var databaseInstance: AppDatabase? = null
    @Volatile private var databaseReplacementInProgress = false

    val database: AppDatabase
        get() = synchronized(this) {
            check(!databaseReplacementInProgress) { "Trwa bezpieczna podmiana bazy danych" }
            databaseInstance ?: createDatabase(DATABASE_NAME).also { databaseInstance = it }
        }

    /** Zamknięcie głównej instancji przed atomową podmianą pliku SQLite. */
    fun closeDatabase() = synchronized(this) {
        databaseInstance?.close()
        databaseInstance = null
    }

    /** Od tego momentu główna baza nie może zostać ponownie otwarta przed restartem procesu. */
    fun beginDatabaseReplacement() = synchronized(this) {
        databaseReplacementInProgress = true
        databaseInstance?.close()
        databaseInstance = null
    }

    /** Używane również do sprawdzenia przywróconej kopii poza aktywną bazą. */
    fun createDatabase(name: String): AppDatabase {
        val builder = Room.databaseBuilder(
            applicationContext,
            AppDatabase::class.java,
            name,
        ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16, MIGRATION_16_17, MIGRATION_17_18, MIGRATION_18_19, MIGRATION_19_20, MIGRATION_20_21, MIGRATION_21_22)
        return builder.build()
    }

    companion object {
        const val DATABASE_NAME = "magazyn-mobile.db"
    }
}
