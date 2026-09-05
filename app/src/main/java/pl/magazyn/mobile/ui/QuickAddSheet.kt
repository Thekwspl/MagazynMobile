package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun QuickAddSheet(
    onClose: () -> Unit,
    onNote: () -> Unit,
    onPeople: () -> Unit,
    onProducts: () -> Unit,
    onTask: () -> Unit,
) {
    Column(
        Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Co chcesz zrobić?", style = MaterialTheme.typography.titleLarge)
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Zamknij") }
        }
        QuickAddAction(Icons.Default.NoteAdd, "Wklej notatkę", "Utwórz zamówienie z tekstu", onNote)
        QuickAddAction(Icons.Default.TaskAlt, "Nowe zadanie", "Termin, priorytet i powiązania", onTask)
        QuickAddAction(Icons.Default.PersonAdd, "Dodaj osobę", "Imię, nazwisko, stanowisko i profil", onPeople)
        QuickAddAction(Icons.Default.PlaylistAdd, "Dodaj przedmiot", "Nowa pozycja w katalogu", onProducts)
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun QuickAddAction(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, null)
        Column(Modifier.padding(start = 10.dp).weight(1f), horizontalAlignment = Alignment.Start) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.labelSmall)
        }
    }
}
