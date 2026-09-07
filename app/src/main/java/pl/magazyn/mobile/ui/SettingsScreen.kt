package pl.magazyn.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(
    contentPadding: PaddingValues,
    onBack: () -> Unit,
    onAiSettings: () -> Unit,
    onUpdates: () -> Unit,
    onLearningRules: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(contentPadding)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Wróć"
                )
            }

            Column {
                Text(
                    text = "Ustawienia",
                    style = MaterialTheme.typography.headlineSmall
                )
                Text(
                    text = "Aplikacja, AI i reguły lokalne",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        SettingsItem(
            icon = Icons.Default.AutoAwesome,
            title = "Ustawienia AI",
            subtitle = "Klucz Gemini i prywatność notatek",
            onClick = onAiSettings
        )

        SettingsItem(
            icon = Icons.Default.SystemUpdate,
            title = "Aktualizacje",
            subtitle = "Sprawdzanie i instalowanie nowej wersji",
            onClick = onUpdates
        )

        SettingsItem(
            icon = Icons.Default.Psychology,
            title = "Uczenie offline",
            subtitle = "Reguły używane bez internetu",
            onClick = onLearningRules
        )
    }
}

@Composable
private fun SettingsItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null
            )

            Column(
                Modifier
                    .padding(start = 14.dp)
                    .weight(1f)
            ) {
                Text(
                    text = title,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null
            )
        }

        HorizontalDivider(
            Modifier.padding(top = 15.dp)
        )
    }
}