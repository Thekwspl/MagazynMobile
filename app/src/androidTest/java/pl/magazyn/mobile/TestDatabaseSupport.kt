package pl.magazyn.mobile

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import pl.magazyn.mobile.data.AppDatabase
import pl.magazyn.mobile.data.EmployeeEntity
import pl.magazyn.mobile.data.ProductEntity
import pl.magazyn.mobile.data.StockBalanceEntity
import pl.magazyn.mobile.data.WarehouseEntity

internal class IsolatedApplicationEnvironment private constructor(
    val root: File,
    val application: MagazynApplication,
) : AutoCloseable {
    val database: AppDatabase get() = application.database

    override fun close() {
        runCatching { application.closeDatabase() }
        root.deleteRecursively()
    }

    companion object {
        fun create(testName: String = UUID.randomUUID().toString()): IsolatedApplicationEnvironment {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val root = File(instrumentation.targetContext.cacheDir, "release-gate-$testName-${System.nanoTime()}")
                .apply { mkdirs() }
            val context = IsolatedStorageContext(instrumentation.targetContext, root)
            val application = instrumentation.newApplication(
                MagazynApplication::class.java.classLoader,
                MagazynApplication::class.java.name,
                context,
            ) as MagazynApplication
            return IsolatedApplicationEnvironment(root, application)
        }
    }
}

private class IsolatedStorageContext(base: Context, private val root: File) : ContextWrapper(base) {
    private val preferencesPrefix = "${root.name}-"

    override fun getApplicationContext(): Context = this
    override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
    override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
    override fun getDatabasePath(name: String): File = File(root, "databases/$name").also {
        it.parentFile?.mkdirs()
    }

    override fun deleteDatabase(name: String): Boolean {
        val database = getDatabasePath(name)
        return listOf(database, File(database.path + "-wal"), File(database.path + "-shm"), File(database.path + "-journal"))
            .map { !it.exists() || it.delete() }
            .all { it }
    }

    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences(preferencesPrefix + name, mode)
}

internal suspend fun AppDatabase.seedCoreData(
    stock: Double = 10.0,
    returnable: Boolean = true,
) {
    withTransaction {
        warehouseDao().upsert(listOf(WarehouseEntity("warehouse-main", "Magazyn główny", true)))
        employeeDao().insert(EmployeeEntity("employee-1", "Jan Kowalski", "Jan", "Kowalski"))
        productDao().insert(ProductEntity("product-1", "Produkt", "52", "szt.", isReturnable = returnable))
        stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product-1", stock)))
    }
}

internal fun AppDatabase.queryLong(sql: String, vararg args: Any?): Long =
    openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
        check(cursor.moveToFirst()) { "Zapytanie nie zwróciło wiersza: $sql" }
        cursor.getLong(0)
    }

internal fun AppDatabase.queryDouble(sql: String, vararg args: Any?): Double =
    openHelper.readableDatabase.query(SimpleSQLiteQuery(sql, args)).use { cursor ->
        check(cursor.moveToFirst()) { "Zapytanie nie zwróciło wiersza: $sql" }
        cursor.getDouble(0)
    }

internal suspend fun AppDatabase.awaitViewModelWork() {
    // Najpierw pozwalamy viewModelScope wejść do operacji, potem ustawiamy na
    // tym samym executorze Room barierę transakcyjną.
    withContext(Dispatchers.Main) { Unit }
    withTransaction { queryLong("SELECT 1") }
    withContext(Dispatchers.Main) { Unit }
}

internal suspend fun eventually(message: String, condition: suspend () -> Boolean) {
    repeat(100) {
        if (condition()) return
        delay(50)
    }
    throw AssertionError(message)
}
