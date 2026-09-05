package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.data.ParserLearningRuleEntity

@Composable
fun LearningRulesScreen(contentPadding: PaddingValues, viewModel: LearningRulesViewModel = viewModel()) {
    val rules by viewModel.rules.collectAsStateWithLifecycle()
    var edited by remember { mutableStateOf<ParserLearningRuleEntity?>(null) }
    var adding by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf<ParserLearningRuleEntity?>(null) }
    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("Uczenie offline", style = MaterialTheme.typography.headlineSmall)
                Text("Reguły używane bez internetu i bez AI", style = MaterialTheme.typography.bodySmall)
            }
            FilledTonalButton(onClick = { adding = true }) { Icon(Icons.Default.Add, null); Text("Dodaj") }
        }
        if (rules.isEmpty()) Text("Brak zapisanych reguł. Powstaną po zatwierdzeniu poprawek albo możesz dodać je ręcznie.", Modifier.padding(16.dp))
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(rules, key = { it.id }) { rule ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Row(Modifier.fillMaxWidth().padding(12.dp)) {
                        Column(Modifier.weight(1f)) {
                            Text(ruleTypeLabel(rule.ruleType), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                            Text(rule.sourceLabel, fontWeight = FontWeight.SemiBold)
                            Text("→ ${rule.learnedName}${rule.learnedVariant?.let { " · $it" }.orEmpty()}${if (rule.ruleType == "PRODUCT") " · ${rule.learnedUnit}" else ""}")
                            Text("Potwierdzenia: ${rule.confirmations}", style = MaterialTheme.typography.labelSmall)
                        }
                        IconButton(onClick = { edited = rule }) { Icon(Icons.Default.Edit, "Edytuj") }
                        IconButton(onClick = { deleting = rule }) { Icon(Icons.Default.DeleteOutline, "Usuń") }
                    }
                }
            }
        }
    }
    if (adding || edited != null) RuleEditor(edited, { adding = false; edited = null }) { existing, type, source, target, variant, unit ->
        viewModel.save(existing, type, source, target, variant, unit); adding = false; edited = null
    }
    deleting?.let { rule ->
        AlertDialog(
            onDismissRequest = { deleting = null }, title = { Text("Usunąć regułę?") },
            text = { Text("Parser offline przestanie zamieniać „${rule.sourceLabel}” na „${rule.learnedName}”.") },
            confirmButton = { Button(onClick = { viewModel.delete(rule.id); deleting = null }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Usuń") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Anuluj") } },
        )
    }
}

@Composable
private fun RuleEditor(rule: ParserLearningRuleEntity?, onDismiss: () -> Unit, onSave: (ParserLearningRuleEntity?, String, String, String, String, String) -> Unit) {
    var type by rememberSaveable(rule?.id) { mutableStateOf(rule?.ruleType ?: "PRODUCT") }
    var source by rememberSaveable(rule?.id) { mutableStateOf(rule?.sourceLabel.orEmpty()) }
    var target by rememberSaveable(rule?.id) { mutableStateOf(rule?.learnedName.orEmpty()) }
    var variant by rememberSaveable(rule?.id) { mutableStateOf(rule?.learnedVariant.orEmpty()) }
    var unit by rememberSaveable(rule?.id) { mutableStateOf(rule?.learnedUnit ?: "szt.") }
    var typeMenu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (rule == null) "Nowa reguła" else "Edytuj regułę") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.fillMaxWidth()) {
                OutlinedButton(onClick = { typeMenu = true }, Modifier.fillMaxWidth()) { Text("Typ: ${ruleTypeLabel(type)}") }
                DropdownMenu(typeMenu, { typeMenu = false }) {
                    listOf("PRODUCT", "PERSON", "POSITION", "PATTERN").forEach { choice -> DropdownMenuItem(text = { Text(ruleTypeLabel(choice)) }, onClick = { type = choice; typeMenu = false }) }
                }
            }
            OutlinedTextField(source, { source = it }, Modifier.fillMaxWidth(), label = { Text("Tekst z notatki") })
            OutlinedTextField(target, { target = it }, Modifier.fillMaxWidth(), label = { Text(when (type) { "PERSON" -> "Pełne imię i nazwisko"; "POSITION" -> "Poprawna nazwa stanowiska"; "PATTERN" -> "Typ: ORDER, TASK, CONTACT lub NOTE"; else -> "Poprawna nazwa przedmiotu" }) })
            if (type == "PRODUCT") {
                OutlinedTextField(variant, { variant = it }, Modifier.fillMaxWidth(), label = { Text("Wariant (opcjonalnie)") })
                OutlinedTextField(unit, { unit = it }, Modifier.fillMaxWidth(), label = { Text("Jednostka") })
            }
        } },
        confirmButton = { Button(onClick = { onSave(rule, type, source, target, variant, unit) }, enabled = source.isNotBlank() && target.isNotBlank() && (type != "PRODUCT" || unit.isNotBlank())) { Text("Zapisz") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Anuluj") } },
    )
}

private fun ruleTypeLabel(type: String): String = when (type) {
    "PERSON" -> "Osoba / ksywka"
    "POSITION" -> "Stanowisko"
    "PATTERN" -> "Schemat wiadomości"
    else -> "Przedmiot"
}
