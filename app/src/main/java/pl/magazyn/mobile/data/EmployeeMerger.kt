package pl.magazyn.mobile.data

import androidx.room.withTransaction
import pl.magazyn.mobile.domain.normalizeCommaSeparated
import pl.magazyn.mobile.domain.normalizeEmployeeName

class EmployeeMerger(private val database: AppDatabase) {
    suspend fun merge(targetId: String, sourceId: String) = database.withTransaction {
        require(targetId != sourceId) { "Nie można scalić osoby z nią samą" }

        val target = database.employeeDao().findById(targetId)
            ?: error("Nie znaleziono osoby docelowej")
        val source = database.employeeDao().findById(sourceId)
            ?: error("Nie znaleziono osoby do scalenia")
        require(!target.isArchived) { "Osoba docelowa jest archiwalna" }
        require(!source.isArchived) { "Osoba do scalenia jest archiwalna" }

        val targetTrackedPhones = database.hrappkaPhoneDao().findForEmployee(targetId)
        val sourceTrackedPhones = database.hrappkaPhoneDao().findForEmployee(sourceId)
        val targetManualPhones = manualPhones(target.phoneNumbers, targetTrackedPhones)
        val sourceManualPhones = manualPhones(source.phoneNumbers, sourceTrackedPhones)
        val manualPhones = (targetManualPhones + sourceManualPhones).distinctBy(::normalizePhoneKey)
        val manualKeys = manualPhones.map(::normalizePhoneKey).toSet()
        val retainedTrackedPhones = (targetTrackedPhones + sourceTrackedPhones)
            .filterNot { it.normalizedNumber in manualKeys }
            .map { it.copy(employeeId = targetId) }

        val normalizedTargetName = normalizeEmployeeName(target.firstName, target.lastName).fullName
        val normalizedSourceName = normalizeEmployeeName(source.firstName, source.lastName).fullName
        val sourceNameAlias = normalizedSourceName.takeIf {
            it.isNotBlank() && !it.equals(normalizedTargetName, ignoreCase = true)
        }.orEmpty()
        val mergedAliases = normalizeCommaSeparated(
            listOf(target.aliases, source.aliases, sourceNameAlias).filter(String::isNotBlank).joinToString(", "),
        )
        val mergedTags = normalizeCommaSeparated(
            listOf(target.tags, source.tags).filter(String::isNotBlank).joinToString(", "),
        )
        val mergedPhoneNumbers = (
            manualPhones + retainedTrackedPhones.map(EmployeeHrappkaPhoneEntity::displayNumber)
        ).distinctBy(::normalizePhoneKey).joinToString(", ")

        database.employeeDao().update(
            target.copy(
                phoneNumbers = mergedPhoneNumbers,
                aliases = mergedAliases,
                tags = mergedTags,
            ),
        )

        database.hrappkaPhoneDao().deleteForEmployee(targetId)
        database.hrappkaPhoneDao().deleteForEmployee(sourceId)
        database.employeeMergeDao().moveHrappkaLinks(sourceId, targetId)
        if (retainedTrackedPhones.isNotEmpty()) {
            database.hrappkaPhoneDao().upsert(retainedTrackedPhones)
        }

        val sourcePositionIds = database.employeeMergeDao().sourceJobPositionIds(sourceId)
        if (sourcePositionIds.isNotEmpty()) {
            database.jobPositionDao().link(sourcePositionIds.map { EmployeeJobPositionEntity(targetId, it) })
        }
        database.employeeMergeDao().deleteSourceJobPositions(sourceId)
        val sourceShipyardIds = database.employeeMergeDao().sourceShipyardIds(sourceId)
        if (sourceShipyardIds.isNotEmpty()) {
            database.shipyardDao().insertLeaders(sourceShipyardIds.map { ShipyardLeaderEntity(it, targetId) })
        }
        database.employeeMergeDao().deleteSourceShipyardLeaderships(sourceId)
        database.employeeMergeDao().moveStockMovements(sourceId, targetId)
        database.employeeMergeDao().moveCustodies(sourceId, targetId)
        database.employeeMergeDao().moveOrders(sourceId, targetId)
        database.employeeMergeDao().moveNotebookTasks(sourceId, targetId)
        val sourceTaskIds = database.employeeMergeDao().sourceNotebookTaskIds(sourceId)
        if (sourceTaskIds.isNotEmpty()) {
            database.notebookDao().insertTaskEmployees(sourceTaskIds.map { NotebookTaskEmployeeEntity(it, targetId) })
        }
        database.employeeMergeDao().deleteSourceNotebookTaskEmployees(sourceId)
        database.employeeMergeDao().moveNotebookTaskStepPeople(sourceId, targetId)

        database.employeeDao().update(source.copy(isArchived = true))
    }

    private fun manualPhones(
        phoneNumbers: String,
        trackedPhones: List<EmployeeHrappkaPhoneEntity>,
    ): List<String> {
        val trackedKeys = trackedPhones.map(EmployeeHrappkaPhoneEntity::normalizedNumber).toSet()
        return splitPhones(phoneNumbers)
            .distinctBy(::normalizePhoneKey)
            .filter { normalizePhoneKey(it) !in trackedKeys }
    }
}
