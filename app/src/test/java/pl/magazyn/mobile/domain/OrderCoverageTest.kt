package pl.magazyn.mobile.domain

import org.junit.Assert.*
import org.junit.Test
import pl.magazyn.mobile.data.ProductWithStock

class OrderCoverageTest {
    private fun product(id: String, name: String, variant: String?, unit: String, aliases: String = "") =
        ProductWithStock(id, name, variant, unit, "", "", "", aliases, "", "", false, 0.0, 0, false, 36.0, true)
    private val catalog = listOf(product("g", "Rękawice Wampirki", "09", "opak."), product("o", "Okulary BHP", null, "opak."),
        product("b", "Buty robocze", "47", "para"), product("h", "Opalarka", null, "szt."))
    private val raw = "Elektro 13.10\n- 3 paczki rekawic wampirkow, rozmiar 9\n- Karton okularow BHP\n- Buty robocze, rozmiar m47 dla Marka Wiewiorki\n- 3 opalarki"
    private val proposal = ParsedNote(null, listOf(ParsedItem("Rękawice Wampirki", "09", 3, "opak.", 1f, productId="g",recipientKind="shipyard"),
        ParsedItem("Okulary BHP", null, 1, "opak.", 1f, productId="o",recipientKind="shipyard"),
        ParsedItem("Opalarka", null, 3, "szt.", 1f, productId="h",recipientKind="shipyard")), kind=ParsedInputKind.ORDER, agentProposal=true)

    @Test fun omittedBootsBecomeExplicitUnresolvedItemForPersonNotShipyard() {
        val checked=OrderCoverage.reconcile(raw,proposal,catalog,listOf("Elektro"))
        assertEquals(4,checked.sourceRequests.size)
        val boots=checked.items.single { it.sourceRecipient!=null }
        assertTrue(boots.sourceText!!.contains("Buty robocze"))
        assertEquals("Marka Wiewiorki",boots.recipientName)
        assertNull(boots.recipientKind); assertNull(boots.recipientId)
        assertTrue(boots.requiresSourceConfirmation)
        assertFalse(OrderCoverage.canSave(checked,checked.items))
        val approved=checked.items.filterNot { it===boots }
        assertTrue(OrderCoverage.canSave(checked.copy(excludedSourceIds=boots.sourceIds),approved))
        assertFalse(OrderCoverage.canSave(checked,approved))
    }
    @Test fun cartonWithoutNumeralStillRequiresUnitConfirmation() {
        assertEquals("Karton",ProductUnits.explicitUnit("- Karton okularow BHP"))
        assertEquals(1L,ProductUnits.explicitQuantity("- Karton okularow BHP"))
        val result=OrderCoverage.reconcile(raw,proposal,catalog,listOf("Elektro"))
        assertTrue(ProductUnits.needsConfirmation(result.items.first { it.productId=="o" },"opak."))
    }
    @Test fun typoAliasesAndSeveralItemsInOneLineAreNotCountedByBullets() {
        val c=listOf(product("g","Rękawice",null,"para","wampirki"),product("h","Opalarka",null,"szt."))
        val p=ParsedNote(null,listOf(ParsedItem("Rękawice",null,2,"para",1f,productId="g"), ParsedItem("Opalarka",null,3,"szt.",1f,productId="h")),kind=ParsedInputKind.ORDER)
        val result=OrderCoverage.reconcile("2 wampirkj i 3 opalarki",p,c,emptyList())
        assertTrue(result.items.all { it.sourceIds.isNotEmpty() })
        assertEquals(1,result.sourceRequests.size)
    }
    @Test fun knownProductDoesNotCoverUnknownRequestOnSameLine() {
        val result=OrderCoverage.reconcile("3 opalarki i tajemniczy element",proposal,catalog,emptyList())
        assertTrue(result.items.any { it.requiresSourceConfirmation && it.sourceText=="3 opalarki i tajemniczy element" })
        assertFalse(OrderCoverage.canSave(result,result.items))
    }
    @Test fun unknownFragmentAndQuantityDifferenceCannotDisappear() {
        val result=OrderCoverage.reconcile("Nieznany element x7",proposal,catalog,emptyList())
        assertTrue(result.items.any { it.requiresSourceConfirmation && it.sourceText=="Nieznany element x7" })
        val changed=OrderCoverage.reconcile("Opalarka 10 szt.",proposal,catalog,emptyList())
        assertTrue(changed.items.first { it.productId=="h" }.requiresSourceConfirmation)
    }
    @Test fun userNotesRemainAndDiagnosticsStaySeparate() {
        val note=proposal.copy(reviewWarnings=listOf(ReviewWarning("a",ReviewWarningCategory.GENERAL,"A"),ReviewWarning("b",ReviewWarningCategory.GENERAL,"B")))
        assertEquals(2,OrderCoverage.warnings(note).size)
        assertTrue(OrderCoverage.reconcile(raw,note,catalog,listOf("Elektro")).items.filter { it.productId!=null }.all { it.notes.isEmpty() })
    }
}
