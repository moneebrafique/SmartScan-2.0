package com.paypeico.chat.data

import com.paypeico.chat.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

/** One shared database connection, reopened automatically when it drops. */
object Db {
    private val mutex = Mutex()
    private var conn: Connection? = null

    /**
     * Runs [block] on a background thread with a live connection.
     * Reads retry once on a fresh connection; writes (retry = false) check the
     * connection first instead, so a message is never inserted twice.
     */
    suspend fun <T> use(retry: Boolean = true, block: (Connection) -> T): T =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                try {
                    block(open(validate = !retry))
                } catch (e: SQLException) {
                    closeQuietly()
                    if (!retry) throw e
                    block(open(validate = false))
                }
            }
        }

    private fun open(validate: Boolean): Connection {
        conn?.let { c ->
            val alive = !c.isClosed && (!validate || c.isValid(3))
            if (alive) return c
            closeQuietly()
        }
        Class.forName("com.mysql.jdbc.Driver")
        DriverManager.setLoginTimeout(10)
        val c = DriverManager.getConnection(Config.jdbcUrl, BuildConfig.DB_USER, BuildConfig.DB_PASS)
        conn = c
        return c
    }

    private fun closeQuietly() {
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        conn = null
    }
}
