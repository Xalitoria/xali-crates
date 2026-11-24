package mc.xalitoria.crates.paper.data.db.tables

import mc.xalitoria.crates.paper.data.db.DatabaseManager
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.dao.id.LongIdTable

/** Placed crates in worlds (static, dynamic, or lucky chests). */
object PlacedCrateTable : LongIdTable(DatabaseManager.tableName("placed")) {
    val world = varchar("world", 128)
    val x = integer("x")
    val y = integer("y")
    val z = integer("z")
    val crateId = varchar("crate_id", 64).index()
    val placedAt = long("placed_at")
    val expiresAt = long("expires_at").default(0L)

    init {
        uniqueIndex(world, x, y, z)
    }
}

/** Virtual keys and virtual crates held by a player per crate id. */
object VirtualBalanceTable : Table(DatabaseManager.tableName("virtual")) {
    val player = uuid("player")
    val crateId = varchar("crate_id", 64)
    val keys = integer("keys").default(0)
    val crates = integer("crates").default(0)

    override val primaryKey = PrimaryKey(player, crateId)
}

/** Active crate cooldowns (locationKey is empty string for per-crate cooldowns, or `world:x:y:z` for per-location). */
object CooldownTable : Table(DatabaseManager.tableName("cooldowns")) {
    val player = uuid("player")
    val crateId = varchar("crate_id", 64)
    val locationKey = varchar("location_key", 192).default("")
    val expiresAt = long("expires_at")

    override val primaryKey = PrimaryKey(player, crateId, locationKey)
}

/** Crate opening audit log / player history. */
object HistoryTable : LongIdTable(DatabaseManager.tableName("history")) {
    val player = uuid("player").index()
    val playerName = varchar("player_name", 32)
    val crateId = varchar("crate_id", 64).index()
    val rewards = text("rewards")
    val openedAt = long("opened_at").index()
    val virtual = bool("virtual").default(false)
}
