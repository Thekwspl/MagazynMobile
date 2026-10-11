package pl.magazyn.mobile.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import pl.magazyn.mobile.domain.*

class CompactInputLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun quickInputUsesTwoSingleRowsAboveAndBelowTextWithAccessibleActions() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(360.dp)) {
                SmartInput("Produkt x1", QuickInputMode.ORDER, {}, {}, false, null, false, null, null, emptyMap(), {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
            } }
        }
        val modes = listOf("ALL", "Zamówienie", "Zadanie", "Notatka").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        val methods = listOf("Offline", "Gemini", "Codex").map { compose.onNodeWithText(it).fetchSemanticsNode().boundsInRoot }
        assertTrue(modes.zipWithNext().all { (a, b) -> kotlin.math.abs(a.top - b.top) < 2f && a.left < b.left })
        assertTrue(methods.zipWithNext().all { (a, b) -> kotlin.math.abs(a.top - b.top) < 2f && a.left < b.left })
        assertTrue(modes.first().top < compose.onNodeWithText("Szybkie pole").fetchSemanticsNode().boundsInRoot.top)
        assertTrue(methods.first().top > compose.onNodeWithText("Szybkie pole").fetchSemanticsNode().boundsInRoot.bottom)
        listOf("Offline", "Gemini", "Codex").forEach { compose.onNodeWithText(it).assertHasClickAction().assertIsEnabled() }
        compose.onNodeWithText("AI tworzy tylko propozycję — niczego nie zapisuje automatycznie.").assertDoesNotExist()
    }

    @Test fun largeFontKeepsAllModesReachableThroughHorizontalScroll() {
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                MaterialTheme { Box(Modifier.width(320.dp)) {
                    SmartInput("Produkt x1", QuickInputMode.ALL, {}, {}, false, "Błąd Gemini", false, "Błąd Codex", null, emptyMap(), {}, {}, {}, { _, _ -> }, { _, _ -> }, {})
                } }
            }
        }
        compose.onNodeWithText("Notatka").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Codex").performScrollTo().assertIsDisplayed().assertHasClickAction()
        compose.onNodeWithText("Błąd Gemini").assertExists()
        compose.onNodeWithText("Błąd Codex").assertExists()
    }

    @Test fun newOrderUsesVariantQuantityReadonlyUnitAndNoNotesField() {
        compose.setContent {
            MaterialTheme { Box(Modifier.width(360.dp)) {
                ParsedNoteReviewContent("", ParsedNote(null, emptyList(), kind = ParsedInputKind.ORDER), null,
                    emptyList(), emptyList(), emptyList(), emptyList(), {}, { _, _ -> }, { _, _, _, _, _, _ -> }, {}, { _, _, _, _ -> })
            } }
        }
        compose.onNodeWithText("Dodaj kolejną pozycję").performScrollTo().performClick()
        compose.onNodeWithText("Wariant").performScrollTo().assertExists()
        val variant = compose.onNodeWithText("Wariant").fetchSemanticsNode().boundsInRoot
        val quantity = compose.onNodeWithText("Ilość").fetchSemanticsNode().boundsInRoot
        val unit = compose.onNodeWithText("Jednostka").fetchSemanticsNode().boundsInRoot
        assertTrue(variant.left < quantity.left && quantity.left < unit.left)
        assertTrue(kotlin.math.abs(variant.top - unit.top) < 2f)
        compose.onNodeWithText("Jednostka").assert(hasSetTextAction().not())
        compose.onNodeWithText("Uwagi").assertDoesNotExist()
    }
}
