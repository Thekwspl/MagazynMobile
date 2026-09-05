package pl.magazyn.mobile.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration

@Composable
fun PhoneNumbersInline(phoneNumbers: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Column(modifier) {
        phoneNumbers.split(',').map(String::trim).filter(String::isNotBlank).forEach { number ->
            Text(
                text = number,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium,
                textDecoration = TextDecoration.Underline,
                modifier = Modifier.clickable {
                    val dialNumber = number.filter { it.isDigit() || it == '+' }
                    context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$dialNumber")))
                },
            )
        }
    }
}
