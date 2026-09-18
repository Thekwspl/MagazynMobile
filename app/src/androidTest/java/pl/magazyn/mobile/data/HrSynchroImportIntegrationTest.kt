package pl.magazyn.mobile.data

import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
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
import pl.magazyn.mobile.domain.HrPhoneFetchStatus
import pl.magazyn.mobile.domain.HrSynchroCompleteness
import pl.magazyn.mobile.domain.HrSynchroEmployee
import pl.magazyn.mobile.domain.HrSynchroExport
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.seedCoreData

@RunWith(AndroidJUnit4::class)
class HrSynchroImportIntegrationTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase
    private lateinit var importer: HrSynchroImporter

    @Before fun setup() {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        importer = HrSynchroImporter(database)
    }

    @After fun cleanup() = environment.close()

    @Test
    fun exactUniqueNameLinksExistingPersonAndManualAssignmentResolvesAmbiguity() = runBlocking {
        database.employeeDao().insert(employee("local-1", "Jan", "Kowalski"))
        val exact = exportEmployees(listOf(source(101, "JAN", "KOWALSKI")))
        assertEquals(HrImportDecision.AUTO_LINK, importer.prepare(exact).items.single().decision)
        importer.import(exact, emptyMap())
        assertEquals("local-1", database.hrappkaLinkDao().findByHrappkaId(101)?.employeeId)

        database.employeeDao().insert(employee("local-2", "Piotr", "Testowy"))
        database.employeeDao().insert(employee("local-3", "Piotr", "Testowy"))
        val ambiguous = exportEmployees(listOf(source(102, "Piotr", "Testowy")))
        assertEquals(HrImportDecision.NEEDS_ASSIGNMENT, importer.prepare(ambiguous).items.single().decision)
        importer.import(ambiguous, mapOf(102L to HrManualAssignment(employeeId = "local-3")))
        assertEquals("local-3", database.hrappkaLinkDao().findByHrappkaId(102)?.employeeId)
        assertTrue(database.hrappkaLinkDao().findForEmployee("local-2").isEmpty())
    }

    @Test
    fun twoHrappkaIdsCanBeAssignedToOneEmployeeInOneAtomicImportAndRepeatedImportIsIdempotent() = runBlocking {
        database.employeeDao().insert(employee("person", "Piotr", "Pawłowski"))
        val file = exportEmployees(
            listOf(
                source(1001, "PIOTR", "PAWŁOWSKI", phones = listOf("+48 111 111 111")),
                source(1002, "Piotr", "Pawłowski", phones = listOf("+48 222 222 222")),
            ),
        )
        val assignments = mapOf(
            1001L to HrManualAssignment(employeeId = "person"),
            1002L to HrManualAssignment(employeeId = "person"),
        )
        val plan = importer.prepare(file, assignments)
        assertEquals(0, plan.needsAssignmentCount)
        importer.import(file, assignments)
        importer.import(file, assignments)

        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employees"))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_links WHERE employeeId='person'"))
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones WHERE employeeId='person'"))
        assertEquals(setOf("+48 111 111 111", "+48 222 222 222"), phoneSet("person"))
    }

    @Test
    fun employeeWithExistingHrappkaLinkRemainsExactNameCandidateForAnotherId() = runBlocking {
        database.employeeDao().insert(employee("person", "Piotr", "Pawłowski"))
        database.hrappkaLinkDao().upsert(EmployeeHrappkaLinkEntity(999, "person", "OLD", false))
        val file = exportEmployees(listOf(source(1000, "PIOTR", "PAWŁOWSKI", externalId = "NEW")))
        val item = importer.prepare(file).items.single()
        assertEquals(HrImportDecision.AUTO_LINK, item.decision)
        assertEquals("person", item.employeeId)
        importer.import(file, emptyMap())
        assertEquals(setOf(999L, 1000L), database.hrappkaLinkDao().findForEmployee("person").map { it.hrappkaId }.toSet())
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employees"))
    }

    @Test
    fun sharedPhoneRemainsWhileAnyHrappkaSourceStillProvidesIt() = runBlocking {
        database.employeeDao().insert(employee("person", "Anna", "Wspólna"))
        val assignments = mapOf(1101L to assignment("person"), 1102L to assignment("person"))
        importer.import(
            exportEmployees(listOf(source(1101, "Anna", "Wspólna", phones = listOf("+48 500 000 000")), source(1102, "Anna", "Wspólna", phones = listOf("+48 500 000 000")))),
            assignments,
        )
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones"))
        assertEquals(setOf("+48 500 000 000"), phoneSet("person"))

        importer.import(
            exportEmployees(listOf(source(1101, "Anna", "Wspólna", phones = emptyList()), source(1102, "Anna", "Wspólna", phones = listOf("+48 500 000 000")))),
            emptyMap(),
        )
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones"))
        assertEquals(setOf("+48 500 000 000"), phoneSet("person"))
    }

    @Test
    fun manualPhoneIsNeverDeletedByHrappka() = runBlocking {
        database.employeeDao().insert(employee("person", "Jan", "Ręczny", phones = "+48 500 123 456"))
        importer.import(exportEmployees(listOf(source(1201, "Jan", "Ręczny", phones = listOf("+48 500 123 456")))), emptyMap())
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones"))
        importer.import(exportEmployees(listOf(source(1201, "Jan", "Ręczny", phones = emptyList()))), emptyMap())
        assertEquals(setOf("+48 500 123 456"), phoneSet("person"))
    }

    @Test
    fun partialFetchAndErrorPreservePhonesPerSource() = runBlocking {
        database.employeeDao().insert(employee("person", "Jan", "Częściowy"))
        val assignments = mapOf(1301L to assignment("person"), 1302L to assignment("person"))
        importer.import(
            exportEmployees(listOf(source(1301, "Jan", "Częściowy", phones = listOf("+48 111")), source(1302, "Jan", "Częściowy", phones = listOf("+48 222")))),
            assignments,
        )
        importer.import(
            exportEmployees(listOf(source(1301, "Jan", "Częściowy", phones = listOf("+48 333")), source(1302, "Jan", "Częściowy", phoneStatus = HrPhoneFetchStatus.NOT_FETCHED))),
            emptyMap(),
        )
        assertEquals(setOf("+48 333", "+48 222"), phoneSet("person"))
        importer.import(
            exportEmployees(listOf(source(1301, "Jan", "Częściowy", phones = listOf("+48 444")), source(1302, "Jan", "Częściowy", phoneStatus = HrPhoneFetchStatus.ERROR))),
            emptyMap(),
        )
        assertEquals(setOf("+48 444", "+48 222"), phoneSet("person"))
    }

    @Test
    fun skipDecisionResolvesPreviewAndWritesNothingForRecord() = runBlocking {
        database.employeeDao().insert(employee("a", "Jan", "Ten Sam"))
        database.employeeDao().insert(employee("b", "Jan", "Ten Sam"))
        val file = exportEmployees(listOf(source(1401, "Jan", "Ten Sam", phones = listOf("+48 555"))))
        assertEquals(1, importer.prepare(file).needsAssignmentCount)
        val assignments = mapOf(1401L to HrManualAssignment(skip = true))
        assertEquals(0, importer.prepare(file, assignments).needsAssignmentCount)
        val report = importer.import(file, assignments)
        assertEquals(1, report.skipped)
        assertNull(database.hrappkaLinkDao().findByHrappkaId(1401))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM employee_hrappka_phones"))
    }

    @Test
    fun onlyConflictingHrappkaStatusesRequireAttention() = runBlocking {
        database.employeeDao().insert(employee("conflict", "Jan", "Konflikt"))
        importer.import(
            exportEmployees(listOf(source(1501, "Jan", "Konflikt", status = "Nie zatrudniać"), source(1502, "Jan", "Konflikt", status = "Pracuje"))),
            mapOf(1501L to assignment("conflict"), 1502L to assignment("conflict")),
        )
        var attention = buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first())
        assertEquals(HrappkaAttentionKind.STATUS_CONFLICT, attention.single().kind)
        assertFalse(database.employeeDao().observeSummaries().first().single().hrappkaDoNotHire)

        importer.import(
            exportEmployees(listOf(source(1502, "Jan", "Konflikt", status = "Nie zatrudniać"))),
            emptyMap(),
        )
        attention = buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first())
        assertTrue(attention.isEmpty())
        assertTrue(database.employeeDao().observeSummaries().first().single().hrappkaDoNotHire)

        importer.import(
            exportEmployees(listOf(source(1501, "Jan", "Konflikt", status = "Pracuje"), source(1502, "Jan", "Konflikt", status = "Pracuje"))),
            emptyMap(),
        )
        assertTrue(buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first()).isEmpty())
        assertFalse(database.employeeDao().observeSummaries().first().single().hrappkaDoNotHire)
    }

    @Test
    fun doNotHireDoesNotRemoveHistoryCustodyOrMovements() = runBlocking {
        database.seedCoreData()
        database.withTransaction {
            database.movementDao().insertMovement(StockMovementEntity("movement", "ISSUE", "warehouse-main", "employee-1", effectiveDate = "2026-09-01", createdAtEpochMillis = 1))
            database.movementDao().insertLine(StockMovementLineEntity("line", "movement", "product-1", -1.0, "szt."))
            database.movementDao().insertCustody(CustodyEntity("custody", "employee-1", "product-1", 1.0, "movement", "2026-09-01"))
        }
        importer.import(exportEmployees(listOf(source(1601, "Jan", "Kowalski", status = "Nie zatrudniać"))), mapOf(1601L to assignment("employee-1")))
        assertTrue(checkNotNull(database.hrappkaLinkDao().findByHrappkaId(1601)).doNotHire)
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE id='movement'"))
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE id='custody'"))
        importer.import(exportEmployees(listOf(source(1601, "Jan", "Kowalski", status = "Pracuje"))), emptyMap())
        assertFalse(checkNotNull(database.hrappkaLinkDao().findByHrappkaId(1601)).doNotHire)
    }

    @Test
    fun missingPersonIsCreatedIncludingDoNotHireAndPhoneAloneNeverMerges() = runBlocking {
        database.employeeDao().insert(employee("local-phone", "Jan", "Lokalny", phones = "+48 500 000 111"))
        importer.import(exportEmployees(listOf(source(1701, "Anna", "Nowa", externalId = "17/04/2026", phones = listOf("+48 500 000 111")))), emptyMap())
        val link = checkNotNull(database.hrappkaLinkDao().findByHrappkaId(1701))
        assertEquals("17/04/2026", link.externalId)
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM employees"))
        val doNotHireExport = exportEmployees(
            listOf(source(1702, "Ewa", "Informacyjna", phones = listOf("123 456 789"), status = "Nie zatrudniać")),
        )
        assertEquals(HrImportDecision.CREATE_NEW, importer.prepare(doNotHireExport).items.single().decision)
        val report = importer.import(doNotHireExport, emptyMap())
        val doNotHireLink = checkNotNull(database.hrappkaLinkDao().findByHrappkaId(1702))
        assertTrue(doNotHireLink.doNotHire)
        assertEquals(1, report.created)
        assertEquals(0, report.skipped)
        assertEquals(setOf("123 456 789"), phoneSet(doNotHireLink.employeeId))
        assertTrue(database.employeeDao().observeSummaries().first().first { it.id == doNotHireLink.employeeId }.hrappkaDoNotHire)
        assertTrue(buildHrappkaAttention(database.hrappkaLinkDao().observeActiveDetails().first()).isEmpty())
    }

    private suspend fun phoneSet(employeeId: String): Set<String> = database.employeeDao().findById(employeeId)?.phoneNumbers
        ?.split(',')?.map(String::trim)?.filter(String::isNotBlank)?.toSet().orEmpty()

    private fun employee(id: String, first: String, last: String, phones: String = "") =
        EmployeeEntity(id, "$first $last", first, last, phoneNumbers = phones)

    private fun assignment(employeeId: String) = HrManualAssignment(employeeId = employeeId)

    private fun source(
        id: Long,
        first: String,
        last: String,
        externalId: String? = null,
        phones: List<String> = emptyList(),
        phoneStatus: HrPhoneFetchStatus = HrPhoneFetchStatus.OK,
        status: String? = null,
    ) = HrSynchroEmployee(id, externalId, first, last, phones, phoneStatus, status)

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
