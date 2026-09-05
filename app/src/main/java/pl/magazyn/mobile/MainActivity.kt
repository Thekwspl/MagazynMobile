package pl.magazyn.mobile

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import pl.magazyn.mobile.ui.MagazynApp
import pl.magazyn.mobile.ui.theme.MagazynTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MagazynTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MagazynApp()
                }
            }
        }
    }
}
