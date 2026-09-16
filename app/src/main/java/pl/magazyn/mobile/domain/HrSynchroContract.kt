package pl.magazyn.mobile.domain

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

const val HR_SYNCHRO_FORMAT = "magazynmobile-hr-import-v1"

enum class HrPhoneFetchStatus(val wireValue: String) {
    OK("ok"),
    NOT_FETCHED("not_fetched"),
    ERROR("error");

    companion object {
        fun fromWire(value: String): HrPhoneFetchStatus? = entries.firstOrNull { it.wireValue == value }
    }
}

data class HrSynchroCompleteness(
    val employeeListComplete: Boolean,
    val phoneProfilesTotal: Int,
    val phoneProfilesFetched: Int,
    val phoneProfilesNotFetched: Int,
    val phoneProfilesFailed: Int,
    val phonesComplete: Boolean,
)

data class HrSynchroEmployee(
    val hrappkaId: Long,
    val externalId: String?,
    val firstName: String,
    val lastName: String,
    val phones: List<String>,
    val phoneFetchStatus: HrPhoneFetchStatus,
    val status: String?,
) {
    val doNotHire: Boolean get() = status?.trim()?.equals("Nie zatrudniać", ignoreCase = true) == true
}

data class HrSynchroExport(
    val exportedAt: String,
    val employeeCount: Int,
    val completeness: HrSynchroCompleteness,
    val employees: List<HrSynchroEmployee>,
)

class HrSynchroContractException(message: String) : IllegalArgumentException(message)

object HrSynchroContractParser {
    fun parse(text: String): HrSynchroExport {
        val root = try {
            JSONObject(text)
        } catch (error: Exception) {
            throw HrSynchroContractException("Etap PARSING: plik nie jest poprawnym JSON (${error.message ?: error.javaClass.simpleName})")
        }
        val format = root.requiredString("format")
        if (format != HR_SYNCHRO_FORMAT) {
            throw HrSynchroContractException("Etap VALIDATION: Nieobsługiwana wersja pliku Synchro: $format")
        }
        val exportedAt = root.requiredString("exportedAt")
        try {
            Instant.parse(exportedAt)
        } catch (_: Exception) {
            throw HrSynchroContractException("Etap VALIDATION: exportedAt nie jest poprawną datą ISO-8601: $exportedAt")
        }
        val employeeCount = root.requiredNonNegativeInt("employeeCount")
        val employeeArray = root.requiredArray("employees")
        if (employeeCount != employeeArray.length()) {
            throw HrSynchroContractException("Etap VALIDATION: employeeCount=$employeeCount, ale employees.size=${employeeArray.length()}")
        }
        val completenessObject = root.requiredObject("completeness")
        val completeness = HrSynchroCompleteness(
            employeeListComplete = completenessObject.requiredBoolean("employeeListComplete"),
            phoneProfilesTotal = completenessObject.requiredNonNegativeInt("phoneProfilesTotal"),
            phoneProfilesFetched = completenessObject.requiredNonNegativeInt("phoneProfilesFetched"),
            phoneProfilesNotFetched = completenessObject.requiredNonNegativeInt("phoneProfilesNotFetched"),
            phoneProfilesFailed = completenessObject.requiredNonNegativeInt("phoneProfilesFailed"),
            phonesComplete = completenessObject.requiredBoolean("phonesComplete"),
        )
        val employees = buildList {
            repeat(employeeArray.length()) { index ->
                val employee = employeeArray.opt(index) as? JSONObject
                    ?: throw recordError(index, null, "rekord nie jest obiektem")
                val id = employee.requiredLong("hrappkaId", index)
                val phoneStatusValue = employee.requiredString("phoneFetchStatus", index, id)
                val phoneStatus = HrPhoneFetchStatus.fromWire(phoneStatusValue)
                    ?: throw recordError(index, id, "nieprawidłowy phoneFetchStatus=$phoneStatusValue")
                val phonesArray = employee.requiredArray("phones", index, id)
                val phones = buildList {
                    repeat(phonesArray.length()) { phoneIndex ->
                        val phone = phonesArray.opt(phoneIndex)
                        if (phone !is String) throw recordError(index, id, "phones[$phoneIndex] nie jest stringiem")
                        if (phone.trim().isEmpty()) throw recordError(index, id, "phones[$phoneIndex] jest pusty")
                        add(phone.trim().replace(Regex("\\s+"), " "))
                    }
                }
                add(
                    HrSynchroEmployee(
                        hrappkaId = id,
                        externalId = employee.nullableString("externalId", index, id),
                        firstName = employee.requiredString("firstName", index, id).normalizedWhitespace(),
                        lastName = employee.requiredString("lastName", index, id).normalizedWhitespace(),
                        phones = phones,
                        phoneFetchStatus = phoneStatus,
                        status = employee.nullableString("status", index, id)?.normalizedWhitespace(),
                    ),
                )
            }
        }
        employees.forEachIndexed { index, employee ->
            if (employee.firstName.isBlank()) throw recordError(index, employee.hrappkaId, "firstName jest pusty")
            if (employee.lastName.isBlank()) throw recordError(index, employee.hrappkaId, "lastName jest pusty")
        }
        val duplicateId = employees.groupingBy(HrSynchroEmployee::hrappkaId).eachCount().entries.firstOrNull { it.value > 1 }?.key
        if (duplicateId != null) throw HrSynchroContractException("Etap VALIDATION: duplikat hrappkaId=$duplicateId")

        val actualFetched = employees.count { it.phoneFetchStatus == HrPhoneFetchStatus.OK }
        val actualNotFetched = employees.count { it.phoneFetchStatus == HrPhoneFetchStatus.NOT_FETCHED }
        val actualFailed = employees.count { it.phoneFetchStatus == HrPhoneFetchStatus.ERROR }
        if (completeness.phoneProfilesTotal != employeeCount ||
            completeness.phoneProfilesFetched != actualFetched ||
            completeness.phoneProfilesNotFetched != actualNotFetched ||
            completeness.phoneProfilesFailed != actualFailed ||
            actualFetched + actualNotFetched + actualFailed != employeeCount
        ) {
            throw HrSynchroContractException(
                "Etap VALIDATION: completeness nie zgadza się z rekordami " +
                    "(total=${completeness.phoneProfilesTotal}/$employeeCount, fetched=${completeness.phoneProfilesFetched}/$actualFetched, " +
                    "notFetched=${completeness.phoneProfilesNotFetched}/$actualNotFetched, failed=${completeness.phoneProfilesFailed}/$actualFailed)",
            )
        }
        val actuallyComplete = actualFetched == employeeCount && actualNotFetched == 0 && actualFailed == 0
        if (completeness.phonesComplete != actuallyComplete) {
            throw HrSynchroContractException("Etap VALIDATION: phonesComplete=${completeness.phonesComplete}, a liczniki profili wskazują $actuallyComplete")
        }
        return HrSynchroExport(exportedAt, employeeCount, completeness, employees)
    }

    private fun JSONObject.requiredString(name: String, index: Int? = null, id: Long? = null): String {
        val value = opt(name)
        if (value !is String) throw fieldError(index, id, "$name nie jest stringiem")
        return value
    }

    private fun JSONObject.nullableString(name: String, index: Int, id: Long): String? {
        if (!has(name)) throw recordError(index, id, "$name nie istnieje")
        if (isNull(name)) return null
        val value = opt(name)
        if (value !is String) throw recordError(index, id, "$name nie jest string|null")
        return value
    }

    private fun JSONObject.requiredArray(name: String, index: Int? = null, id: Long? = null): JSONArray =
        opt(name) as? JSONArray ?: throw fieldError(index, id, "$name nie jest tablicą")

    private fun JSONObject.requiredObject(name: String): JSONObject =
        opt(name) as? JSONObject ?: throw HrSynchroContractException("Etap VALIDATION: $name nie jest obiektem")

    private fun JSONObject.requiredBoolean(name: String): Boolean =
        opt(name) as? Boolean ?: throw HrSynchroContractException("Etap VALIDATION: completeness.$name nie jest boolean")

    private fun JSONObject.requiredNonNegativeInt(name: String): Int {
        val value = opt(name)
        val number = value as? Number ?: throw HrSynchroContractException("Etap VALIDATION: $name nie jest liczbą")
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in 0..Int.MAX_VALUE) {
            throw HrSynchroContractException("Etap VALIDATION: $name nie jest nieujemną liczbą całkowitą")
        }
        return long.toInt()
    }

    private fun JSONObject.requiredLong(name: String, index: Int): Long {
        val value = opt(name)
        val number = value as? Number ?: throw recordError(index, null, "$name nie jest liczbą całkowitą")
        val long = number.toLong()
        if (number.toDouble() != long.toDouble()) throw recordError(index, null, "$name nie jest liczbą całkowitą")
        return long
    }

    private fun fieldError(index: Int?, id: Long?, detail: String): HrSynchroContractException =
        if (index == null) HrSynchroContractException("Etap VALIDATION: $detail") else recordError(index, id, detail)

    private fun recordError(index: Int, id: Long?, detail: String) = HrSynchroContractException(
        "Etap VALIDATION: rekord employees[$index]${id?.let { ", hrappkaId=$it" }.orEmpty()}: $detail",
    )

    private fun String.normalizedWhitespace(): String = trim().replace(Regex("\\s+"), " ")
}
