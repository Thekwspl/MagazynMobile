package pl.magazyn.mobile.data

import android.content.Context
import java.io.File

/**
 * Mały, trwały dziennik przywracania. Pozwala odróżnić poprawny restart po
 * przywróceniu od przerwanego startu i zachować działającą bazę do rollbacku.
 */
object RestoreJournal {
    private const val PREFERENCES = "restore-journal"
    private const val KEY_STATE = "state"
    private const val KEY_STAGE = "stage"
    private const val STATE_NONE = "NONE"
    private const val STATE_PENDING_VERIFICATION = "PENDING_VERIFICATION"
    private const val STATE_VERIFYING = "VERIFYING"
    private const val STATE_ROLLED_BACK = "ROLLED_BACK"

    fun rollbackFile(context: Context): File =
        File(File(context.filesDir, "restore-rollback"), "magazyn-mobile-before-restore.db")

    fun recordStage(context: Context, stage: String) {
        preferences(context).edit().putString(KEY_STAGE, stage).apply()
    }

    fun markPendingVerification(context: Context) {
        preferences(context).edit()
            .putString(KEY_STATE, STATE_PENDING_VERIFICATION)
            .putString(KEY_STAGE, "RESTART_REQUESTED")
            .apply()
    }

    /** Wywoływane na początku procesu, zanim Room otworzy główną bazę. */
    fun recoverBeforeDatabaseOpen(context: Context, databaseFile: File): String? {
        when (preferences(context).getString(KEY_STATE, STATE_NONE)) {
            STATE_PENDING_VERIFICATION -> preferences(context).edit()
                .putString(KEY_STATE, STATE_VERIFYING)
                .putString(KEY_STAGE, "VERIFYING_RESTORED_DB")
                .apply()

            STATE_VERIFYING -> return rollback(context, databaseFile)
        }
        return null
    }

    /** Wywoływane przez callback Room dopiero po udanym otwarciu aktualnej bazy. */
    fun confirmDatabaseOpened(context: Context) {
        when (preferences(context).getString(KEY_STATE, STATE_NONE)) {
            STATE_PENDING_VERIFICATION, STATE_VERIFYING -> {
                rollbackFile(context).delete()
                preferences(context).edit()
                    .putString(KEY_STATE, STATE_NONE)
                    .putString(KEY_STAGE, "RESTORE_CONFIRMED")
                    .apply()
            }
        }
    }

    fun lastStage(context: Context): String? = preferences(context).getString(KEY_STAGE, null)

    private fun rollback(context: Context, databaseFile: File): String {
        val rollback = rollbackFile(context)
        if (!rollback.isFile) {
            preferences(context).edit().putString(KEY_STAGE, "ROLLBACK_FILE_MISSING").apply()
            return "Przywracanie kopii nie zakończyło się poprawnie, a poprzedniej bazy nie znaleziono. Etap: ROLLBACK_FILE_MISSING"
        }
        deleteSidecars(databaseFile)
        rollback.copyTo(databaseFile, overwrite = true)
        preferences(context).edit()
            .putString(KEY_STATE, STATE_ROLLED_BACK)
            .putString(KEY_STAGE, "ROLLBACK_RESTORED_PREVIOUS_DB")
            .apply()
        return "Przywracanie kopii nie zostało potwierdzone po restarcie. Dla bezpieczeństwa przywrócono poprzednią bazę danych."
    }

    fun deleteSidecars(databaseFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(databaseFile.path + suffix).delete()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
}
