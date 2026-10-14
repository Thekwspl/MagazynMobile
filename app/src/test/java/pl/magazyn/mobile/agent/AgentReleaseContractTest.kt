package pl.magazyn.mobile.agent

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class AgentReleaseContractTest {
    @Test fun sharedServerFixtureIsAcceptedAndUnexpectedFieldsRemainRejected() {
        val raw=javaClass.classLoader!!.getResource("agent-contract-v2.json")!!.readText()
        val reply=AgentProtocol.parse(raw)
        assertEquals(AgentStatus.PROPOSAL,reply.status)
        assertEquals("yard",reply.recipient!!.id)
        assertEquals(3.0,reply.items.single().quantity,0.0)
        assertTrue(runCatching {AgentProtocol.parse(JSONObject(raw).put("schemaVersion",1).toString())}.isFailure)
        assertTrue(runCatching {AgentProtocol.parse(JSONObject(raw).put("unexpected","field").toString())}.isFailure)
    }
}
