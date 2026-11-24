package mc.xalitoria.crates.paper.data.db.repository

import kotlinx.serialization.json.Json
import mc.xalitoria.crates.paper.data.db.DatabaseManager.dbTransaction
import mc.xalitoria.crates.paper.data.db.tables.CooldownTable
import mc.xalitoria.crates.paper.data.db.tables.HistoryTable
import mc.xalitoria.crates.paper.data.db.tables.PlacedCrateTable
import mc.xalitoria.crates.paper.data.db.tables.VirtualBalanceTable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.minus
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.insertAndGetId
import org.jetbrains.exposed.v1.jdbc.insertIgnore
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.update
import java.util.UUID
import kotlin.uuid.toJavaUuid
import kotlin.uuid.toKotlinUuid

data class PlacedCrateRecord(
    val id: Long,
    val world: String,
    val x: Int,
    val y: Int,
    val z: Int,
    val crateId: String,
    val placedAt: Long,
    val expiresAt: Long,
) {
    val blockKey: BlockPos get() = BlockPos(world, x, y, z)
}

data class BlockPos(val world: String, val x: Int, val y: Int, val z: Int) {
    fun asKey(): String = "$world:$x:$y:$z"
}

data class VirtualBalance(val keys: Int = 0, val crates: Int = 0) {
    companion object {
        val ZERO = VirtualBalance()
    }
}

data class HistoryRecord(
    val id: Long,
    val player: UUID,
    val playerName: String,
    val crateId: String,
    val rewards: List<String>,
    val openedAt: Long,
    val virtual: Boolean,
)

object PlacedCrateRepository {
    private fun ResultRow.toRecord() = PlacedCrateRecord(
        id = this[PlacedCrateTable.id].value,
        world = this[PlacedCrateTable.world],
        x = this[PlacedCrateTable.x],
        y = this[PlacedCrateTable.y],
        z = this[PlacedCrateTable.z],
        crateId = this[PlacedCrateTable.crateId],
        placedAt = this[PlacedCrateTable.placedAt],
        expiresAt = this[PlacedCrateTable.expiresAt],
    )

    suspend fun all(): List<PlacedCrateRecord> = dbTransaction {
        PlacedCrateTable.selectAll().map { it.toRecord() }
    }

    suspend fun put(world: String, x: Int, y: Int, z: Int, crateId: String, placedAt: Long, expiresAt: Long = 0L): PlacedCrateRecord = dbTransaction {
        val existing = PlacedCrateTable.selectAll().where {
            (PlacedCrateTable.world eq world) and (PlacedCrateTable.x eq x) and (PlacedCrateTable.y eq y) and (PlacedCrateTable.z eq z)
        }.singleOrNull()
        if (existing != null) {
            val id = existing[PlacedCrateTable.id].value
            PlacedCrateTable.update({ PlacedCrateTable.id eq id }) {
                it[PlacedCrateTable.crateId] = crateId
                it[PlacedCrateTable.placedAt] = placedAt
                it[PlacedCrateTable.expiresAt] = expiresAt
            }
            PlacedCrateRecord(id, world, x, y, z, crateId, placedAt, expiresAt)
        } else {
            val id = PlacedCrateTable.insertAndGetId {
                it[PlacedCrateTable.world] = world
                it[PlacedCrateTable.x] = x
                it[PlacedCrateTable.y] = y
                it[PlacedCrateTable.z] = z
                it[PlacedCrateTable.crateId] = crateId
                it[PlacedCrateTable.placedAt] = placedAt
                it[PlacedCrateTable.expiresAt] = expiresAt
            }.value
            PlacedCrateRecord(id, world, x, y, z, crateId, placedAt, expiresAt)
        }
    }

    suspend fun deleteAt(world: String, x: Int, y: Int, z: Int): Boolean = dbTransaction {
        PlacedCrateTable.deleteWhere {
            (PlacedCrateTable.world eq world) and (PlacedCrateTable.x eq x) and (PlacedCrateTable.y eq y) and (PlacedCrateTable.z eq z)
        } > 0
    }

    suspend fun deleteByCrate(crateId: String): Int = dbTransaction {
        PlacedCrateTable.deleteWhere { PlacedCrateTable.crateId eq crateId }
    }

    suspend fun renameCrate(oldId: String, newId: String): Int = dbTransaction {
        PlacedCrateTable.update({ PlacedCrateTable.crateId eq oldId }) {
            it[PlacedCrateTable.crateId] = newId
        }
    }
}

/**
 * Guarded atomic updates for virtual keys & crates (never read-modify-write, safe under concurrent servers & InnoDB).
 */
object VirtualBalanceRepository {
    private fun ResultRow.toBalance() = VirtualBalance(this[VirtualBalanceTable.keys], this[VirtualBalanceTable.crates])

    private fun JdbcTransaction.read(player: UUID, crateId: String): VirtualBalance? =
        VirtualBalanceTable.selectAll()
            .where { (VirtualBalanceTable.player eq player.toKotlinUuid()) and (VirtualBalanceTable.crateId eq crateId) }
            .singleOrNull()?.toBalance()

    private fun JdbcTransaction.ensure(player: UUID, crateId: String) {
        if (read(player, crateId) != null) return
        VirtualBalanceTable.insertIgnore {
            it[VirtualBalanceTable.player] = player.toKotlinUuid()
            it[VirtualBalanceTable.crateId] = crateId
        }
    }

    suspend fun of(player: UUID): Map<String, VirtualBalance> = dbTransaction {
        VirtualBalanceTable.selectAll()
            .where { VirtualBalanceTable.player eq player.toKotlinUuid() }
            .associate { it[VirtualBalanceTable.crateId] to it.toBalance() }
    }

    suspend fun get(player: UUID, crateId: String): VirtualBalance = dbTransaction {
        read(player, crateId) ?: VirtualBalance.ZERO
    }

    suspend fun giveKeys(player: UUID, crateId: String, amount: Int): Int {
        require(amount >= 1) { "amount must be >= 1" }
        return dbTransaction {
            ensure(player, crateId)
            VirtualBalanceTable.update({ (VirtualBalanceTable.player eq player.toKotlinUuid()) and (VirtualBalanceTable.crateId eq crateId) }) {
                it[keys] = keys + amount
            }
            read(player, crateId)!!.keys
        }
    }

    /** Atomically consumes [amount] keys if available; returns true if deducted. */
    suspend fun consumeKeys(player: UUID, crateId: String, amount: Int = 1): Boolean {
        require(amount >= 1) { "amount must be >= 1" }
        return dbTransaction {
            VirtualBalanceTable.update({
                (VirtualBalanceTable.player eq player.toKotlinUuid()) and
                    (VirtualBalanceTable.crateId eq crateId) and
                    (VirtualBalanceTable.keys greaterEq amount)
            }) {
                it[keys] = keys - amount
            } > 0
        }
    }

    /** Takes up to [amount] keys from [player]; returns how many were actually taken. */
    suspend fun takeKeys(player: UUID, crateId: String, amount: Int): Int {
        require(amount >= 1) { "amount must be >= 1" }
        return dbTransaction {
            val id = player.toKotlinUuid()
            val updated = VirtualBalanceTable.update({
                (VirtualBalanceTable.player eq id) and (VirtualBalanceTable.crateId eq crateId) and (VirtualBalanceTable.keys greaterEq amount)
            }) {
                it[keys] = keys - amount
            }
            if (updated > 0) return@dbTransaction amount
            val held = read(player, crateId)?.keys ?: 0
            if (held > 0 && VirtualBalanceTable.update({
                    (VirtualBalanceTable.player eq id) and (VirtualBalanceTable.crateId eq crateId) and (VirtualBalanceTable.keys eq held)
                }) { it[keys] = 0 } > 0) held else 0
        }
    }

    /** Transfers [amount] keys from [from] to [to] in one transaction (touching rows in UUID order to prevent InnoDB deadlocks). */
    suspend fun transferKeys(from: UUID, to: UUID, crateId: String, amount: Int): Boolean {
        require(amount >= 1) { "amount must be >= 1" }
        if (from == to) return false
        return dbTransaction {
            val first = if (from < to) from else to
            val second = if (from < to) to else from
            ensure(first, crateId)
            ensure(second, crateId)
            val deducted = VirtualBalanceTable.update({
                (VirtualBalanceTable.player eq from.toKotlinUuid()) and
                    (VirtualBalanceTable.crateId eq crateId) and
                    (VirtualBalanceTable.keys greaterEq amount)
            }) {
                it[keys] = keys - amount
            }
            if (deducted == 0) return@dbTransaction false
            VirtualBalanceTable.update({
                (VirtualBalanceTable.player eq to.toKotlinUuid()) and (VirtualBalanceTable.crateId eq crateId)
            }) {
                it[keys] = keys + amount
            }
            true
        }
    }

    suspend fun giveCrates(player: UUID, crateId: String, amount: Int): Int {
        require(amount >= 1) { "amount must be >= 1" }
        return dbTransaction {
            ensure(player, crateId)
            VirtualBalanceTable.update({ (VirtualBalanceTable.player eq player.toKotlinUuid()) and (VirtualBalanceTable.crateId eq crateId) }) {
                it[crates] = crates + amount
            }
            read(player, crateId)!!.crates
        }
    }

    suspend fun consumeCrates(player: UUID, crateId: String, amount: Int = 1): Boolean {
        require(amount >= 1) { "amount must be >= 1" }
        return dbTransaction {
            VirtualBalanceTable.update({
                (VirtualBalanceTable.player eq player.toKotlinUuid()) and
                    (VirtualBalanceTable.crateId eq crateId) and
                    (VirtualBalanceTable.crates greaterEq amount)
            }) {
                it[crates] = crates - amount
            } > 0
        }
    }

    suspend fun takeCrates(player: UUID, crateId: String, amount: Int): Int {
        require(amount >= 1) { "amount must be >= 1" }
        return dbTransaction {
            val id = player.toKotlinUuid()
            val updated = VirtualBalanceTable.update({
                (VirtualBalanceTable.player eq id) and (VirtualBalanceTable.crateId eq crateId) and (VirtualBalanceTable.crates greaterEq amount)
            }) {
                it[crates] = crates - amount
            }
            if (updated > 0) return@dbTransaction amount
            val held = read(player, crateId)?.crates ?: 0
            if (held > 0 && VirtualBalanceTable.update({
                    (VirtualBalanceTable.player eq id) and (VirtualBalanceTable.crateId eq crateId) and (VirtualBalanceTable.crates eq held)
                }) { it[crates] = 0 } > 0) held else 0
        }
    }
}

object CooldownRepository {
    suspend fun activeOf(player: UUID, now: Long = System.currentTimeMillis()): Map<Pair<String, String>, Long> = dbTransaction {
        CooldownTable.selectAll()
            .where { (CooldownTable.player eq player.toKotlinUuid()) and (CooldownTable.expiresAt greater now) }
            .associate { (it[CooldownTable.crateId] to it[CooldownTable.locationKey]) to it[CooldownTable.expiresAt] }
    }

    suspend fun set(player: UUID, crateId: String, locationKey: String, expiresAt: Long) {
        dbTransaction {
            val id = player.toKotlinUuid()
            val updated = CooldownTable.update({
                (CooldownTable.player eq id) and (CooldownTable.crateId eq crateId) and (CooldownTable.locationKey eq locationKey)
            }) {
                it[CooldownTable.expiresAt] = expiresAt
            }
            if (updated == 0) {
                CooldownTable.insertIgnore {
                    it[CooldownTable.player] = id
                    it[CooldownTable.crateId] = crateId
                    it[CooldownTable.locationKey] = locationKey
                    it[CooldownTable.expiresAt] = expiresAt
                }
            }
        }
    }

    suspend fun purgeExpired(now: Long = System.currentTimeMillis()): Int = dbTransaction {
        CooldownTable.deleteWhere { CooldownTable.expiresAt lessEq now }
    }
}

object HistoryRepository {
    private val json = Json { ignoreUnknownKeys = true }

    private fun ResultRow.toRecord() = HistoryRecord(
        id = this[HistoryTable.id].value,
        player = this[HistoryTable.player].toJavaUuid(),
        playerName = this[HistoryTable.playerName],
        crateId = this[HistoryTable.crateId],
        rewards = runCatching { json.decodeFromString<List<String>>(this[HistoryTable.rewards]) }.getOrElse { [] },
        openedAt = this[HistoryTable.openedAt],
        virtual = this[HistoryTable.virtual],
    )

    suspend fun record(
        player: UUID,
        playerName: String,
        crateId: String,
        rewards: List<String>,
        openedAt: Long,
        virtual: Boolean,
        maxPerPlayer: Int = 500,
    ): Long = dbTransaction {
        val id = HistoryTable.insertAndGetId {
            it[HistoryTable.player] = player.toKotlinUuid()
            it[HistoryTable.playerName] = playerName.take(32)
            it[HistoryTable.crateId] = crateId
            it[HistoryTable.rewards] = json.encodeToString(rewards)
            it[HistoryTable.openedAt] = openedAt
            it[HistoryTable.virtual] = virtual
        }.value
        if (maxPerPlayer > 0) {
            val cutoff = HistoryTable.selectAll()
                .where { HistoryTable.player eq player.toKotlinUuid() }
                .orderBy(HistoryTable.id, SortOrder.DESC)
                .limit(1)
                .offset(maxPerPlayer.toLong())
                .singleOrNull()?.get(HistoryTable.id)?.value
            if (cutoff != null) {
                HistoryTable.deleteWhere { (HistoryTable.player eq player.toKotlinUuid()) and (HistoryTable.id lessEq cutoff) }
            }
        }
        id
    }

    suspend fun recent(player: UUID, limit: Int = 20): List<HistoryRecord> = dbTransaction {
        HistoryTable.selectAll()
            .where { HistoryTable.player eq player.toKotlinUuid() }
            .orderBy(HistoryTable.id, SortOrder.DESC)
            .limit(limit.coerceIn(1, 200))
            .map { it.toRecord() }
    }
}
