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
    @Test fun explicitKnownShipyardRecipientIsNotForcedIntoPersonSelection() {
        val one=proposal.copy(items=listOf(proposal.items.last().copy(recipientName="Elektro",recipientId="yard")))
        val result=OrderCoverage.reconcile("3 opalarki dla Elektro",one,catalog,listOf("Elektro"))
        assertEquals(1,result.items.size)
        assertFalse(result.items.single().requiresSourceConfirmation)
        assertTrue(OrderCoverage.canSave(result,result.items))
    }
    @Test fun recipientBeforeDashIsAccountedForWithoutInventingAnotherProduct() {
        val one=proposal.copy(items=listOf(proposal.items.last().copy(recipientName="Jan Kowalski",recipientKind="person")))
        val result=OrderCoverage.reconcile("Jan Kowalski - Opalarka x3",one,catalog,emptyList())
        assertEquals(1,result.items.size)
        assertFalse(result.items.single().requiresSourceConfirmation)
        assertTrue(OrderCoverage.canSave(result,result.items))
    }
    @Test fun activeLearnedProductAliasIsUsedBySourceReconciliation() {
        val rule=pl.magazyn.mobile.data.ParserLearningRuleEntity("rule","swietlik","Świetlik","Opalarka",null,"szt.",updatedAtEpochMillis=1)
        val one=proposal.copy(items=listOf(proposal.items.last().copy(quantity=2)))
        val result=OrderCoverage.reconcile("Świetlik x2",one,catalog,emptyList(),listOf(rule))
        assertEquals(1,result.items.size)
        assertEquals(setOf("source-0"),result.items.single().sourceIds)
        assertFalse(result.items.single().requiresSourceConfirmation)
    }
    @Test fun ambiguousCatalogVariantsCannotBeAccountedForByNameOnly() {
        val c=listOf(product("g9","Rękawice","9","para"),product("g10","Rękawice","10","para"))
        val p=ParsedNote(null,listOf(ParsedItem("Rękawice","9",2,"para",1f,productId="g9")),kind=ParsedInputKind.ORDER)
        val result=OrderCoverage.reconcile("2 rękawice",p,c,emptyList())
        assertTrue(result.items.any { it.sourceText=="2 rękawice" && it.requiresSourceConfirmation })
        assertFalse(OrderCoverage.canSave(result,result.items))
    }
    @Test fun bundleExpandedIntoSeveralCatalogProductsKeepsOneSourceAndRequiresQuantityDecision() {
        val c=listOf(product("g","Rękawice",null,"para","zestaw PPE"),product("b","Buty",null,"para","zestaw PPE"))
        val p=ParsedNote(null,listOf(ParsedItem("Rękawice",null,1,"para",1f,productId="g"),ParsedItem("Buty",null,1,"para",1f,productId="b")),kind=ParsedInputKind.ORDER)
        val result=OrderCoverage.reconcile("1 zestaw PPE",p,c,emptyList())
        assertEquals(1,result.sourceRequests.size)
        assertEquals(2,result.items.size)
        assertTrue(result.items.all { it.sourceIds==setOf("source-0") && it.requiresSourceConfirmation })
        assertFalse(OrderCoverage.canSave(result,result.items))
    }
    @Test fun repeatedSourceLinesCannotSilentlyShareOneQuantity() {
        val one=proposal.copy(items=listOf(proposal.items.last().copy(quantity=2)))
        val result=OrderCoverage.reconcile("Opalarka x2\nOpalarka x2",one,catalog,emptyList())
        assertEquals(2,result.items.single().sourceIds.size)
        assertTrue(result.items.single().requiresSourceConfirmation)
        assertFalse(OrderCoverage.canSave(result,result.items))
    }
    @Test fun duplicateModelRowsNeedExplicitResolution() {
        val duplicate=proposal.copy(items=listOf(proposal.items.last(),proposal.items.last()))
        val result=OrderCoverage.reconcile("3 opalarki",duplicate,catalog,emptyList())
        assertTrue(result.items.all { it.requiresSourceConfirmation })
    }
    @Test fun additionalGenericPackageRequestCannotHideBehindTheSameCatalogUnit() {
        val c=listOf(product("h","Opalarka",null,"kpl"))
        val p=ParsedNote(null,listOf(ParsedItem("Opalarka",null,2,"kpl",1f,productId="h")),kind=ParsedInputKind.ORDER)
        val result=OrderCoverage.reconcile("2 opalarki i 2 komplety",p,c,emptyList())
        assertFalse(OrderCoverage.canSave(result,result.items))
        assertTrue(result.items.any {it.requiresSourceConfirmation && it.sourceText=="2 opalarki i 2 komplety"})
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
    @Test fun geminiDiagnosticsAreGlobalWhileBusinessAndConditionNotesRemain() {
        val note=GeminiNoteAnalyzer().parseResponse("""{"kind":"ORDER","items":[{"name":"Opalarka","notes":"Dostępne: 3 szt."},{"name":"Buty","notes":"Stan: uszkodzone"},{"name":"Rękawice","notes":"Zwrot po zmianie"}],"warnings":["Dostępne: 3 szt.","Zwrot po zmianie"]}""")
        assertEquals("",note.items[0].notes)
        assertEquals("Stan: uszkodzone",note.items[1].notes)
        assertEquals("Zwrot po zmianie",note.items[2].notes)
        assertEquals(2,note.reviewWarnings.size)
    }
    @Test fun userNotesRemainAndDiagnosticsStaySeparate() {
        val note=proposal.copy(reviewWarnings=listOf(ReviewWarning("a",ReviewWarningCategory.GENERAL,"A"),ReviewWarning("b",ReviewWarningCategory.GENERAL,"B")))
        assertEquals(2,OrderCoverage.warnings(note).size)
        assertTrue(OrderCoverage.reconcile(raw,note,catalog,listOf("Elektro")).items.filter { it.productId!=null }.all { it.notes.isEmpty() })
    }
}
