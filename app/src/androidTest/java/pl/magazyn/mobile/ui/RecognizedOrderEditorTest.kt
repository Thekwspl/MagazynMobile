package pl.magazyn.mobile.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import pl.magazyn.mobile.data.ProductWithStock
import pl.magazyn.mobile.data.ShipyardEntity
import pl.magazyn.mobile.domain.*

class RecognizedOrderEditorTest {
    @get:Rule val compose = createComposeRule()

    private val opalarka = ParsedItem("Opalarka", null, 2, "szt.", 1f, productId = "h")
    private val gloves = ParsedItem("Rękawice", "9", 3, "para", 1f, productId = "g")
    private val products = listOf(
        product("h", "Opalarka", null, "szt."),
        product("g", "Rękawice", "9", "para"),
        product("g10", "Rękawice", "10", "para"),
    )

    @Test fun checkboxRowAndPencilHaveIndependentActionsAndCancelDiscardsChanges() {
        val state = review(listOf(opalarka, gloves))
        compose.onNodeWithContentDescription("Zatwierdź pozycję: Opalarka").performClick().assertIsOff()
        compose.onNodeWithText("Edytuj pozycję").assertDoesNotExist()
        compose.onNodeWithText("Opalarka").performClick()
        compose.onNodeWithText("Edytuj pozycję").assertIsDisplayed()
        field("Nazwa, alias lub tag").performTextReplacement("Przewód")
        field("Ilość").performTextReplacement("7")
        field("Osoba lub stocznia").performClick().performTextReplacement("Rura")
        compose.onNodeWithText("Anuluj").assertIsDisplayed().performClick()
        compose.waitUntil(timeoutMillis = 5_000) { state.editor == null }
        compose.onNodeWithText("Edytuj pozycję").assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(opalarka, state.rows[0].item)
            assertFalse(state.rows[0].approved)
        }
        compose.onNode(
            hasContentDescription("Popraw") and hasAnyAncestor(hasAnyDescendant(hasText("Rękawice · 9")) and hasClickAction()),
            useUnmergedTree = true,
        ).performClick()
        compose.onNodeWithText("Edytuj pozycję").assertIsDisplayed()
        compose.runOnIdle { assertEquals(gloves, state.editor?.item) }
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        compose.waitUntil(timeoutMillis = 5_000) { state.editor == null }
        compose.onNodeWithText("Edytuj pozycję").assertDoesNotExist()
    }

    @Test fun addOnlyCommitsAValidItemAndKeepsExistingRowsAndSource() {
        val state = review(listOf(opalarka))
        val add = compose.onNodeWithText("Dodaj kolejną pozycję")
        add.performScrollTo().performClick()
        compose.onNodeWithContentDescription("Zamknij edytor").performClick()
        compose.runOnIdle { assertEquals(1, state.rows.size) }
        add.performScrollTo().performClick()
        val save = compose.onNodeWithText("Zapisz zmiany")
        field("Nazwa, alias lub tag").performTextReplacement("Rękawice")
        field("Wariant").performTextReplacement("10")
        field("Osoba lub stocznia").performTextReplacement("Rura")
        val quantity = field("Ilość")
        listOf("", "0", "-1", "9223372036854775808").forEach {
            quantity.performTextReplacement(it)
            save.assertIsDisplayed().assertIsNotEnabled()
        }
        quantity.performTextReplacement("4")
        save.assertIsEnabled().performClick()
        compose.runOnIdle {
            assertEquals(2, state.rows.size)
            assertEquals(opalarka, state.rows.first().item)
            val added = state.rows.last()
            assertEquals("g10", added.item.productId)
            assertEquals("10", added.item.variant)
            assertEquals("r", added.item.recipientId)
            assertEquals(4L, added.item.quantity)
            assertTrue(added.item.sourceIds.isEmpty())
            assertTrue(added.approved)
        }
    }

    @Test fun sourceAndUnitConfirmationAreCommittedOnlyWithTheEditedRow() {
        val source = gloves.copy(
            sourceIds = setOf("s1"), sourceText = "Karton rękawic 10 dla Rura",
            requestedUnit = "karton", sourceRecipient = "Rura", recipientName = "Rura",
            requiresSourceConfirmation = true,
        )
        val state = review(listOf(opalarka, source))
        compose.onNodeWithText("Rękawice · 9").performClick()
        field("Wariant").performTextReplacement("10")
        compose.onNode(hasText("Rura") and hasClickAction() and !hasSetTextAction()).performScrollTo().performClick()
        field("Ilość").performTextReplacement("5")
        compose.onNodeWithText("Potwierdzam 5 para").performScrollTo().performClick()
        compose.onNodeWithText("Potwierdzam dane fragmentu").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(source, state.rows[1].item) }
        compose.onNodeWithText("Zapisz zmiany").performClick()
        compose.runOnIdle {
            val edited = state.rows[1].item
            assertEquals("g10", edited.productId)
            assertEquals("r", edited.recipientId)
            assertEquals("para", edited.confirmedCatalogUnit)
            assertEquals("karton", edited.requestedUnit)
            assertEquals(source.sourceIds, edited.sourceIds)
            assertEquals(source.sourceText, edited.sourceText)
            assertEquals(source.sourceRecipient, edited.sourceRecipient)
            assertFalse(edited.requiresSourceConfirmation)
        }
    }

    @Test fun draggingSheetDownDiscardsUncommittedChanges() {
        val state = review(listOf(opalarka))
        compose.onNodeWithText("Opalarka").performClick()
        field("Ilość").performTextReplacement("9")
        compose.onNodeWithText("Edytuj pozycję").performTouchInput {
            swipe(center, center + androidx.compose.ui.geometry.Offset(0f, 650f), durationMillis = 350)
        }
        compose.onNodeWithText("Edytuj pozycję").assertDoesNotExist()
        compose.runOnIdle { assertEquals(opalarka, state.rows.single().item) }
    }

    @Test fun longListRestoresScrollAndKeepsAnOpenDraftSeparate() {
        val items = (1..30).map { opalarka.copy(quantity = it.toLong()) }
        val note = note(items)
        val state = RecognizedOrderReviewState(items)
        val restoration = StateRestorationTester(compose)
        restoration.setContent { ReviewContent(note, state) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(24)
        compose.onNodeWithText("24 szt.").performClick()
        field("Ilość").performTextReplacement("67")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Edytuj pozycję").assertIsDisplayed()
        compose.runOnIdle {
            assertEquals("67", state.editor?.quantityText)
            assertEquals(24L, state.rows[23].item.quantity)
        }
        compose.onNodeWithText("Zapisz zmiany").performClick()
        compose.onNodeWithText("67 szt.", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(23L, state.rows[22].item.quantity) }
    }

    private fun field(label: String) = compose.onNode(hasText(label) and hasSetTextAction()).performScrollTo()

    private fun note(items: List<ParsedItem>) = ParsedNote(null, items, kind = ParsedInputKind.ORDER, shipyardName = "Elektro")

    private fun review(items: List<ParsedItem>): RecognizedOrderReviewState {
        val note = note(items)
        val state = RecognizedOrderReviewState(items)
        compose.setContent { ReviewContent(note, state) }
        return state
    }

    @androidx.compose.runtime.Composable
    private fun ReviewContent(note: ParsedNote, state: RecognizedOrderReviewState) {
        MaterialTheme {
            ParsedNoteReviewContent("", note, null, emptyList(), products,
                listOf(ShipyardEntity("e", "Elektro"), ShipyardEntity("r", "Rura")),
                emptyList(), {}, { _, _ -> error("Unexpected tag write") }, { _, _, _, _, _, _ -> },
                {}, { _, _, _, _, _ -> error("Unexpected order save") }, orderState = state)
        }
    }

    private fun product(id: String, name: String, variant: String?, unit: String) =
        ProductWithStock(id, name, variant, unit, "", "", "", "", "", "", false, 0.0, 0, false, 36.0, true)
}
