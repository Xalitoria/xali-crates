package mc.xalitoria.crates.paper

import mc.xalitoria.crates.paper.service.CrateRegistry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

class CrateToggleTest {

    @Test
    fun disabledCrateSurvivesARestart(@TempDir dir: Path) {
        CrateRegistry.writeDefaultsIfEmpty(dir)
        val crates = dir.resolve("crates")
        val starter = CrateRegistry.loadAll(dir).crates.getValue("starter")
        assertTrue(starter.enabled)

        CrateRegistry.saveCrate(crates, "starter", starter.copy(enabled = false))
        val off = CrateRegistry.loadAll(dir).crates.getValue("starter")
        assertEquals(false, off.enabled)
        assertEquals(starter.copy(enabled = false), off, "disabling changed more than `enabled`")

        CrateRegistry.saveCrate(crates, "starter", off.copy(enabled = true))
        assertEquals(starter, CrateRegistry.loadAll(dir).crates.getValue("starter"))
    }
}
