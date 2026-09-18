package pl.magazyn.mobile.data

import androidx.room.withTransaction
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.queryDouble
import pl.magazyn.mobile.queryLong

@RunWith(AndroidJUnit4::class)
class EmployeeMergeIntegrationTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase
    private lateinit var merger: EmployeeMerger

    @Before
    fun setup() {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        merger = EmployeeMerger(database)
    }

    @After
    fun cleanup() = environment.close()

    @Test
    fun mergePreservesProfilePhonesAndEveryEmployeeReferenceWithoutChangingStock() = runBlocking {
        seedComprehensiveMergeFixture()

        merger.merge(TARGET, SOURCE)

        val target = checkNotNull(database.employeeDao().findById(TARGET))
        val source = checkNotNull(database.employeeDao().findById(SOURCE))
        assertEquals("Jan", target.firstName)
        assertEquals("Kowalski", target.lastName)
        assertEquals("Jan Kowalski", target.fullName)
        assertFalse(target.isArchived)
        assertTrue(source.isArchived)
        assertEquals("Spawacz", source.tags)
        assertEquals(listOf(TARGET), database.employeeDao().getAllNow().map(EmployeeEntity::id))

        assertEquals(setOf("Janek", "Jasio", "Jan Nowak"), target.aliases.split(", ").toSet())
        assertEquals(setOf("Brygadzista", "Spawacz"), target.tags.split(", ").toSet())
        assertEquals(
            setOf("123456789", "+48111111111", "+48222222222", "+47999999999"),
            splitPhones(target.phoneNumbers).map(::normalizePhoneKey).toSet(),
        )
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones WHERE employeeId=? AND normalizedNumber=?", TARGET, "123456789"))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones WHERE employeeId=?", TARGET))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_links WHERE employeeId=?", TARGET))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_links WHERE employeeId=?", SOURCE))
        assertEquals(1, buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first()).size)

        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employee_job_positions WHERE employeeId=?", TARGET))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employee_job_positions WHERE employeeId=?", SOURCE))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM shipyard_leaders WHERE employeeId=?", TARGET))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM shipyard_leaders WHERE employeeId=?", SOURCE))

        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE employeeId=?", TARGET))
        assertEquals("Historyczny odbiorca", queryString("SELECT recipientLabel FROM stock_movements WHERE id='movement-source'"))
        assertEquals(3L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE employeeId=?", TARGET))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE employeeId=? AND productId='product' AND returnedDate IS NULL", TARGET))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE employeeId=? AND returnedDate IS NOT NULL", TARGET))
        assertEquals(7.0, database.queryDouble("SELECT quantity FROM stock_balances WHERE warehouseId='warehouse-main' AND productId='product'"), 0.0)

        assertEquals(TARGET, database.orderDao().findById("order")?.employeeId)
        assertEquals("DRAFT", database.orderDao().findById("order")?.status)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM notebook_tasks WHERE id='task-direct' AND employeeId=?", TARGET))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM notebook_task_employees WHERE taskId='task-members' AND employeeId=?", TARGET))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM notebook_task_step_people WHERE taskStepId='step' AND employeeId=?", TARGET))
        assertEquals("Notatka source", queryString("SELECT note FROM notebook_task_step_people WHERE id='step-person-source'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM notebook_task_step_people WHERE id='step-person-source' AND isCompleted=1 AND completedAtEpochMillis=123"))
        assertEquals(0L, countReferences(SOURCE))
    }

    @Test
    fun mergedHrappkaStatusesKeepExistingAllFalseAllTrueAndConflictSemantics() = runBlocking {
        insertEmployee("false-target", "Adam", "Aktywny")
        insertEmployee("false-source", "Adam", "Aktywny")
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(1, "false-target", doNotHire = false))
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(2, "false-source", doNotHire = false))
        merger.merge("false-target", "false-source")

        insertEmployee("true-target", "Ewa", "Nieaktywna")
        insertEmployee("true-source", "Ewa", "Nieaktywna")
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(3, "true-target", doNotHire = true))
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(4, "true-source", doNotHire = true))
        merger.merge("true-target", "true-source")

        val summaries = database.employeeDao().observeSummaries().first().associateBy(EmployeeSummary::id)
        assertFalse(checkNotNull(summaries["false-target"]).hrappkaDoNotHire)
        assertTrue(checkNotNull(summaries["true-target"]).hrappkaDoNotHire)
        assertTrue(buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first()).isEmpty())

        insertEmployee("mixed-target", "Ola", "Konflikt")
        insertEmployee("mixed-source", "Ola", "Konflikt")
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(5, "mixed-target", doNotHire = true))
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(6, "mixed-source", doNotHire = false))
        merger.merge("mixed-target", "mixed-source")
        val attention = buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first())
        assertEquals("mixed-target", attention.single().employeeId)
        assertEquals(HrappkaAttentionKind.STATUS_CONFLICT, attention.single().kind)
    }

    @Test
    fun samePersonIsRejectedWithoutAnyWrite() = runBlocking {
        insertEmployee(TARGET, "Jan", "Kowalski", phones = "123 456 789")

        val failure = runCatching { merger.merge(TARGET, TARGET) }.exceptionOrNull()

        assertTrue(failure is IllegalArgumentException)
        val employee = checkNotNull(database.employeeDao().findById(TARGET))
        assertFalse(employee.isArchived)
        assertEquals("123 456 789", employee.phoneNumbers)
    }

    @Test
    fun failureInMiddleRollsBackProfilePhonesRelationsAndArchival() = runBlocking {
        insertEmployee(TARGET, "Jan", "Kowalski", phones = "111 111 111", aliases = "Janek")
        insertEmployee(SOURCE, "Jan", "Nowak", phones = "222 222 222", aliases = "Jasio")
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(99, SOURCE, doNotHire = false))
        database.jobPositionDao().upsert(listOf(JobPositionEntity("position", "Spawacz")))
        database.jobPositionDao().link(listOf(EmployeeJobPositionEntity(SOURCE, "position")))
        database.orderDao().upsertOrders(listOf(OrderEntity("rollback-order", null, SOURCE, "Jan Nowak", null, "DRAFT", "2026-09-18", 1)))
        database.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER force_merge_failure BEFORE UPDATE OF employeeId ON orders WHEN OLD.id='rollback-order' BEGIN SELECT RAISE(ABORT, 'forced merge failure'); END",
        )

        val failure = runCatching { merger.merge(TARGET, SOURCE) }.exceptionOrNull()

        assertTrue(failure != null)
        val target = checkNotNull(database.employeeDao().findById(TARGET))
        val source = checkNotNull(database.employeeDao().findById(SOURCE))
        assertEquals("111 111 111", target.phoneNumbers)
        assertEquals("Janek", target.aliases)
        assertFalse(source.isArchived)
        assertEquals(SOURCE, database.hrappkaLinkDao().findByHrappkaId(99)?.employeeId)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employee_job_positions WHERE employeeId=?", SOURCE))
        assertEquals(SOURCE, database.orderDao().findById("rollback-order")?.employeeId)
    }

    private suspend fun seedComprehensiveMergeFixture() = database.withTransaction {
        insertEmployee(
            TARGET,
            "Jan",
            "Kowalski",
            phones = "123 456 789, +48 111 111 111",
            aliases = "Janek",
            tags = "Brygadzista",
        )
        insertEmployee(
            SOURCE,
            "Jan",
            "Nowak",
            phones = "(123) 456-789, +48 222 222 222, +47 99 99 99 999",
            aliases = "Jasio",
            tags = "Spawacz",
        )
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(100, TARGET, "T", false))
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(200, SOURCE, "S", true))
        database.hrappkaPhoneDao().upsert(
            listOf(
                EmployeeHrappkaPhoneEntity(100, TARGET, "+48111111111", "+48 111 111 111"),
                EmployeeHrappkaPhoneEntity(200, SOURCE, "123456789", "(123) 456-789"),
                EmployeeHrappkaPhoneEntity(200, SOURCE, "+48222222222", "+48 222 222 222"),
            ),
        )

        database.jobPositionDao().upsert(listOf(JobPositionEntity("shared", "Wspólne"), JobPositionEntity("source", "Źródłowe")))
        database.jobPositionDao().link(
            listOf(
                EmployeeJobPositionEntity(TARGET, "shared"),
                EmployeeJobPositionEntity(SOURCE, "shared"),
                EmployeeJobPositionEntity(SOURCE, "source"),
            ),
        )
        database.shipyardDao().insert(ShipyardEntity("yard-shared", "Wspólna"))
        database.shipyardDao().insert(ShipyardEntity("yard-source", "Źródłowa"))
        database.shipyardDao().insertLeaders(
            listOf(
                ShipyardLeaderEntity("yard-shared", TARGET),
                ShipyardLeaderEntity("yard-shared", SOURCE),
                ShipyardLeaderEntity("yard-source", SOURCE),
            ),
        )

        database.warehouseDao().upsert(listOf(WarehouseEntity("warehouse-main", "Magazyn", true)))
        database.productDao().insert(ProductEntity("product", "Produkt", unit = "szt."))
        database.stockDao().upsert(listOf(StockBalanceEntity("warehouse-main", "product", 7.0)))
        database.movementDao().insertMovement(StockMovementEntity("movement-target", "ISSUE", "warehouse-main", TARGET, "Target", "2026-09-01", 1))
        database.movementDao().insertMovement(StockMovementEntity("movement-source", "ISSUE", "warehouse-main", SOURCE, "Historyczny odbiorca", "2026-09-02", 2))
        database.movementDao().insertLine(StockMovementLineEntity("line-target", "movement-target", "product", -1.0, "szt."))
        database.movementDao().insertLine(StockMovementLineEntity("line-source", "movement-source", "product", -2.0, "szt."))
        database.movementDao().insertCustody(CustodyEntity("custody-target", TARGET, "product", 1.0, "movement-target", "2026-09-01"))
        database.movementDao().insertCustody(CustodyEntity("custody-source-active", SOURCE, "product", 1.0, "movement-source", "2026-09-02"))
        database.movementDao().insertCustody(CustodyEntity("custody-source-returned", SOURCE, "product", 1.0, "movement-source", "2026-09-02", "2026-09-03"))

        database.orderDao().upsertOrders(listOf(OrderEntity("order", null, SOURCE, "Historyczny odbiorca", "Stocznia", "DRAFT", "2026-09-20", 3)))
        database.notebookDao().insertNotebook(OrderNotebookEntity("notebook", "Test", "ACTIVE", createdAtEpochMillis = 4))
        database.notebookDao().insertTasks(
            listOf(
                NotebookTaskEntity("task-direct", "notebook", "Bezpośrednie", position = 0, employeeId = SOURCE),
                NotebookTaskEntity("task-members", "notebook", "Lista", position = 1),
            ),
        )
        database.notebookDao().insertTaskEmployees(
            listOf(NotebookTaskEmployeeEntity("task-members", TARGET), NotebookTaskEmployeeEntity("task-members", SOURCE)),
        )
        database.taskStructureDao().insertSteps(listOf(NotebookTaskStepEntity("step", "task-members", 0, note = "Etap")))
        database.taskStructureDao().insertStepPeople(
            listOf(
                NotebookTaskStepPersonEntity("step-person-target", "step", 0, TARGET, note = "Notatka target"),
                NotebookTaskStepPersonEntity("step-person-source", "step", 1, SOURCE, note = "Notatka source", isCompleted = true, completedAtEpochMillis = 123, completedBy = "Tester"),
            ),
        )
    }

    private suspend fun insertEmployee(
        id: String,
        first: String,
        last: String,
        phones: String = "",
        aliases: String = "",
        tags: String = "",
    ) {
        database.employeeDao().insert(
            EmployeeEntity(id, "$first $last", first, last, phones, aliases, tags),
        )
    }

    private fun countReferences(employeeId: String): Long = listOf(
        "employee_hrappka_links",
        "employee_hrappka_phones",
        "employee_job_positions",
        "shipyard_leaders",
        "stock_movements",
        "custodies",
        "orders",
        "notebook_tasks",
        "notebook_task_employees",
        "notebook_task_step_people",
    ).sumOf { table -> database.queryLong("SELECT COUNT(*) FROM $table WHERE employeeId=?", employeeId) }

    private fun queryString(sql: String): String = database.openHelper.readableDatabase
        .query(SimpleSQLiteQuery(sql))
        .use { cursor ->
            check(cursor.moveToFirst())
            cursor.getString(0)
        }

    private companion object {
        const val TARGET = "target"
        const val SOURCE = "source"
    }
}
