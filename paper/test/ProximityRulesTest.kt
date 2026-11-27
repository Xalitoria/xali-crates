package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.service.ProximityRules
import mc.xalitoria.crates.paper.service.ProximityRules.Spot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProximityRulesTest {
    private val spot = Spot("w:0,64,0", "w", 0.5, 64.5, 0.5, 3.0)

    @Test
    fun zeroAndNegativeRadiusDisableHiding() {
        assertFalse(ProximityRules.active(0.0))
        assertFalse(ProximityRules.active(-2.0))
        assertFalse(ProximityRules.active(Double.NaN))
        assertFalse(ProximityRules.active(Double.POSITIVE_INFINITY))
        assertTrue(ProximityRules.active(0.5))

        val off = spot.copy(radius = 0.0)
        assertEquals(emptySet<String>(), ProximityRules.within([off], "w", 0.5, 64.5, 0.5))
    }

    @Test
    fun withinIsStraightLineAndInclusive() {
        assertEquals(setOf(spot.key), ProximityRules.within([spot], "w", 0.5, 64.5, 3.5))
        assertEquals(emptySet<String>(), ProximityRules.within([spot], "w", 0.5, 64.5, 3.6))
        // 2.5 on two axes is 3.54 away: outside although each axis alone is inside.
        assertEquals(emptySet<String>(), ProximityRules.within([spot], "w", 3.0, 64.5, 3.0))
        assertEquals(setOf(spot.key), ProximityRules.within([spot], "w", 0.5, 66.5, 2.5))
    }

    @Test
    fun anotherWorldIsNotNear() {
        assertEquals(emptySet<String>(), ProximityRules.within([spot], "nether", 0.5, 64.5, 0.5))
    }

    @Test
    fun playersHideOnlyWhenTheyShareACrate() {
        val a = setOf("one", "two")
        assertTrue(ProximityRules.hidesFrom(a, setOf("two")))
        assertTrue(ProximityRules.hidesFrom(setOf("two"), a))
        assertFalse(ProximityRules.hidesFrom(a, setOf("three")))
        assertFalse(ProximityRules.hidesFrom(a, emptySet()))
        assertFalse(ProximityRules.hidesFrom(emptySet(), emptySet()))
    }
}
