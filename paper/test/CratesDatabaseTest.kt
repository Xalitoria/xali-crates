package mc.xalitoria.crates.paper

import com.xalitoria.util.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import mc.xalitoria.crates.paper.data.db.DatabaseManager
import mc.xalitoria.crates.paper.data.db.repository.CooldownRepository
import mc.xalitoria.crates.paper.data.db.repository.HistoryRepository
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRepository
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID

class CratesDatabaseTest {
    @BeforeEach
    fun setup(@TempDir dir: Path) {
        val file = dir.resolve("test.db")
        val hikari = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:$file"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }
        DatabaseManager.attach(HikariDataSource(hikari), DatabaseConfig(tablePrefix = "testcrates_"))
    }

    @AfterEach
    fun teardown() {
        DatabaseManager.close()
    }

    @Test
    fun placedCrateCrud() = runBlocking<Unit> {
        val saved = PlacedCrateRepository.put("world", 10, 64, -20, "starter", placedAt = 1000L, expiresAt = 0L)
        assertEquals("starter", saved.crateId)
        assertEquals(1, PlacedCrateRepository.all().size)

        // Updating same coordinates overwrites crateId
        PlacedCrateRepository.put("world", 10, 64, -20, "master", placedAt = 2000L, expiresAt = 0L)
        assertEquals("master", PlacedCrateRepository.all().single().crateId)

        assertTrue(PlacedCrateRepository.deleteAt("world", 10, 64, -20))
        assertTrue(PlacedCrateRepository.all().isEmpty())
    }

    @Test
    fun virtualBalancesGuardedConsumptionAndTransfer() = runBlocking<Unit> {
        val alice = UUID.randomUUID()
        val bob = UUID.randomUUID()

        assertEquals(5, VirtualBalanceRepository.giveKeys(alice, "starter", 5))
        assertEquals(5, VirtualBalanceRepository.get(alice, "starter").keys)

        assertFalse(VirtualBalanceRepository.transferKeys(alice, bob, "starter", 10))
        assertTrue(VirtualBalanceRepository.transferKeys(alice, bob, "starter", 2))
        assertEquals(3, VirtualBalanceRepository.get(alice, "starter").keys)
        assertEquals(2, VirtualBalanceRepository.get(bob, "starter").keys)

        // Concurrent guarded consumption of 3 keys across 8 racers: exactly 3 succeed
        val successes = (1..8).map {
            async { VirtualBalanceRepository.consumeKeys(alice, "starter", 1) }
        }.awaitAll().count { it }
        assertEquals(3, successes)
        assertEquals(0, VirtualBalanceRepository.get(alice, "starter").keys)
    }

    @Test
    fun cooldownsAndHistoryPruning() = runBlocking<Unit> {
        val player = UUID.randomUUID()
        val future = System.currentTimeMillis() + 60_000L
        val past = System.currentTimeMillis() - 60_000L

        CooldownRepository.set(player, "starter", "", future)
        CooldownRepository.set(player, "expired", "", past)
        val active = CooldownRepository.activeOf(player)
        assertEquals(1, active.size)
        assertEquals(future, active["starter" to ""])

        repeat(5) { idx ->
            HistoryRepository.record(player, "Steve", "starter", listOf("r$idx"), openedAt = 1000L + idx, virtual = true, maxPerPlayer = 3)
        }
        val recent = HistoryRepository.recent(player, 10)
        assertEquals(3, recent.size)
        assertEquals(listOf("r4"), recent.first().rewards)
    }
}
