package mc.xalitoria.crates.paper

import com.xalitoria.util.config.DatabaseConfig
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import mc.xalitoria.crates.paper.data.db.DatabaseManager
import mc.xalitoria.crates.paper.data.db.repository.PlacedCrateRepository
import mc.xalitoria.crates.paper.data.db.repository.VirtualBalanceRepository
import mc.xalitoria.crates.paper.data.model.ObtainMethod
import mc.xalitoria.crates.paper.migrate.SpecializedCratesMigrator
import mc.xalitoria.crates.paper.service.CrateRegistry
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.util.UUID
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText

class SpecializedCratesMigratorTest {
    @BeforeEach
    fun setup(@TempDir dir: Path) {
        val hikari = HikariConfig().apply {
            jdbcUrl = "jdbc:sqlite:${dir.resolve("crates.db")}"
            driverClassName = "org.sqlite.JDBC"
            maximumPoolSize = 1
        }
        DatabaseManager.attach(HikariDataSource(hikari), DatabaseConfig(tablePrefix = "migcrates_"))
    }

    @AfterEach
    fun teardown() {
        DatabaseManager.close()
    }

    @Test
    fun migratesCratesMultiCratesAnimationsMenusAndSqliteData(@TempDir tmp: Path) = runBlocking<Unit> {
        val source = tmp.resolve("SpecializedCrates")
        val target = tmp.resolve("XaliCrates")
        val menuDir = tmp.resolve("XaliMenu/menus")
        source.resolve("Crates").createDirectories()
        source.resolve("animations").createDirectories()

        // 1. Sample SpecializedCrates .crate file
        source.resolve("Crates/BasicCrate.crate").writeText(
            """
            enabled: true
            obtain-method: PLACED
            display-name: '&aBasic Crate'
            crate:
              material: CHEST
              name: '&a&lBasic Crate'
              lore:
                - '&7Right-click to open!'
              glow: true
            key:
              require: true
              material: TRIPWIRE_HOOK
              name: '&aBasic Key'
              glow: true
            open:
              animation: CSGO
              cooldown: 300
              cost: 250.0
              inventory-name: '&8Basic Crate'
            hologram:
              lines:
                - '&a&lBasic Crate'
                - '&7Click to open'
              offset: 0.25
            inventory-actions:
              custom-inventory:
                rows: 3
                title: '&aBasic Preview'
            rewards:
              diamonds:
                chance: 40
                give-display-item: true
                display-item:
                  material: DIAMOND
                  amount: 3
                  name: '&b3x Diamonds'
                commands:
                  - 'eco give %player% 100'
              vip:
                chance: 10
                give-display-item: false
                display-item:
                  material: EMERALD
                  name: '&aVIP Rank'
                commands:
                  - 'lp user %player% parent add vip'
                fallback:
                  permissions:
                    - 'group.vip'
                  reward: fallback_cash
              fallback_cash:
                chance: 1
                fallback-only: true
                give-display-item: false
                display-item:
                  material: GOLD_INGOT
                  name: '&e$5,000'
                commands:
                  - 'eco give %player% 5000'
            """.trimIndent(),
        )

        // 2. Sample .multicrate file
        source.resolve("Crates/AllCrates.multicrate").writeText(
            """
            enabled: true
            display-name: '&dAll Crates'
            crates:
              - BasicCrate
            inventory-name: '&dServer Crates'
            rows: 3
            """.trimIndent(),
        )

        // 3. Sample custom animation
        source.resolve("animations/custom_spin.yml").writeText(
            """
            reward-item: 'r'
            items:
              g:
                material: GRAY_STAINED_GLASS_PANE
                name: ' '
            stages:
              '0':
                ticks: 10
                slots:
                  - 'g,g,g,g,g,g,g,g,g'
                  - 'g,g,g,g,r,g,g,g,g'
                  - 'g,g,g,g,g,g,g,g,g'
            """.trimIndent(),
        )

        // 4. Real SpecializedCrates flatfile ActiveCrates.db & PlayerData.db
        val playerUuid = UUID.randomUUID()
        source.resolve("ActiveCrates.db").writeText(
            """
            'world;100;70;-50':
              crate: BasicCrate
              placedTime: 1700000000000
            """.trimIndent(),
        )
        source.resolve("PlayerData.db").writeText(
            """
            '$playerUuid':
              virtual-crates: 'BasicCrate;2;7'
              crate-cooldowns: 'BasicCrate;${System.currentTimeMillis()}'
              history: 'Steve;BasicCrate;[diamonds];true'
            """.trimIndent(),
        )

        // Dry run first: writes nothing
        val dry = SpecializedCratesMigrator.migrate(source, target, menuDir, dryRun = true)
        assertTrue(dry.dryRun)
        assertEquals(2, dry.cratesMigrated)
        assertEquals(1, dry.animationsMigrated)
        assertFalse(target.resolve("crates/basiccrate.yml").exists())

        // Live run
        val live = SpecializedCratesMigrator.migrate(source, target, menuDir, dryRun = false, overwrite = true)
        assertFalse(live.dryRun)
        assertEquals(2, live.cratesMigrated)
        assertEquals(1, live.animationsMigrated)
        assertTrue(live.menusGenerated >= 1)
        assertEquals(1, live.placedCratesMigrated)
        assertEquals(1, live.playersMigrated)

        val catalog = CrateRegistry.loadAll(target)
        val basic = catalog.crates["basiccrate"]
        assertNotNull(basic)
        assertEquals(ObtainMethod.STATIC, basic!!.obtain)
        assertEquals(3, basic.rewards.size)
        assertEquals("fallback_cash", basic.rewards["vip"]?.fallback?.reward)
        assertTrue("allcrates" in catalog.crates)
        assertTrue(catalog.crates["allcrates"]!!.isMultiCrate)
        assertTrue("custom_spin" in catalog.animations)

        // Verify DB migration
        val placed = PlacedCrateRepository.all().firstOrNull { it.world == "world" && it.x == 100 && it.y == 70 && it.z == -50 }
        assertNotNull(placed)
        assertEquals("basiccrate", placed!!.crateId)
        val bal = VirtualBalanceRepository.get(playerUuid, "basiccrate")
        assertEquals(7, bal.keys)
        assertEquals(2, bal.crates)

        // Verify generated XaliMenu .conf exists
        val confs = Files.list(menuDir).use { s -> s.toList() }
        assertTrue(confs.isNotEmpty())
        assertTrue(confs.any { it.readText().contains("xalicrates:") })
    }
}
