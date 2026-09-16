package pl.magazyn.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class HrSynchroContractParserTest {
    @Test
    fun acceptsCompleteExportAndIgnoresUnknownFields() {
        val parsed = HrSynchroContractParser.parse(json(phoneStatus = "ok", phonesComplete = true, extra = ",\"futureField\":123"))
        assertEquals(1, parsed.employeeCount)
        assertEquals(HrPhoneFetchStatus.OK, parsed.employees.single().phoneFetchStatus)
    }

    @Test
    fun acceptsRealisticPartialFixtureAndPreservesExternalIdsAsStrings() {
        val text = checkNotNull(javaClass.classLoader?.getResourceAsStream("magazynmobile-hr-import-v1-synthetic.json"))
            .bufferedReader().use { it.readText() }
        val parsed = HrSynchroContractParser.parse(text)
        assertFalse(parsed.completeness.phonesComplete)
        assertEquals("00000", parsed.employees.first { it.hrappkaId == 1001L }.externalId)
        assertEquals("17/04/2026", parsed.employees.first { it.hrappkaId == 1002L }.externalId)
    }

    @Test
    fun acceptsIncompleteEmployeeListWithoutTreatingItAsDeletionInstruction() {
        val parsed = HrSynchroContractParser.parse(json().replace("\"employeeListComplete\":true", "\"employeeListComplete\":false"))
        assertFalse(parsed.completeness.employeeListComplete)
        assertEquals(1, parsed.employees.size)
    }

    @Test
    fun rejectsUnsupportedFormat() {
        assertThrows(HrSynchroContractException::class.java) {
            HrSynchroContractParser.parse(json().replace(HR_SYNCHRO_FORMAT, "magazynmobile-hr-import-v2"))
        }
    }

    @Test
    fun rejectsEmployeeCountMismatch() {
        assertThrows(HrSynchroContractException::class.java) {
            HrSynchroContractParser.parse(json().replace("\"employeeCount\":1", "\"employeeCount\":2"))
        }
    }

    @Test
    fun rejectsDuplicateHrappkaId() {
        val first = employeeJson(123, "ok")
        val duplicate = baseJson("[$first,$first]", 2, 2, 0, 0, true)
        assertThrows(HrSynchroContractException::class.java) { HrSynchroContractParser.parse(duplicate) }
    }

    @Test
    fun rejectsUnknownPhoneFetchStatus() {
        assertThrows(HrSynchroContractException::class.java) { HrSynchroContractParser.parse(json(phoneStatus = "pending")) }
    }

    @Test
    fun rejectsInconsistentCompleteness() {
        assertThrows(HrSynchroContractException::class.java) {
            HrSynchroContractParser.parse(json(phoneStatus = "not_fetched", phonesComplete = false).replace("\"phoneProfilesNotFetched\":1", "\"phoneProfilesNotFetched\":0"))
        }
    }

    private fun json(phoneStatus: String = "ok", phonesComplete: Boolean = true, extra: String = ""): String {
        val fetched = if (phoneStatus == "ok") 1 else 0
        val notFetched = if (phoneStatus == "not_fetched") 1 else 0
        val failed = if (phoneStatus == "error") 1 else 0
        return baseJson("[${employeeJson(123, phoneStatus, extra)}]", 1, fetched, notFetched, failed, phonesComplete)
    }

    private fun employeeJson(id: Long, phoneStatus: String, extra: String = "") =
        """{"hrappkaId":$id,"externalId":"00000","firstName":"Jan","lastName":"Testowy","phones":["+48 500 000 001"],"phoneFetchStatus":"$phoneStatus","status":null$extra}"""

    private fun baseJson(employees: String, count: Int, fetched: Int, notFetched: Int, failed: Int, complete: Boolean) =
        """{"format":"$HR_SYNCHRO_FORMAT","exportedAt":"2026-09-16T20:00:00Z","employeeCount":$count,"completeness":{"employeeListComplete":true,"phoneProfilesTotal":$count,"phoneProfilesFetched":$fetched,"phoneProfilesNotFetched":$notFetched,"phoneProfilesFailed":$failed,"phonesComplete":$complete},"employees":$employees}"""
}
