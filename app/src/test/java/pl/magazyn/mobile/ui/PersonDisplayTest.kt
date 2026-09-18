package pl.magazyn.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pl.magazyn.mobile.data.EmployeeSummary

class PersonDisplayTest {
    private fun person(phones: String = "") = EmployeeSummary(
        id = "employee",
        fullName = "Jan Kowalski",
        firstName = "Jan",
        lastName = "Kowalski",
        phoneNumbers = phones,
        aliases = "Kowal",
        tags = "spawacz",
        positions = "Monter",
    )

    @Test
    fun phoneListHandlesNoneOneManyAndDeduplicatesFormattingVariants() {
        assertTrue(distinctPersonPhoneNumbers("").isEmpty())
        assertEquals(listOf("123 456 789"), distinctPersonPhoneNumbers("123 456 789"))
        assertEquals(
            listOf("123 456 789", "+47 99 99 99 99"),
            distinctPersonPhoneNumbers("123 456 789, 123-456-789, +47 99 99 99 99"),
        )
    }

    @Test
    fun phoneSearchIgnoresFormattingButDoesNotGuessCountryPrefix() {
        val employee = person("123 456 789")
        listOf("123456789", "123 456 789", "123-456-789", "(123) 456 789").forEach { query ->
            assertTrue("query=$query", employee.matchesPersonSearch(query))
        }
        assertTrue(person("+48 123 456 789").matchesPersonSearch("+48 (123) 456-789"))
        assertFalse(person("+48 123 456 789").matchesPersonSearch("123456789"))
    }

    @Test
    fun nameAndSurnameSearchRemainAvailable() {
        val employee = person("123 456 789")
        assertTrue(employee.matchesPersonSearch("Jan"))
        assertTrue(employee.matchesPersonSearch("Kowalski"))
        assertTrue(employee.matchesPersonSearch("Kowal"))
        assertFalse(employee.matchesPersonSearch("Nowak"))
    }
}
