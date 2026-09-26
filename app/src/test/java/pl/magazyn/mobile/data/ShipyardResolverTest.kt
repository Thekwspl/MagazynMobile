package pl.magazyn.mobile.data

import org.junit.Assert.*
import org.junit.Test

class ShipyardResolverTest {
    private val yards = listOf(
        ShipyardEntity("s1", "Ulstein Elektro", aliases = "U-E, Verft", tags = "elektryka"),
        ShipyardEntity("s2", "Ulstein Rura", aliases = "Verft", tags = "rury"),
    )
    private val leaders = listOf(ShipyardLeaderLink("s1", "p"), ShipyardLeaderLink("s2", "p"))

    @Test fun `name alias tag and leader signals do not silently choose ambiguous records`() {
        assertEquals("s1", ShipyardResolver.resolve(null, "Ulstein Elektro", yards).confirmedId)
        assertEquals("s1", ShipyardResolver.resolve(null, "u-e", yards).confirmedId)
        assertEquals("s1", ShipyardResolver.resolve(null, "ELEKTRYKA", yards).confirmedId)
        assertEquals("s1", ShipyardResolver.resolve(null, "Ulstein Elektro", yards, leaders, "p").confirmedId)
        assertEquals("s1", ShipyardResolver.resolve(null, "U-E", yards, leaders, "p").confirmedId)
        assertNull(ShipyardResolver.resolve(null, "Verft", yards).confirmedId)
        assertEquals(2, ShipyardResolver.resolve(null, "Verft", yards).candidates.size)
        assertNull(ShipyardResolver.resolve(null, "Nieznane", yards, leaders, "p").confirmedId)
        assertEquals(2, ShipyardResolver.resolve(null, "Nieznane", yards, leaders, "p").candidates.size)
        assertTrue(ShipyardResolver.resolve(null, "Nieznane", yards).candidates.isEmpty())
    }

    @Test fun `manual stable identity overrides labels and is never re-guessed`() {
        assertEquals("s2", ShipyardResolver.resolve("s2", "Ulstein Elektro", yards).confirmedId)
        assertNull(ShipyardResolver.resolve("missing", "Ulstein Elektro", yards).confirmedId)
    }
}
