package pl.magazyn.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp

private fun dial(context: android.content.Context, number: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    if (intent.resolveActivity(context.packageManager) != null) context.startActivity(intent)
}

@Composable
fun PersonPhoneAction(phoneNumbers: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val numbers = remember(phoneNumbers) { distinctPersonPhoneNumbers(phoneNumbers) }
    var chooseNumber by remember { mutableStateOf(false) }
    if (numbers.isEmpty()) return

    IconButton(
        onClick = {
            if (numbers.size == 1) dial(context, numbers.single()) else chooseNumber = true
        },
        modifier = modifier.size(36.dp),
    ) {
        Icon(Icons.Default.Phone, contentDescription = "Zadzwoń")
    }
    if (chooseNumber) {
        AlertDialog(
            onDismissRequest = { chooseNumber = false },
            title = { Text("Wybierz numer telefonu") },
            text = {
                Column {
                    numbers.forEach { number ->
                        TextButton(onClick = {
                            chooseNumber = false
                            dial(context, number)
                        }) { Text(number) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { chooseNumber = false }) { Text("Anuluj") } },
        )
    }
}

@Composable
fun PhoneNumbersInline(phoneNumbers: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        distinctPersonPhoneNumbers(phoneNumbers).forEach { number ->
            Text(
                text = number,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    dial(context, number)
                },
            )
        }
    }
}
