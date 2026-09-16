package pl.magazyn.mobile.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.domain.HrPhoneFetchStatus
import pl.magazyn.mobile.domain.HrSynchroCompleteness
import pl.magazyn.mobile.domain.HrSynchroEmployee
import pl.magazyn.mobile.domain.HrSynchroExport

@RunWith(AndroidJUnit4::class)
class HrSynchroImportIntegrationTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase
    private lateinit var importer: HrSynchroImporter

    @Before
    fun setup() {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        importer = HrSynchroImporter(database)
    }

    @After
    fun cleanup() = environment.close()

    @Test
    fun exactUniqueNameLinksExistingPersonAndManualAssignmentResolvesAmbiguity() = runBlocking {
        database.employeeDao().insert(employee("local-1", "Jan", "Kowalski"))
        val exact = export(employee = source(101, "Jan", "Kowalski"))
        val exactPlan = importer.prepare(exact)
        assertEquals(HrImportDecision.AUTO_LINK, exactPlan.items.single().decision)
        importer.import(exact, emptyMap())
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employees"))
        assertEquals(101L, database.employeeDao().findById("local-1")?.hrappkaId)

        database.employeeDao().insert(employee("local-2", "Piotr", "Testowy"))
        database.employeeDao().insert(employee("local-3", "Piotr", "Testowy"))
        val ambiguous = export(employee = source(102, "Piotr", "Testowy"))
        assertEquals(HrImportDecision.NEEDS_ASSIGNMENT, importer.prepare(ambiguous).items.single().decision)
        importer.import(ambiguous, mapOf(102L to HrManualAssignment(employeeId = "local-3")))
        assertEquals(102L, database.employeeDao().findById("local-3")?.hrappkaId)
        assertNull(database.employeeDao().findById("local-2")?.hrappkaId)
    }

    @Test
    fun missingPersonIsCreatedUnlessStatusIsDoNotHire() = runBlocking {
        importer.import(export(employee = source(201, "Anna", "Nowa", externalId = "17/04/2026")), emptyMap())
        val created = database.employeeDao().findByHrappkaId(201)
        assertEquals("17/04/2026", created?.hrappkaExternalId)

        val skipped = importer.import(export(employee = source(202, "Ewa", "Pominięta", status = "Nie zatrudniać")), emptyMap())
        assertEquals(1, skipped.skipped)
        assertNull(database.employeeDao().findByHrappkaId(202))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employees"))
    }

    @Test
    fun matchingPhoneAloneNeverMergesPeople() = runBlocking {
        database.employeeDao().insert(employee("local-phone", "Jan", "Lokalny", phones = "+48 500 000 111"))
        importer.import(
            export(employee = source(203, "Anna", "Inna", phones = listOf("+48 500 000 111"))),
            emptyMap(),
        )

        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employees"))
        assertNull(database.employeeDao().findById("local-phone")?.hrappkaId)
        assertEquals("Anna", database.employeeDao().findByHrappkaId(203)?.firstName)
    }

    @Test
    fun phoneProvenanceProtectsManualNumbersAndHandlesAllFetchStatuses() = runBlocking {
        database.employeeDao().insert(employee("person", "Jan", "Telefon", phones = "+48 500 000 001", hrappkaId = 301))

        importer.import(export(employee = source(301, "Jan", "Telefon", phones = listOf("+47 900 00 001"))), emptyMap())
        assertEquals(setOf("+48 500 000 001", "+47 900 00 001"), phoneSet("person"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones WHERE employeeId='person'"))

        importer.import(export(employee = source(301, "Jan", "Telefon", phones = listOf("+47 900 00 002", "+47  900 00 002"))), emptyMap())
        assertEquals(setOf("+48 500 000 001", "+47 900 00 002"), phoneSet("person"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones WHERE employeeId='person'"))

        importer.import(export(employee = source(301, "Jan", "Telefon", phones = emptyList(), phoneStatus = HrPhoneFetchStatus.NOT_FETCHED)), emptyMap())
        assertEquals(setOf("+48 500 000 001", "+47 900 00 002"), phoneSet("person"))
        importer.import(export(employee = source(301, "Jan", "Telefon", phones = emptyList(), phoneStatus = HrPhoneFetchStatus.ERROR)), emptyMap())
        assertEquals(setOf("+48 500 000 001", "+47 900 00 002"), phoneSet("person"))

        importer.import(export(employee = source(301, "Jan", "Telefon", phones = emptyList())), emptyMap())
        assertEquals(setOf("+48 500 000 001"), phoneSet("person"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones WHERE employeeId='person'"))
    }

    @Test
    fun doNotHireFlagTogglesWithoutRemovingHistoryCustodyOrMovements() = runBlocking {
        database.seedCoreData()
        database.withTransaction {
            database.employeeDao().update(
                checkNotNull(database.employeeDao().findById("employee-1")).copy(
                    phoneNumbers = "+47 900 00 401",
                    hrappkaId = 401,
                ),
            )
            database.hrappkaPhoneDao().upsert(listOf(EmployeeHrappkaPhoneEntity("employee-1", "+4790000401", "+47 900 00 401")))
            database.movementDao().insertMovement(StockMovementEntity("movement", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-09-01", createdAtEpochMillis = 1))
            database.movementDao().insertLine(StockMovementLineEntity("line", "movement", "product-1", -1.0, "szt."))
            database.movementDao().insertCustody(CustodyEntity("custody", "employee-1", "product-1", 1.0, "movement", "2026-09-01"))
        }

        importer.import(export(employee = source(401, "Jan", "Kowalski", status = "Nie zatrudniać")), emptyMap())
        assertTrue(checkNotNull(database.employeeDao().findById("employee-1")).hrappkaDoNotHire)
        assertEquals("+47 900 00 401", database.employeeDao().findById("employee-1")?.phoneNumbers)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE id='movement'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE id='custody'"))

        importer.import(export(employee = source(401, "Jan", "Kowalski", status = "Pracuje")), emptyMap())
        assertFalse(checkNotNull(database.employeeDao().findById("employee-1")).hrappkaDoNotHire)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE id='movement'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE id='custody'"))
    }

    @Test
    fun repeatedImportIsIdempotentAndUnresolvedPlanWritesNothing() = runBlocking {
        val file = export(employee = source(501, "Maria", "Powtarzalna", phones = listOf("+47 900 00 010")))
        importer.import(file, emptyMap())
        importer.import(file, emptyMap())
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employees WHERE hrappkaId=501"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones"))

        database.employeeDao().insert(employee("duplicate-1", "Adam", "Taki Sam"))
        database.employeeDao().insert(employee("duplicate-2", "Adam", "Taki Sam"))
        val unresolved = exportEmployees(
            listOf(
                source(502, "Adam", "Taki Sam"),
                source(503, "Nowa", "Osoba"),
            ),
        )
        val failure = runCatching { importer.import(unresolved, emptyMap()) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employees WHERE hrappkaId=502"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employees WHERE hrappkaId=503"))
    }

    private suspend fun phoneSet(employeeId: String): Set<String> = database.employeeDao().findById(employeeId)?.phoneNumbers
        ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()

    private fun employee(id: String, first: String, last: String, phones: String = "", hrappkaId: Long? = null) =
        EmployeeEntity(id, "$first $last", first, last, phoneNumbers = phones, hrappkaId = hrappkaId)

    private fun source(
        id: Long,
        first: String,
        last: String,
        externalId: String? = null,
        phones: List<String> = emptyList(),
        phoneStatus: HrPhoneFetchStatus = HrPhoneFetchStatus.OK,
        status: String? = null,
    ) = HrSynchroEmployee(id, externalId, first, last, phones, phoneStatus, status)

    private fun export(employee: HrSynchroEmployee): HrSynchroExport {
        return exportEmployees(listOf(employee))
    }

    private fun exportEmployees(employees: List<HrSynchroEmployee>): HrSynchroExport {
        val fetched = employees.count { it.phoneFetchStatus == HrPhoneFetchStatus.OK }
        val notFetched = employees.count { it.phoneFetchStatus == HrPhoneFetchStatus.NOT_FETCHED }
        val failed = employees.count { it.phoneFetchStatus == HrPhoneFetchStatus.ERROR }
        return HrSynchroExport(
            exportedAt = "2026-09-16T20:00:00Z",
            employeeCount = employees.size,
            completeness = HrSynchroCompleteness(true, employees.size, fetched, notFetched, failed, fetched == employees.size),
            employees = employees,
        )
    }
}
