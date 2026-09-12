package pl.magazyn.mobile.data

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption

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
    private const val STATE_RECOVERY_FAILED = "RECOVERY_FAILED"

    fun rollbackFile(context: Context): File =
        File(File(context.filesDir, "restore-rollback"), "magazyn-mobile-before-restore.db")

    fun recordStage(context: Context, stage: String) {
        persist(preferences(context).edit().putString(KEY_STAGE, stage))
    }

    fun markPendingVerification(context: Context) {
        persist(preferences(context).edit()
            .putString(KEY_STATE, STATE_PENDING_VERIFICATION)
            .putString(KEY_STAGE, "INSTALLING_AWAITING_HEALTHCHECK"))
    }

    /** Wywoływane na początku procesu, zanim Room otworzy główną bazę. */
    fun recoverBeforeDatabaseOpen(context: Context, databaseFile: File): String? {
        when (preferences(context).getString(KEY_STATE, STATE_NONE)) {
            STATE_PENDING_VERIFICATION -> persist(
                preferences(context).edit()
                    .putString(KEY_STATE, STATE_VERIFYING)
                    .putString(KEY_STAGE, "VERIFYING_RESTORED_DB"),
            )

            STATE_VERIFYING -> return rollback(context, databaseFile)
        }
        return null
    }

    fun isHealthCheckRequired(context: Context): Boolean =
        preferences(context).getString(KEY_STATE, STATE_NONE) == STATE_VERIFYING

    /** Wywoływane wyłącznie po pełnym, aplikacyjnym health-checku przywróconej bazy. */
    fun confirmAfterHealthCheck(context: Context) {
        if (!isHealthCheckRequired(context)) return
        persist(
            preferences(context).edit()
                .putString(KEY_STATE, STATE_NONE)
                .putString(KEY_STAGE, "RESTORE_CONFIRMED"),
        )
        rollbackFile(context).delete()
    }

    fun markRolledBack(context: Context, stage: String) {
        persist(
            preferences(context).edit()
                .putString(KEY_STATE, STATE_ROLLED_BACK)
                .putString(KEY_STAGE, stage),
        )
    }

    fun markHealthCheckFailed(context: Context) {
        if (isHealthCheckRequired(context)) {
            recordStage(context, "HEALTHCHECK_FAILED_ROLLBACK_PENDING")
        }
    }

    fun lastStage(context: Context): String? = preferences(context).getString(KEY_STAGE, null)

    private fun rollback(context: Context, databaseFile: File): String {
        val rollback = rollbackFile(context)
        if (!rollback.isFile) {
            persist(
                preferences(context).edit()
                    .putString(KEY_STATE, STATE_RECOVERY_FAILED)
                    .putString(KEY_STAGE, "ROLLBACK_FILE_MISSING"),
            )
            return "Przywracanie kopii nie zakończyło się poprawnie, a poprzedniej bazy nie znaleziono. Etap: ROLLBACK_FILE_MISSING"
        }
        val staged = File(databaseFile.parentFile, "${databaseFile.name}.rollback-restoring")
        rollback.copyTo(staged, overwrite = true)
        FileOutputStream(staged, true).use { it.fd.sync() }
        deleteSidecars(databaseFile)
        runCatching {
            java.nio.file.Files.move(staged.toPath(), databaseFile.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }.getOrElse {
            java.nio.file.Files.move(staged.toPath(), databaseFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        markRolledBack(context, "ROLLBACK_RESTORED_PREVIOUS_DB")
        return "Przywracanie kopii nie zostało potwierdzone po restarcie. Dla bezpieczeństwa przywrócono poprzednią bazę danych."
    }

    fun deleteSidecars(databaseFile: File) {
        listOf("-wal", "-shm", "-journal").forEach { suffix ->
            File(databaseFile.path + suffix).delete()
        }
    }

    private fun preferences(context: Context) =
        context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    private fun persist(editor: android.content.SharedPreferences.Editor) {
        check(editor.commit()) { "Nie udało się trwale zapisać stanu przywracania danych" }
    }
}
