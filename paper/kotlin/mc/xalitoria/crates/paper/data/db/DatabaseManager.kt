package mc.xalitoria.crates.paper.data.db

import com.xalitoria.util.config.DatabaseConfig
import com.xalitoria.util.config.databaseType
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mc.xalitoria.crates.paper.XaliCrates
import mc.xalitoria.crates.paper.XaliCrates.Companion.debug
import mc.xalitoria.crates.paper.data.db.tables.CooldownTable
import mc.xalitoria.crates.paper.data.db.tables.HistoryTable
import mc.xalitoria.crates.paper.data.db.tables.PlacedCrateTable
import mc.xalitoria.crates.paper.data.db.tables.VirtualBalanceTable
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.JdbcTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils

/** HikariCP pool and Exposed [Database] for XaliCrates tables. */
object DatabaseManager {
    lateinit var dataSource: HikariDataSource
        private set
    lateinit var database: Database
        private set

    var tablePrefix = "xalicrates_"
        private set

    fun tableName(name: String): String = tablePrefix + name

    fun connect(plugin: XaliCrates, config: DatabaseConfig) {
        val hikari = HikariConfig()
        val type = config.databaseType
        if (type.isRemote) {
            val mysql = config.mysql
            hikari.jdbcUrl = mysql.jdbcUrl(type)
            hikari.driverClassName = mysql.driverClass(type)
            hikari.username = mysql.username
            hikari.password = mysql.password
            hikari.maximumPoolSize = mysql.poolSize.coerceAtLeast(1)
        } else {
            val file = plugin.dataFolder.resolve(config.sqlite.filename)
            file.parentFile.mkdirs()
            hikari.jdbcUrl = "jdbc:sqlite:${file.absolutePath}"
            hikari.driverClassName = "org.sqlite.JDBC"
            hikari.maximumPoolSize = 1
        }
        hikari.poolName = "XaliCrates-DB"
        attach(HikariDataSource(hikari), config)
    }

    internal fun attach(source: HikariDataSource, config: DatabaseConfig) {
        dataSource = source
        tablePrefix = DatabaseConfig.checkPrefix(config.tablePrefix)
        database = Database.connect(source)
        debug("Attaching database: prefix='{0}'", tablePrefix)

        transaction(database) {
            MigrationUtils.statementsRequiredForDatabaseMigration(
                PlacedCrateTable,
                VirtualBalanceTable,
                CooldownTable,
                HistoryTable,
                withLogs = false,
            ).onEach { debug("Schema statement: {0}", it) }.forEach { exec(it) }
        }
    }

    fun close() {
        if (::dataSource.isInitialized) dataSource.close()
    }

    suspend fun <T> dbTransaction(statement: suspend JdbcTransaction.() -> T): T =
        withContext(Dispatchers.IO) { suspendTransaction(db = database, statement = statement) }
}
