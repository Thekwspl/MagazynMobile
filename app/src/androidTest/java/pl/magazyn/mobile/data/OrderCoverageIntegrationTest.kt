package pl.magazyn.mobile.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import pl.magazyn.mobile.*
import pl.magazyn.mobile.domain.*
import pl.magazyn.mobile.ui.HomeViewModel

@RunWith(AndroidJUnit4::class)
class OrderCoverageIntegrationTest {
    private lateinit var env:IsolatedApplicationEnvironment
    private val db get()=env.database
    @Before fun setup()=runBlocking {env=IsolatedApplicationEnvironment.create();db.seedCoreData();db.shipyardDao().insert(ShipyardEntity("yard","Elektro"))}
    @After fun close(){env.close()}
    @Test fun omittedPersonLineBlocksSaveAndExplicitResolutionCreatesSeparateRecipients()=runBlocking {
        val raw="Elektro\nProdukt rozmiar 52 x2\nButy x1 dla Jan Kowalski"
        val catalog=db.productDao().observeAllWithStock("warehouse-main").first()
        val yard=ParsedItem("Produkt","52",2,"szt.",1f,recipientName="Elektro",productId="product-1",recipientId="yard",recipientKind="shipyard")
        val checked=OrderCoverage.reconcile(raw,ParsedNote(null,listOf(yard),kind=ParsedInputKind.ORDER,agentProposal=true,shipyardName="Elektro"),catalog,listOf("Elektro"))
        val vm=env.track(HomeViewModel(env.application))
        vm.runAndAwaitViewModelWork { vm.saveDraftOrder(raw,checked,checked.items.map {it to it},false) }
        assertEquals(0L,db.queryLong("SELECT COUNT(*) FROM orders"));assertNotNull(vm.reviewSave.value.error)
        val missing=checked.items.single {it.sourceRecipient!=null}
        val fixed=missing.copy(name="Produkt",variant="52",productId="product-1",unit="szt.",recipientName="Jan Kowalski",recipientId="employee-1",recipientKind="person",requiresSourceConfirmation=false)
        val pairs=checked.items.map {it to if(it===missing)fixed else it}
        vm.runAndAwaitViewModelWork {vm.saveDraftOrder(raw,checked,pairs,false)}
        val orders=db.orderDao().observeOpenOrders().first()
        assertEquals(2,orders.size)
        assertEquals(1L,db.queryLong("SELECT COUNT(*) FROM orders WHERE shipyardId='yard' AND employeeId IS NULL"))
        assertEquals(1L,db.queryLong("SELECT COUNT(*) FROM orders WHERE employeeId='employee-1' AND shipyardId IS NULL"))
        assertEquals(0L,db.queryLong("SELECT COUNT(*) FROM stock_movements"))
    }
    @Test fun identicalLabelsDoNotMergePersonAndShipyardIdentities()=runBlocking {
        db.employeeDao().insert(EmployeeEntity("employee-2","Elektro","Elektro",""))
        val yard=ParsedItem("Produkt","52",2,"szt.",1f,recipientName="Elektro",productId="product-1",recipientId="yard",recipientKind="shipyard")
        val person=yard.copy(quantity=1,recipientId="employee-2",recipientKind="person")
        val note=ParsedNote(null,listOf(yard,person),kind=ParsedInputKind.ORDER,agentProposal=true,shipyardName="Elektro")
        val vm=env.track(HomeViewModel(env.application))
        vm.runAndAwaitViewModelWork { vm.saveDraftOrder("Produkt",note,note.items.map {it to it},false) }
        assertEquals(2L,db.queryLong("SELECT COUNT(*) FROM orders"))
        assertEquals(1L,db.queryLong("SELECT COUNT(*) FROM orders WHERE shipyardId='yard' AND employeeId IS NULL"))
        assertEquals(1L,db.queryLong("SELECT COUNT(*) FROM orders WHERE employeeId='employee-2' AND shipyardId IS NULL"))
        assertEquals(0L,db.queryLong("SELECT COUNT(*) FROM stock_movements"))
    }

    @Test fun explicitAndImplicitRowsForSameShipyardRemainOneDraftDespitePersonWithSameLabel()=runBlocking {
        db.employeeDao().insert(EmployeeEntity("employee-2","Elektro","Elektro",""))
        val yard=ParsedItem("Produkt","52",2,"szt.",1f,recipientName="Elektro",productId="product-1",recipientId="yard",recipientKind="shipyard")
        val implicit=yard.copy(quantity=1,recipientId=null)
        val note=ParsedNote(null,listOf(yard,implicit),kind=ParsedInputKind.ORDER,shipyardName="Elektro")
        val vm=env.track(HomeViewModel(env.application))
        vm.runAndAwaitViewModelWork { vm.saveDraftOrder("Produkt",note,note.items.map {it to it},false) }
        assertEquals(1L,db.queryLong("SELECT COUNT(*) FROM orders"))
        assertEquals(1L,db.queryLong("SELECT COUNT(*) FROM orders WHERE shipyardId='yard' AND employeeId IS NULL"))
        assertEquals(2L,db.queryLong("SELECT COUNT(*) FROM order_lines"))
        assertEquals(0L,db.queryLong("SELECT COUNT(*) FROM stock_movements"))
    }

}
