package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.magazyn.mobile.IsolatedApplicationEnvironment
import pl.magazyn.mobile.queryLong
import pl.magazyn.mobile.runAndAwaitViewModelWork
import pl.magazyn.mobile.seedCoreData
import pl.magazyn.mobile.ui.IssueRequest
import pl.magazyn.mobile.ui.PeopleViewModel

@RunWith(AndroidJUnit4::class)
class SpecificIssueReturnTest {
    private lateinit var environment: IsolatedApplicationEnvironment
    private lateinit var database: AppDatabase
    private lateinit var viewModel: PeopleViewModel

    @Before
    fun setup() = runBlocking {
        environment = IsolatedApplicationEnvironment.create()
        database = environment.database
        database.seedCoreData(stock = 10.0)
        viewModel = PeopleViewModel(environment.application)
    }

    @After
    fun cleanup() {
        environment.close()
    }

    @Test
    fun returningSecondIssueClosesOnlyItsCustodyAndCannotBeRepeated() = runBlocking {
        val (issueA, issueB) = issueSameProductTwice()

        viewModel.runAndAwaitViewModelWork {
            viewModel.returnIssue("employee-1", issueB, 1, "2026-09-11")
        }

        assertCustodyState(issueA.movementId, active = true)
        assertCustodyState(issueB.movementId, active = false)
        assertEquals(9.0, stock(), 0.0)
        assertEquals(1L, returnMovementCount())
        assertSingleReturnHistory()
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM issue_returns WHERE originalLineId=?", issueB.lineId))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM issue_returns WHERE originalLineId=?", issueA.lineId))

        viewModel.runAndAwaitViewModelWork {
            viewModel.returnIssue("employee-1", issueB, 1, "2026-09-12")
        }

        assertEquals(9.0, stock(), 0.0)
        assertEquals(1L, returnMovementCount())
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM issue_returns WHERE originalLineId=?", issueB.lineId))
    }

    @Test
    fun returningFirstIssueLeavesSecondCustodyActive() = runBlocking {
        val (issueA, issueB) = issueSameProductTwice()

        viewModel.runAndAwaitViewModelWork {
            viewModel.returnIssue("employee-1", issueA, 1, "2026-09-11")
        }

        assertCustodyState(issueA.movementId, active = false)
        assertCustodyState(issueB.movementId, active = true)
        assertEquals(9.0, stock(), 0.0)
        assertEquals(1L, returnMovementCount())
        assertSingleReturnHistory()
        assertEquals(1L, database.queryLong("SELECT COUNT(*) FROM issue_returns WHERE originalLineId=?", issueA.lineId))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM issue_returns WHERE originalLineId=?", issueB.lineId))
    }

    @Test
    fun ambiguousDuplicateProductLinesInOneMovementAreNotReturnedByGuessing() = runBlocking {
        viewModel.runAndAwaitViewModelWork {
            viewModel.issueToPerson(
                "employee-1",
                listOf(IssueRequest("product-1", 1), IssueRequest("product-1", 1)),
                "2026-09-01",
            )
        }
        val issue = database.movementDao().observeEmployeeIssues("employee-1").first { it.size == 2 }.first()

        viewModel.runAndAwaitViewModelWork {
            viewModel.returnIssue("employee-1", issue, 1, "2026-09-11")
        }

        assertEquals(8.0, stock(), 0.0)
        assertEquals(0L, returnMovementCount())
        assertEquals(2L, database.queryLong("SELECT COUNT(*) FROM custodies WHERE returnedDate IS NULL"))
        assertEquals(0L, database.queryLong("SELECT COUNT(*) FROM issue_returns"))
    }

    private suspend fun issueSameProductTwice(): Pair<EmployeeIssue, EmployeeIssue> {
        viewModel.runAndAwaitViewModelWork {
            viewModel.issueToPerson("employee-1", listOf(IssueRequest("product-1", 1)), "2026-09-01")
        }
        viewModel.runAndAwaitViewModelWork {
            viewModel.issueToPerson("employee-1", listOf(IssueRequest("product-1", 1)), "2026-09-10")
        }
        val issues = database.movementDao().observeEmployeeIssues("employee-1").first { it.size == 2 }
        return checkNotNull(issues.firstOrNull { it.effectiveDate == "2026-09-01" }) to
            checkNotNull(issues.firstOrNull { it.effectiveDate == "2026-09-10" })
    }

    private fun assertCustodyState(movementId: String, active: Boolean) {
        assertEquals(
            if (active) 1L else 0L,
            database.queryLong(
                "SELECT COUNT(*) FROM custodies WHERE issuedMovementId=? AND returnedDate IS NULL",
                movementId,
            ),
        )
    }

    private suspend fun stock(): Double =
        database.stockDao().find("warehouse-main", "product-1")?.quantity ?: Double.NaN

    private fun returnMovementCount(): Long =
        database.queryLong("SELECT COUNT(*) FROM stock_movements WHERE type='RETURN'")

    private fun assertSingleReturnHistory() {
        assertEquals(
            1L,
            database.queryLong(
                """
                SELECT COUNT(*)
                FROM stock_movements m
                JOIN stock_movement_lines l ON l.movementId = m.id
                WHERE m.type = 'RETURN'
                  AND m.employeeId = 'employee-1'
                  AND l.productId = 'product-1'
                  AND l.quantityDelta = 1.0
                """.trimIndent(),
            ),
        )
    }
}
