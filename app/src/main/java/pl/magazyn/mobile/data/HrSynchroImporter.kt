package pl.magazyn.mobile.data

import androidx.room.withTransaction
import java.util.Locale
import java.util.UUID
import pl.magazyn.mobile.domain.HrPhoneFetchStatus
import pl.magazyn.mobile.domain.HrSynchroEmployee
import pl.magazyn.mobile.domain.HrSynchroExport
import pl.magazyn.mobile.domain.normalizeEmployeeName

enum class HrImportDecision {
    LINKED, AUTO_LINK, MANUAL_LINK, CREATE_NEW, NEEDS_ASSIGNMENT, SKIP_DO_NOT_HIRE, SKIP_MANUAL,
}

data class HrManualAssignment(
    val employeeId: String? = null,
    val createNew: Boolean = false,
    val skip: Boolean = false,
)

data class HrImportPlanItem(
    val source: HrSynchroEmployee,
    val decision: HrImportDecision,
    val employeeId: String? = null,
    val candidateIds: List<String> = emptyList(),
)

data class HrImportPlan(
    val export: HrSynchroExport,
    val items: List<HrImportPlanItem>,
    val localEmployees: List<EmployeeEntity>,
    val localLinks: List<EmployeeHrappkaLinkEntity>,
) {
    val newCount get() = items.count { it.decision == HrImportDecision.CREATE_NEW }
    val updateCount get() = items.count { it.decision in setOf(HrImportDecision.LINKED, HrImportDecision.AUTO_LINK, HrImportDecision.MANUAL_LINK) }
    val autoLinkedCount get() = items.count { it.decision == HrImportDecision.AUTO_LINK }
    val needsAssignmentCount get() = items.count { it.decision == HrImportDecision.NEEDS_ASSIGNMENT }
    val skippedDoNotHireCount get() = items.count { it.decision == HrImportDecision.SKIP_DO_NOT_HIRE }
    val skippedManualCount get() = items.count { it.decision == HrImportDecision.SKIP_MANUAL }
    val doNotHireCount get() = items.count { it.source.doNotHire }
}

data class HrImportReport(
    val created: Int,
    val updated: Int,
    val linked: Int,
    val phonesAdded: Int,
    val hrPhonesRemoved: Int,
    val phonesSkippedNotFetched: Int,
    val phoneErrors: Int,
    val doNotHire: Int,
    val skipped: Int,
)

object HrSynchroPlanner {
    fun prepare(
        export: HrSynchroExport,
        employees: List<EmployeeEntity>,
        links: List<EmployeeHrappkaLinkEntity>,
        assignments: Map<Long, HrManualAssignment> = emptyMap(),
    ): HrImportPlan {
        val employeeById = employees.associateBy(EmployeeEntity::id)
        val linkedByHrId = links.associateBy(EmployeeHrappkaLinkEntity::hrappkaId)
        val activeByName = employees.filterNot(EmployeeEntity::isArchived)
            .groupBy { personKey(it.firstName, it.lastName) }

        val items = export.employees.map { source ->
            linkedByHrId[source.hrappkaId]?.let { link ->
                require(employeeById[link.employeeId] != null) {
                    "Etap PLAN: powiązanie hrappkaId=${source.hrappkaId} wskazuje brakującą osobę ${link.employeeId}"
                }
                return@map HrImportPlanItem(source, HrImportDecision.LINKED, link.employeeId)
            }
            assignments[source.hrappkaId]?.let { manual ->
                require(listOf(manual.employeeId != null, manual.createNew, manual.skip).count { it } == 1) {
                    "Etap PLAN: hrappkaId=${source.hrappkaId} ma niejednoznaczną decyzję ręczną"
                }
                if (manual.skip) return@map HrImportPlanItem(source, HrImportDecision.SKIP_MANUAL)
                if (manual.createNew) {
                    return@map HrImportPlanItem(source, HrImportDecision.CREATE_NEW)
                }
                val target = employeeById[manual.employeeId]
                    ?: error("Etap PLAN: nie znaleziono wybranej osoby ${manual.employeeId} dla hrappkaId=${source.hrappkaId}")
                require(!target.isArchived) { "Etap PLAN: osoba ${target.id} jest archiwalna i nie może zostać powiązana" }
                return@map HrImportPlanItem(source, HrImportDecision.MANUAL_LINK, target.id)
            }

            val candidates = activeByName[personKey(source.firstName, source.lastName)].orEmpty()
            when {
                candidates.size == 1 -> HrImportPlanItem(source, HrImportDecision.AUTO_LINK, candidates.single().id, listOf(candidates.single().id))
                candidates.isNotEmpty() -> HrImportPlanItem(source, HrImportDecision.NEEDS_ASSIGNMENT, candidateIds = candidates.map(EmployeeEntity::id))
                else -> HrImportPlanItem(source, HrImportDecision.CREATE_NEW)
            }
        }
        return HrImportPlan(export, items, employees, links)
    }

    fun personKey(firstName: String, lastName: String): String =
        normalizeEmployeeName(firstName, lastName).fullName.lowercase(Locale.ROOT)
}

class HrSynchroImporter(private val database: AppDatabase) {
    suspend fun prepare(
        export: HrSynchroExport,
        assignments: Map<Long, HrManualAssignment> = emptyMap(),
    ): HrImportPlan = HrSynchroPlanner.prepare(
        export,
        database.employeeDao().getAllIncludingArchivedNow(),
        database.hrappkaLinkDao().getAllNow(),
        assignments,
    )

    suspend fun import(
        export: HrSynchroExport,
        assignments: Map<Long, HrManualAssignment>,
    ): HrImportReport = database.withTransaction {
        val plan = prepare(export, assignments)
        require(plan.needsAssignmentCount == 0) { "Etap PLAN: ${plan.needsAssignmentCount} rekordów nadal wymaga przypisania" }
        var created = 0
        var updated = 0
        var linked = 0
        var phonesAdded = 0
        var phonesRemoved = 0
        var skippedNotFetched = 0
        var phoneErrors = 0
        var skipped = 0

        plan.items.forEach { item ->
            if (item.decision in setOf(HrImportDecision.SKIP_DO_NOT_HIRE, HrImportDecision.SKIP_MANUAL)) {
                skipped++
                return@forEach
            }
            val source = item.source
            val oldLink = database.hrappkaLinkDao().findByHrappkaId(source.hrappkaId)
            val existing = item.employeeId?.let { database.employeeDao().findById(it) }
            val normalized = normalizeEmployeeName(source.firstName, source.lastName)
            val employee = if (existing == null) {
                EmployeeEntity(
                    id = UUID.randomUUID().toString(),
                    fullName = normalized.fullName,
                    firstName = normalized.firstName,
                    lastName = normalized.lastName,
                ).also {
                    database.employeeDao().insert(it)
                    created++
                }
            } else {
                existing.copy(
                    fullName = normalized.fullName,
                    firstName = normalized.firstName,
                    lastName = normalized.lastName,
                ).also {
                    database.employeeDao().update(it)
                    updated++
                    if (oldLink == null) linked++
                }
            }

            database.hrappkaLinkDao().upsert(
                EmployeeHrappkaLinkEntity(source.hrappkaId, employee.id, source.externalId, source.doNotHire),
            )
            when (source.phoneFetchStatus) {
                HrPhoneFetchStatus.NOT_FETCHED -> skippedNotFetched++
                HrPhoneFetchStatus.ERROR -> phoneErrors++
                HrPhoneFetchStatus.OK -> {
                    val result = syncPhones(employee, source.hrappkaId, source.phones)
                    phonesAdded += result.added
                    phonesRemoved += result.removed
                }
            }
        }
        HrImportReport(created, updated, linked, phonesAdded, phonesRemoved, skippedNotFetched, phoneErrors, plan.doNotHireCount, skipped)
    }

    private suspend fun syncPhones(employee: EmployeeEntity, hrappkaId: Long, sourcePhones: List<String>): PhoneSyncResult {
        val trackedBefore = database.hrappkaPhoneDao().findForEmployee(employee.id)
        val trackedKeysBefore = trackedBefore.map(EmployeeHrappkaPhoneEntity::normalizedNumber).toSet()
        val existingNumbers = splitPhones(employee.phoneNumbers).distinctBy(::normalizePhoneKey)
        val oldDisplayedKeys = existingNumbers.map(::normalizePhoneKey).toSet()
        val manualNumbers = existingNumbers.filter { normalizePhoneKey(it) !in trackedKeysBefore }
        val manualKeys = manualNumbers.map(::normalizePhoneKey).toSet()
        val incoming = sourcePhones.map(::displayPhone).distinctBy(::normalizePhoneKey)

        database.hrappkaPhoneDao().deleteForHrappkaId(hrappkaId)
        val newSourceRows = incoming.filter { normalizePhoneKey(it) !in manualKeys }.map { number ->
            EmployeeHrappkaPhoneEntity(hrappkaId, employee.id, normalizePhoneKey(number), number)
        }
        if (newSourceRows.isNotEmpty()) database.hrappkaPhoneDao().upsert(newSourceRows)

        val trackedAfter = database.hrappkaPhoneDao().findForEmployee(employee.id)
        val finalNumbers = (manualNumbers + trackedAfter.map(EmployeeHrappkaPhoneEntity::displayNumber)).distinctBy(::normalizePhoneKey)
        database.employeeDao().update(employee.copy(phoneNumbers = finalNumbers.joinToString(", ")))
        val newDisplayedKeys = finalNumbers.map(::normalizePhoneKey).toSet()
        return PhoneSyncResult(
            added = newDisplayedKeys.count { it !in oldDisplayedKeys },
            removed = oldDisplayedKeys.count { it !in newDisplayedKeys },
        )
    }

    private data class PhoneSyncResult(val added: Int, val removed: Int)
}

internal fun splitPhones(value: String): List<String> = value
    .split(Regex("[,;|\\n]+"))
    .map(::displayPhone)
    .filter(String::isNotBlank)

internal fun displayPhone(value: String): String = value.trim().replace(Regex("\\s+"), " ")

internal fun normalizePhoneKey(value: String): String {
    val display = displayPhone(value)
    val digits = display.filter(Char::isDigit)
    val firstDigit = display.indexOfFirst(Char::isDigit)
    val hasLeadingPlus = firstDigit >= 0 && display.take(firstDigit).contains('+')
    return if (digits.isEmpty()) display.lowercase(Locale.ROOT) else (if (hasLeadingPlus) "+" else "") + digits
}
