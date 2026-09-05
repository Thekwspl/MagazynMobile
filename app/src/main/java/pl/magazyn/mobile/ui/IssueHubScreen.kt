package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun IssueHubScreen(
    contentPadding: PaddingValues,
    onPerson: (String) -> Unit,
    onShipyard: () -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val tokens = query.trim().split(Regex("\\s+")).filter(String::isNotBlank)
    val visible = if (tokens.isEmpty()) people else people.filter { person ->
        pl.magazyn.mobile.domain.matchesSearch(query, person.fullName, person.phoneNumbers, person.positions, person.aliases, person.tags)
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text("Wydaj", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        FilledTonalButton(onClick = onShipyard, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Icon(Icons.Default.Business, null)
            Column(Modifier.padding(start = 10.dp).weight(1f), horizontalAlignment = Alignment.Start) {
                Text("Wydaj stoczni", fontWeight = FontWeight.SemiBold)
                Text("Wybierz stocznię i dodaj przedmioty", style = MaterialTheme.typography.labelSmall)
            }
        }
        Text("Wydaj osobie", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Znajdź osobę") },
            placeholder = { Text("Imię, nazwisko, telefon, stanowisko lub alias") },
            singleLine = true,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            items(visible, key = { it.id }) { person ->
                OutlinedCard(onClick = { onPerson(person.id) }, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Person, null)
                        Column(Modifier.padding(start = 10.dp).weight(1f)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text(person.listDisplayName(), fontWeight = FontWeight.SemiBold)
                                if (person.phoneNumbers.isNotBlank()) PhoneNumbersInline(person.phoneNumbers)
                            }
                            if (person.positions.isNotBlank()) Text(person.positions, style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
        }
    }
}
