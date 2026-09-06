package pl.magazyn.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pl.magazyn.mobile.domain.ImportParser
import pl.magazyn.mobile.domain.matchesSearch

@Composable
fun SearchScreen(
    contentPadding: PaddingValues,
    onPerson: (String) -> Unit,
    onIssuePerson: (String) -> Unit,
    onProduct: (String) -> Unit,
    onShipyard: (String?) -> Unit,
    viewModel: SearchViewModel = viewModel(),
) {
    val people by viewModel.people.collectAsStateWithLifecycle()
    val products by viewModel.products.collectAsStateWithLifecycle()
    val shipyards by viewModel.shipyards.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    val tokens = ImportParser.key(query).split(Regex("\\s+")).filter(String::isNotBlank)
    val matchingPeople = if (tokens.isEmpty()) emptyList() else people.filter { person ->
        matchesSearch(query, person.fullName, person.phoneNumbers, person.positions, person.aliases, person.tags)
    }
    val matchingProducts = if (tokens.isEmpty()) emptyList() else products.filter { product ->
        matchesSearch(query, product.name, product.variant.orEmpty(), product.category, product.groupName, product.subgroupName, product.aliases, product.tags)
    }
    val matchingShipyards = if (tokens.isEmpty()) emptyList() else shipyards.filter { shipyard ->
        matchesSearch(query, shipyard.name)
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text("Znajdź i wydaj", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            query,
            { query = it },
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            label = { Text("Osoba, stocznia lub przedmiot") },
            placeholder = { Text("Np. Wojdyło, Ulstein albo buty 44") },
            singleLine = true,
        )
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (tokens.isEmpty()) item { Text("Jedno pole przeszukuje osoby, stocznie i wszystkie przedmioty.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            if (tokens.isNotEmpty() && matchingPeople.isEmpty() && matchingProducts.isEmpty() && matchingShipyards.isEmpty()) item { Text("Brak pasujących osób, przedmiotów i stoczni.") }
            if (matchingShipyards.isNotEmpty()) {
                item { Text("Stocznie", style = MaterialTheme.typography.titleMedium) }
                items(matchingShipyards, key = { it.id }) { shipyard ->
                    OutlinedCard(onClick = { onShipyard(shipyard.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Business, null)
                            Text(shipyard.name, Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                            Icon(Icons.Default.ArrowUpward, "Wydaj stoczni")
                        }
                    }
                }
            }
            if (matchingPeople.isNotEmpty()) {
                item { Text("Osoby", style = MaterialTheme.typography.titleMedium) }
                items(matchingPeople.size, key = { matchingPeople[it].id }) { index ->
                    val person = matchingPeople[index]
                    OutlinedCard(onClick = { onPerson(person.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Person, null)
                            Text(person.listDisplayName(), Modifier.padding(start = 10.dp).weight(1f), fontWeight = FontWeight.SemiBold)
                            Icon(
                                Icons.Default.ArrowUpward,
                                "Wydaj tej osobie",
                                Modifier.size(24.dp).clickable { onIssuePerson(person.id) },
                            )
                        }
                    }
                }
            }
            if (matchingProducts.isNotEmpty()) {
                item { Text("Przedmioty", style = MaterialTheme.typography.titleMedium) }
                items(matchingProducts.size, key = { matchingProducts[it].id }) { index ->
                    val product = matchingProducts[index]
                    OutlinedCard(onClick = { onProduct(product.id) }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Inventory2, null)
                            Column(Modifier.padding(start = 10.dp).weight(1f)) {
                                Text(product.name + product.variant?.let { " · $it" }.orEmpty(), fontWeight = FontWeight.SemiBold)
                                Text(listOf(product.groupName, product.subgroupName, product.category).filter(String::isNotBlank).joinToString(" · "), style = MaterialTheme.typography.labelMedium)
                            }
                            Text(if (product.stockKnown) "${formatWholeQuantity(product.stockQuantity)} ${product.unit}" else "stan ?", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}
