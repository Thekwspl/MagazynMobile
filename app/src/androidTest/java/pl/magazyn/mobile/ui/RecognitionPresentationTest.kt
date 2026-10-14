package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import pl.magazyn.mobile.domain.*

class RecognitionPresentationTest {
    @get:Rule val compose = createComposeRule()
    @Test fun originalMessageIsCollapsedAndPreservesExactMultilineText() {
        val raw="Elektro 13.10\n  - 3 opalarki\n"
        compose.setContent { MaterialTheme { OriginalMessagePanel(raw,initiallyCollapsed=true) } }
        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithText("Oryginalna wiadomość  ▼").performClick()
        compose.onNodeWithText(raw).assertExists()
        compose.onNodeWithText("Oryginalna wiadomość  ▲").performClick()
        compose.onNodeWithText(raw).assertDoesNotExist()
    }
    @Test fun criticalWarningCannotBeHiddenAndSimilarDifferentIssuesAreRetained() {
        val warnings=listOf(ReviewWarning("missing",ReviewWarningCategory.COVERAGE,"Brakuje butów dla Marka",true),
            ReviewWarning("a",ReviewWarningCategory.GENERAL,"Wcześniej wydano rękawice"),
            ReviewWarning("b",ReviewWarningCategory.GENERAL,"Wcześniej wydano inne rękawice"))
        compose.setContent { MaterialTheme { Box(Modifier.width(320.dp)) { RecognitionWarnings("Uwagi Codexa",warnings) } } }
        compose.onNodeWithText("Brakuje butów dla Marka").assertIsDisplayed()
        compose.onNodeWithText("• Wcześniej wydano rękawice").assertDoesNotExist()
        compose.onNodeWithText("Uwagi Codexa  ▼").performClick()
        compose.onNodeWithText("• Wcześniej wydano rękawice").assertExists()
        compose.onNodeWithText("• Wcześniej wydano inne rękawice").assertExists()
    }
}
