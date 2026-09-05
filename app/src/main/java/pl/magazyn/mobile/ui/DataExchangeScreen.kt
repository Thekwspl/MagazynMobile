package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun DataExchangeScreen(contentPadding: PaddingValues) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Text("Import i eksport danych", Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.headlineSmall)
        TabRow(selectedTabIndex = selectedTab) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = { Text("Import") },
                icon = { Icon(Icons.Default.UploadFile, null) },
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = { Text("Eksport") },
                icon = { Icon(Icons.Default.FileDownload, null) },
            )
            Tab(
                selected = selectedTab == 2,
                onClick = { selectedTab = 2 },
                text = { Text("Kopia") },
                icon = { Icon(Icons.Default.Backup, null) },
            )
        }
        Box(Modifier.weight(1f)) {
            when (selectedTab) {
                0 -> ImportScreen(PaddingValues(0.dp), showTitle = false)
                1 -> ExportScreen(PaddingValues(0.dp), showTitle = false)
                else -> BackupScreen(PaddingValues(0.dp))
            }
        }
    }
}
