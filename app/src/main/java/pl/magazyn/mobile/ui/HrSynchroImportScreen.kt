package pl.magazyn.mobile.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.data.EmployeeEntity
import pl.magazyn.mobile.data.HrImportDecision
import pl.magazyn.mobile.data.HrImportPlanItem

private data class AdditionalHrLinkConfirmation(
    val item: HrImportPlanItem,
    val employee: EmployeeEntity,
    val existingIds: List<Long>,
)

@Composable
fun HrSynchroImportScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    viewModel: HrSynchroImportViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var assigning by remember { mutableStateOf<HrImportPlanItem?>(null) }
    var additionalLinkConfirmation by remember { mutableStateOf<AdditionalHrLinkConfirmation?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(viewModel::load) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        BackScreenHeader("HRappka / Synchro", "Bezpieczny import pracowników z pliku JSON", onBack)
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Button(
                    onClick = { picker.launch(arrayOf("application/json", "text/json", "text/plain")) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.loading,
                ) {
                    Icon(Icons.Default.FileOpen, null)
                    Spacer(Modifier.padding(3.dp))
                    Text("Importuj dane z Synchro")
                }
            }
            if (state.loading) item { CircularProgressIndicator(Modifier.padding(12.dp)) }
            state.error?.let { error -> item { HrMessageCard(error, warning = true) } }
            state.plan?.let { plan ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("Podgląd importu", style = MaterialTheme.typography.titleLarge)
                            HrSummaryLine("Pracownicy w pliku", plan.export.employeeCount.toString())
                            HrSummaryLine("Nowi", plan.newCount.toString())
                            HrSummaryLine("Aktualizacje", plan.updateCount.toString())
                            HrSummaryLine("Automatycznie powiązani", plan.autoLinkedCount.toString())
                            HrSummaryLine("Wymagają przypisania", plan.needsAssignmentCount.toString())
                            HrSummaryLine("Nie zatrudniać", plan.doNotHireCount.toString())
                            HrSummaryLine("Pominięto — Nie zatrudniać", plan.skippedDoNotHireCount.toString())
                            HrSummaryLine("Pominięto ręcznie", plan.skippedManualCount.toString())
                            HrSummaryLine("Błędne rekordy", "0")
                            HorizontalDivider()
                            Text("Telefony", fontWeight = FontWeight.SemiBold)
                            HrSummaryLine("Pobrane profile", plan.export.completeness.phoneProfilesFetched.toString())
                            HrSummaryLine("Niepobrane", plan.export.completeness.phoneProfilesNotFetched.toString())
                            HrSummaryLine("Błędy", plan.export.completeness.phoneProfilesFailed.toString())
                            HrSummaryLine("Kompletne", if (plan.export.completeness.phonesComplete) "TAK" else "NIE")
                            HrSummaryLine("Data eksportu", plan.export.exportedAt)
                        }
                    }
                }
                if (!plan.export.completeness.employeeListComplete) {
                    item { HrMessageCard("Lista pracowników w eksporcie jest niepełna. Brakujące osoby nie zostaną usunięte ani dezaktywowane.", warning = true) }
                }
                if (!plan.export.completeness.phonesComplete) {
                    item {
                        HrMessageCard(
                            "Eksport nie zawiera kompletnych danych telefonów. Niepobrane lub błędne profile nie zmienią istniejących numerów telefonu w MagazynMobile.",
                            warning = true,
                        )
                    }
                }
                val autoTargets = plan.items.filter { it.decision == HrImportDecision.AUTO_LINK && it.employeeId != null }
                    .groupBy { it.employeeId }
                val additionalAutoLinks = autoTargets.filter { (employeeId, items) ->
                    items.size > 1 || plan.localLinks.any { it.employeeId == employeeId }
                }
                if (additionalAutoLinks.isNotEmpty()) {
                    item { Text("Proponowane dodatkowe ID HRappka", style = MaterialTheme.typography.titleMedium) }
                    items(additionalAutoLinks.entries.toList(), key = { "auto-${it.key}" }) { entry ->
                        val employee = plan.localEmployees.first { it.id == entry.key }
                        val existingIds = plan.localLinks.filter { it.employeeId == employee.id }.map { it.hrappkaId }
                        val newIds = entry.value.map { it.source.hrappkaId }
                        HrMessageCard(
                            "${employee.lastName} ${employee.firstName}: istniejące ID ${if (existingIds.isEmpty()) "brak" else existingIds.joinToString()}, nowe ID ${newIds.joinToString()}. Powiązania zostaną dodane dopiero po zatwierdzeniu importu.",
                            warning = true,
                        )
                    }
                }
                val unresolved = plan.items.filter { it.decision == HrImportDecision.NEEDS_ASSIGNMENT }
                if (unresolved.isNotEmpty()) {
                    item { Text("Wymagają ręcznego przypisania", style = MaterialTheme.typography.titleMedium) }
                    items(unresolved, key = { it.source.hrappkaId }) { item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${item.source.lastName} ${item.source.firstName}", fontWeight = FontWeight.SemiBold)
                                Text("HRappka ID: ${item.source.hrappkaId}", style = MaterialTheme.typography.labelSmall)
                                if (item.source.phones.isNotEmpty()) Text(item.source.phones.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    if (item.candidateIds.size > 1) "Znaleziono kilka osób o tej samej nazwie."
                                    else "Rekord wymaga wskazania właściwej osoby.",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                OutlinedButton(onClick = { assigning = item }, modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Default.Link, null)
                                    Text("Przypisz")
                                }
                                TextButton(
                                    onClick = { viewModel.skipForThisImport(item.source.hrappkaId) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) { Text("Pomiń w tym imporcie") }
                            }
                        }
                    }
                }
                val skippedDoNotHire = plan.items.filter { it.decision == HrImportDecision.SKIP_DO_NOT_HIRE }
                if (skippedDoNotHire.isNotEmpty()) {
                    item { Text("Automatycznie pominięte — Nie zatrudniać", style = MaterialTheme.typography.titleMedium) }
                    items(skippedDoNotHire, key = { "skip-${it.source.hrappkaId}" }) { item ->
                        OutlinedCard(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                Text("${item.source.lastName} ${item.source.firstName}", fontWeight = FontWeight.SemiBold)
                                Text("HRappka ID: ${item.source.hrappkaId}", style = MaterialTheme.typography.labelSmall)
                                Text("Nie utworzono nowej osoby. Możesz świadomie przypisać rekord do istniejącej.", style = MaterialTheme.typography.bodySmall)
                                OutlinedButton(onClick = { assigning = item }, modifier = Modifier.fillMaxWidth()) {
                                    Text("Przypisz istniejącej osobie")
                                }
                            }
                        }
                    }
                }
                item {
                    Button(
                        onClick = viewModel::confirmImport,
                        enabled = !state.loading && plan.needsAssignmentCount == 0,
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Zatwierdź import") }
                }
                item { TextButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Wybierz inny plik") } }
            }
            state.report?.let { report ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
                                Text(" Import zakończony", style = MaterialTheme.typography.titleLarge)
                            }
                            HrSummaryLine("Utworzono", report.created.toString())
                            HrSummaryLine("Zaktualizowano", report.updated.toString())
                            HrSummaryLine("Powiązano", report.linked.toString())
                            HrSummaryLine("Telefony dodane", report.phonesAdded.toString())
                            HrSummaryLine("Telefony usunięte jako stare HRappka", report.hrPhonesRemoved.toString())
                            HrSummaryLine("Telefony pominięte przez not_fetched", report.phonesSkippedNotFetched.toString())
                            HrSummaryLine("Błędy telefonów", report.phoneErrors.toString())
                            HrSummaryLine("Nie zatrudniać", report.doNotHire.toString())
                            HrSummaryLine("Pominięte/nieprawidłowe", report.skipped.toString())
                        }
                    }
                }
                item { TextButton(onClick = viewModel::reset, modifier = Modifier.fillMaxWidth()) { Text("Importuj kolejny plik") } }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    assigning?.let { item ->
        HrAssignmentDialog(
            item = item,
            employees = state.plan?.localEmployees.orEmpty(),
            onDismiss = { assigning = null },
            onEmployee = { employeeId ->
                assigning = null
                val plan = state.plan
                val employee = plan?.localEmployees?.firstOrNull { it.id == employeeId }
                if (plan != null && employee != null) {
                    val persistedIds = plan.localLinks.filter { it.employeeId == employeeId }.map { it.hrappkaId }
                    val plannedIds = state.assignments.filterValues { it.employeeId == employeeId }.keys
                    val otherIds = (persistedIds + plannedIds).filter { it != item.source.hrappkaId }.distinct().sorted()
                    if (otherIds.isEmpty()) viewModel.assignToEmployee(item.source.hrappkaId, employeeId)
                    else additionalLinkConfirmation = AdditionalHrLinkConfirmation(item, employee, otherIds)
                }
            },
            onCreate = { assigning = null; viewModel.createAsNew(item.source.hrappkaId) },
            onSkip = { assigning = null; viewModel.skipForThisImport(item.source.hrappkaId) },
        )
    }
    additionalLinkConfirmation?.let { confirmation ->
        AlertDialog(
            onDismissRequest = { additionalLinkConfirmation = null },
            title = { Text("Dodać kolejne ID HRappka?") },
            text = {
                Text(
                    "${confirmation.employee.lastName} ${confirmation.employee.firstName} ma już powiązania: " +
                        confirmation.existingIds.joinToString() +
                        ". Nowe ID: ${confirmation.item.source.hrappkaId}.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.assignToEmployee(confirmation.item.source.hrappkaId, confirmation.employee.id)
                    additionalLinkConfirmation = null
                }) { Text("Dodaj kolejne ID") }
            },
            dismissButton = { TextButton(onClick = { additionalLinkConfirmation = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun HrAssignmentDialog(
    item: HrImportPlanItem,
    employees: List<EmployeeEntity>,
    onDismiss: () -> Unit,
    onEmployee: (String) -> Unit,
    onCreate: () -> Unit,
    onSkip: () -> Unit,
) {
    var query by rememberSaveable(item.source.hrappkaId) { mutableStateOf("") }
    val matches = remember(query, employees) {
        employees.filter { employee ->
            !employee.isArchived &&
                (query.isBlank() || listOf(employee.firstName, employee.lastName, employee.phoneNumbers).any { it.contains(query, true) })
        }.sortedWith(compareBy<EmployeeEntity> { it.lastName.lowercase() }.thenBy { it.firstName.lowercase() })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Przypisz ${item.source.lastName} ${item.source.firstName}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("Szukaj osoby") }, singleLine = true)
                LazyColumn(Modifier.fillMaxWidth().height(260.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(matches.take(50), key = { it.id }) { employee ->
                        TextButton(onClick = { onEmployee(employee.id) }, modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.fillMaxWidth()) {
                                Text("${employee.lastName} ${employee.firstName}", fontWeight = FontWeight.SemiBold)
                                if (employee.phoneNumbers.isNotBlank()) Text(employee.phoneNumbers, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                if (!item.source.doNotHire) OutlinedButton(onClick = onCreate, modifier = Modifier.fillMaxWidth()) { Text("Utwórz jako nową") }
                else Text("Status „Nie zatrudniać” nie pozwala utworzyć nowej osoby.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Pomiń w tym imporcie") }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

@Composable
private fun HrSummaryLine(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f))
        Text(value, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun HrMessageCard(text: String, warning: Boolean) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(if (warning) Icons.Default.Warning else Icons.Default.CheckCircle, null, tint = if (warning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            SelectionContainer(Modifier.weight(1f)) { Text(text) }
        }
    }
}
