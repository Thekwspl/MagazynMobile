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

    @Test fun `all text signals participate equally in ambiguity detection`() {
        fun resolution(vararg shipyards: ShipyardEntity) = ShipyardResolver.resolve(null, "Wspolna", shipyards.toList())

        assertEquals("name", resolution(ShipyardEntity("name", "Wspolna")).confirmedId)
        assertEquals("alias", resolution(ShipyardEntity("alias", "Inna", aliases = "Wspolna")).confirmedId)
        assertEquals("tag", resolution(ShipyardEntity("tag", "Inna", tags = "Wspolna")).confirmedId)
        assertEquals("same", resolution(ShipyardEntity("same", "Wspolna", aliases = "Wspolna", tags = "Wspolna")).confirmedId)

        val aliasAlias = resolution(
            ShipyardEntity("a", "Pierwsza", aliases = "Wspolna"),
            ShipyardEntity("b", "Druga", aliases = "Wspolna"),
        )
        assertNull(aliasAlias.confirmedId)
        assertEquals(setOf("a", "b"), aliasAlias.candidates.toSet())

        val nameAlias = resolution(
            ShipyardEntity("a", "Wspolna"),
            ShipyardEntity("b", "Druga", aliases = "Wspolna"),
        )
        assertNull(nameAlias.confirmedId)
        assertEquals(setOf("a", "b"), nameAlias.candidates.toSet())

        val nameTag = resolution(
            ShipyardEntity("a", "Wspolna"),
            ShipyardEntity("b", "Druga", tags = "Wspolna"),
        )
        assertNull(nameTag.confirmedId)
        assertEquals(setOf("a", "b"), nameTag.candidates.toSet())

        val aliasTag = resolution(
            ShipyardEntity("a", "Pierwsza", aliases = "Wspolna"),
            ShipyardEntity("b", "Druga", tags = "Wspolna"),
        )
        assertNull(aliasTag.confirmedId)
        assertEquals(setOf("a", "b"), aliasTag.candidates.toSet())

        val tagTag = resolution(
            ShipyardEntity("a", "Pierwsza", tags = "Wspolna"),
            ShipyardEntity("b", "Druga", tags = "Wspolna"),
        )
        assertNull(tagTag.confirmedId)
        assertEquals(setOf("a", "b"), tagTag.candidates.toSet())
    }

    @Test fun `manual stable identity overrides labels and is never re-guessed`() {
        assertEquals("s2", ShipyardResolver.resolve("s2", "Ulstein Elektro", yards).confirmedId)
        assertNull(ShipyardResolver.resolve("missing", "Ulstein Elektro", yards).confirmedId)
    }

    @Test fun `leader cannot resolve a conflict between text candidates`() {
        val result = ShipyardResolver.resolve(null, "Verft", yards, leaders, "p")
        assertNull(result.confirmedId)
        assertEquals(setOf("s1", "s2"), result.candidates.toSet())
    }
}
