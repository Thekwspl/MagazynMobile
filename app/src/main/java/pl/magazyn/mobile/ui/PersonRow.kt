package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pl.magazyn.mobile.data.EmployeeSummary

@Composable
fun PersonRow(
    person: EmployeeSummary,
    modifier: Modifier = Modifier,
    onIssue: (() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Default.Person, null)
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    person.listDisplayName(),
                    Modifier.weight(1f),
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (person.hrappkaDoNotHire) {
                    Text(
                        "Nie zatrudniać",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
            person.positions.takeIf(String::isNotBlank)?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PersonPhoneAction(person.phoneNumbers)
        onIssue?.let { issue ->
            IconButton(onClick = issue, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.ArrowUpward, "Wydaj tej osobie")
            }
        }
    }
}
