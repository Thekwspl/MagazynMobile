package pl.magazyn.mobile.data

import androidx.room.withTransaction
import java.util.Locale
import java.util.UUID
import pl.magazyn.mobile.domain.HrPhoneFetchStatus
import pl.magazyn.mobile.domain.HrSynchroEmployee
import pl.magazyn.mobile.domain.HrSynchroExport

enum class HrImportDecision {
    LINKED,
    AUTO_LINK,
    MANUAL_LINK,
    CREATE_NEW,
    NEEDS_ASSIGNMENT,
    SKIP_DO_NOT_HIRE,
}

data class HrManualAssignment(val employeeId: String? = null, val createNew: Boolean = false)

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
) {
    val newCount get() = items.count { it.decision == HrImportDecision.CREATE_NEW }
    val updateCount get() = items.count { it.decision in setOf(HrImportDecision.LINKED, HrImportDecision.AUTO_LINK, HrImportDecision.MANUAL_LINK) }
    val autoLinkedCount get() = items.count { it.decision == HrImportDecision.AUTO_LINK }
    val needsAssignmentCount get() = items.count { it.decision == HrImportDecision.NEEDS_ASSIGNMENT }
    val skippedDoNotHireCount get() = items.count { it.decision == HrImportDecision.SKIP_DO_NOT_HIRE }
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
        assignments: Map<Long, HrManualAssignment> = emptyMap(),
    ): HrImportPlan {
        val linkedByHrId = employees.mapNotNull { employee -> employee.hrappkaId?.let { it to employee } }.toMap()
        val unlinkedActive = employees.filter { it.hrappkaId == null && !it.isArchived }
        val unlinkedByName = unlinkedActive.groupBy { personKey(it.firstName, it.lastName) }
        val exactCandidates = export.employees.associate { source ->
            source.hrappkaId to unlinkedByName[personKey(source.firstName, source.lastName)].orEmpty()
        }
        val soleCandidateUseCount = exactCandidates.values.mapNotNull { it.singleOrNull()?.id }.groupingBy { it }.eachCount()
        val manuallyUsedIds = assignments.values.mapNotNull { it.employeeId }
        val duplicateManualTarget = manuallyUsedIds.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicateManualTarget == null) { "Etap PLAN: lokalna osoba $duplicateManualTarget została przypisana do kilku rekordów HRappka" }

        val items = export.employees.map { source ->
            linkedByHrId[source.hrappkaId]?.let { linked ->
                return@map HrImportPlanItem(source, HrImportDecision.LINKED, linked.id)
            }
            val manual = assignments[source.hrappkaId]
            if (manual != null) {
                if (manual.createNew) {
                    require(!source.doNotHire) { "Etap PLAN: hrappkaId=${source.hrappkaId} ma status Nie zatrudniać i nie może zostać utworzony" }
                    return@map HrImportPlanItem(source, HrImportDecision.CREATE_NEW)
                }
                val target = employees.singleOrNull { it.id == manual.employeeId }
                    ?: error("Etap PLAN: nie znaleziono wybranej osoby ${manual.employeeId} dla hrappkaId=${source.hrappkaId}")
                require(!target.isArchived) { "Etap PLAN: osoba ${target.id} jest archiwalna i nie może zostać powiązana" }
                require(target.hrappkaId == null || target.hrappkaId == source.hrappkaId) {
                    "Etap PLAN: osoba ${target.id} jest już połączona z innym hrappkaId"
                }
                return@map HrImportPlanItem(source, HrImportDecision.MANUAL_LINK, target.id)
            }
            val candidates = exactCandidates.getValue(source.hrappkaId)
            if (candidates.size == 1 && soleCandidateUseCount[candidates.single().id] == 1 && candidates.single().id !in manuallyUsedIds) {
                HrImportPlanItem(source, HrImportDecision.AUTO_LINK, candidates.single().id, listOf(candidates.single().id))
            } else if (candidates.isNotEmpty()) {
                HrImportPlanItem(source, HrImportDecision.NEEDS_ASSIGNMENT, candidateIds = candidates.map(EmployeeEntity::id))
            } else if (source.doNotHire) {
                HrImportPlanItem(source, HrImportDecision.SKIP_DO_NOT_HIRE)
            } else {
                HrImportPlanItem(source, HrImportDecision.CREATE_NEW)
            }
        }
        val targets = items.mapNotNull { it.employeeId }
        val duplicateTarget = targets.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.key
        require(duplicateTarget == null) { "Etap PLAN: lokalna osoba $duplicateTarget odpowiada kilku rekordom HRappka" }
        return HrImportPlan(export, items, employees)
    }

    fun personKey(firstName: String, lastName: String): String =
        (firstName.trim().replace(Regex("\\s+"), " ") + " " + lastName.trim().replace(Regex("\\s+"), " "))
            .lowercase(Locale.ROOT)
}

class HrSynchroImporter(private val database: AppDatabase) {
    suspend fun prepare(export: HrSynchroExport, assignments: Map<Long, HrManualAssignment> = emptyMap()): HrImportPlan =
        HrSynchroPlanner.prepare(export, database.employeeDao().getAllIncludingArchivedNow(), assignments)

    suspend fun import(export: HrSynchroExport, assignments: Map<Long, HrManualAssignment>): HrImportReport = database.withTransaction {
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
            if (item.decision == HrImportDecision.SKIP_DO_NOT_HIRE) {
                skipped++
                return@forEach
            }
            val source = item.source
            val existing = item.employeeId?.let { database.employeeDao().findById(it) }
            val cleanFirst = source.firstName.trim().replace(Regex("\\s+"), " ")
            val cleanLast = source.lastName.trim().replace(Regex("\\s+"), " ")
            val employee = if (existing == null) {
                EmployeeEntity(
                    id = UUID.randomUUID().toString(),
                    fullName = "$cleanFirst $cleanLast",
                    firstName = cleanFirst,
                    lastName = cleanLast,
                    hrappkaId = source.hrappkaId,
                    hrappkaExternalId = source.externalId,
                    hrappkaDoNotHire = source.doNotHire,
                ).also {
                    database.employeeDao().insert(it)
                    created++
                }
            } else {
                existing.copy(
                    fullName = "$cleanFirst $cleanLast",
                    firstName = cleanFirst,
                    lastName = cleanLast,
                    hrappkaId = source.hrappkaId,
                    hrappkaExternalId = source.externalId,
                    hrappkaDoNotHire = source.doNotHire,
                ).also {
                    database.employeeDao().update(it)
                    updated++
                    if (existing.hrappkaId == null) linked++
                }
            }

            when (source.phoneFetchStatus) {
                HrPhoneFetchStatus.NOT_FETCHED -> skippedNotFetched++
                HrPhoneFetchStatus.ERROR -> phoneErrors++
                HrPhoneFetchStatus.OK -> {
                    // Sam status „Nie zatrudniać” nie może usuwać ani zastępować telefonu osoby.
                    if (!source.doNotHire) {
                        val result = syncPhones(employee, source.phones)
                        phonesAdded += result.added
                        phonesRemoved += result.removed
                    }
                }
            }
        }
        HrImportReport(
            created = created,
            updated = updated,
            linked = linked,
            phonesAdded = phonesAdded,
            hrPhonesRemoved = phonesRemoved,
            phonesSkippedNotFetched = skippedNotFetched,
            phoneErrors = phoneErrors,
            doNotHire = plan.doNotHireCount,
            skipped = skipped,
        )
    }

    private suspend fun syncPhones(employee: EmployeeEntity, sourcePhones: List<String>): PhoneSyncResult {
        val existingTracked = database.hrappkaPhoneDao().findForEmployee(employee.id)
        val trackedKeys = existingTracked.map(EmployeeHrappkaPhoneEntity::normalizedNumber).toSet()
        val existingNumbers = splitPhones(employee.phoneNumbers).distinctBy(::normalizePhoneKey)
        val manualNumbers = existingNumbers.filter { normalizePhoneKey(it) !in trackedKeys }
        val manualKeys = manualNumbers.map(::normalizePhoneKey).toSet()
        val incoming = sourcePhones.map(::displayPhone).distinctBy(::normalizePhoneKey)
        val incomingKeys = incoming.map(::normalizePhoneKey).toSet()
        val existingKeys = existingNumbers.map(::normalizePhoneKey).toSet()
        val finalNumbers = (manualNumbers + incoming).distinctBy(::normalizePhoneKey)
        val finalTracked = incoming.filter { normalizePhoneKey(it) !in manualKeys }.map { number ->
            EmployeeHrappkaPhoneEntity(employee.id, normalizePhoneKey(number), number)
        }
        database.employeeDao().update(employee.copy(phoneNumbers = finalNumbers.joinToString(", ")))
        database.hrappkaPhoneDao().deleteForEmployee(employee.id)
        if (finalTracked.isNotEmpty()) database.hrappkaPhoneDao().upsert(finalTracked)
        return PhoneSyncResult(
            added = incomingKeys.count { it !in existingKeys },
            removed = trackedKeys.count { it !in incomingKeys },
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
    return if (digits.isEmpty()) display.lowercase(Locale.ROOT) else (if (display.startsWith('+')) "+" else "") + digits
}
