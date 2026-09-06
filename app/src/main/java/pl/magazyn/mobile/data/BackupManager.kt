package pl.magazyn.mobile.data

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.StandardCopyOption
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import pl.magazyn.mobile.MagazynApplication

class BackupManager(private val application: MagazynApplication) {
    private val database get() = application.database

    fun createEncryptedBackup(uri: Uri, password: String) {
        require(password.length >= 6) { "Hasło musi mieć co najmniej 6 znaków" }
        database.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            if (cursor.moveToFirst() && cursor.getInt(0) != 0) error("Baza jest chwilowo zajęta. Spróbuj ponownie za moment.")
        }
        val databaseFile = application.getDatabasePath(MagazynApplication.DATABASE_NAME)
        require(databaseFile.isFile) { "Nie znaleziono bazy danych" }
        val salt = ByteArray(16).also(SecureRandom()::nextBytes)
        val iv = ByteArray(12).also(SecureRandom()::nextBytes)
        val cipher = cipher(Cipher.ENCRYPT_MODE, password, salt, iv)
        application.contentResolver.openOutputStream(uri, "w")?.use { rawOutput ->
            val output = DataOutputStream(rawOutput)
            output.write(MAGIC)
            output.writeInt(DATABASE_VERSION)
            output.write(salt)
            output.write(iv)
            CipherOutputStream(output, cipher).use { encrypted -> databaseFile.inputStream().use { it.copyTo(encrypted) } }
        } ?: error("Nie udało się utworzyć pliku kopii")
    }

    fun restoreEncryptedBackup(uri: Uri, password: String) {
        require(password.length >= 6) { "Wpisz hasło użyte podczas tworzenia kopii" }
        val temporary = File.createTempFile("magazyn-restore-", ".db", application.cacheDir)
        try {
            application.contentResolver.openInputStream(uri)?.use { rawInput ->
                val input = DataInputStream(rawInput)
                val magic = ByteArray(MAGIC.size).also(input::readFully)
                require(magic.contentEquals(MAGIC)) { "To nie jest kopia Magazyn Mobile" }
                val backupFormat = input.readInt()
                require(backupFormat == DATABASE_VERSION) { "Nieobsługiwana wersja pliku kopii" }
                val salt = ByteArray(16).also(input::readFully)
                val iv = ByteArray(12).also(input::readFully)
                try {
                    CipherInputStream(input, cipher(Cipher.DECRYPT_MODE, password, salt, iv)).use { decrypted ->
                        temporary.outputStream().use { decrypted.copyTo(it) }
                    }
                } catch (_: AEADBadTagException) {
                    error("Nieprawidłowe hasło albo uszkodzony plik kopii")
                } catch (failure: java.io.IOException) {
                    if (failure.cause is AEADBadTagException) error("Nieprawidłowe hasło albo uszkodzony plik kopii")
                    throw failure
                }
            } ?: error("Nie udało się odczytać pliku kopii")
            validateDatabase(temporary)
            replaceDatabase(temporary)
        } finally {
            temporary.delete()
        }
    }

    fun restartApplication() {
        val launchIntent = application.packageManager.getLaunchIntentForPackage(application.packageName)
            ?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ?: return
        val pending = PendingIntent.getActivity(application, 9021, launchIntent, PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val alarm = application.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarm.set(AlarmManager.ELAPSED_REALTIME, SystemClock.elapsedRealtime() + 800L, pending)
        Process.killProcess(Process.myPid())
    }

    private fun validateDatabase(file: File) {
        val header = file.inputStream().use { input -> ByteArray(16).also { require(input.read(it) == 16) } }
        require(header.contentEquals("SQLite format 3\u0000".toByteArray())) { "Odszyfrowany plik nie jest prawidłową bazą SQLite" }
        val sqlite = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val version = sqlite.rawQuery("PRAGMA user_version", null).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
            require(version in 1..19) { if (version > 19) "Kopia pochodzi z nowszej wersji aplikacji" else "Kopia ma nieprawidłową wersję bazy" }
            sqlite.rawQuery("PRAGMA integrity_check", null).use { cursor ->
                require(cursor.moveToFirst() && cursor.getString(0).equals("ok", true)) { "Kontrola spójności kopii nie powiodła się" }
            }
        } finally {
            sqlite.close()
        }
    }

    private fun replaceDatabase(source: File) {
        val destination = application.getDatabasePath(MagazynApplication.DATABASE_NAME)
        val rollback = File(application.cacheDir, "magazyn-before-restore.db")
        val staged = File(destination.parentFile, "${MagazynApplication.DATABASE_NAME}.restoring")
        destination.parentFile?.mkdirs()
        source.copyTo(staged, overwrite = true)
        FileOutputStream(staged, true).use { it.fd.sync() }
        database.close()
        destination.copyTo(rollback, overwrite = true)
        try {
            listOf("-wal", "-shm", "-journal").forEach { File(destination.path + it).delete() }
            runCatching {
                java.nio.file.Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }.getOrElse {
                java.nio.file.Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            rollback.delete()
        } catch (error: Exception) {
            rollback.copyTo(destination, overwrite = true)
            throw error
        } finally {
            staged.delete()
            rollback.delete()
        }
    }

    private fun cipher(mode: Int, password: String, salt: ByteArray, iv: ByteArray): Cipher {
        val keyBytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(password.toCharArray(), salt, 150_000, 256)).encoded
        return Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(128, iv))
        }
    }

    private companion object {
        val MAGIC = "MAGAZYN_BACKUP\u0001".toByteArray()
        const val DATABASE_VERSION = 1
    }
}
