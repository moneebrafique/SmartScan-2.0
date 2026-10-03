package com.paypeico.chat.data

import org.mindrot.jbcrypt.BCrypt
import java.sql.Connection

/**
 * All chat database work. Mirrors paypeico's ChatController and the chat
 * script in the dashboards, so the phone and the web stay in sync.
 */
object ChatRepo {

    private data class AccountTable(val table: String, val type: String, val hasStatus: Boolean)

    // Same order as admincontroller::postLogin
    private val accountTables = listOf(
        AccountTable("admins", "admin", hasStatus = true),
        AccountTable("users", "user", hasStatus = true),
        AccountTable("chargings", "charging", hasStatus = true),
        AccountTable("processors", "processor", hasStatus = false), // processors have no status column
    )

    // ---------------------------------------------------------------- login

    suspend fun login(email: String, password: String): LoginResult = Db.use { c ->
        var result: LoginResult = LoginResult.Error("Incorrect email or password")
        for (t in accountTables) {
            val r = tryAccount(c, t, email, password)
            if (r != null) {
                result = r
                break
            }
        }
        result
    }

    private fun tryAccount(c: Connection, t: AccountTable, email: String, password: String): LoginResult? {
        val cols = if (t.hasStatus) "id, name, password, status, admin" else "id, name, password, admin"
        return c.prepareStatement("SELECT $cols FROM ${t.table} WHERE email = ? LIMIT 1").use { ps ->
            ps.setString(1, email)
            ps.executeQuery().use { rs ->
                when {
                    !rs.next() -> null
                    !checkPassword(password, rs.getString("password") ?: "") -> null
                    t.hasStatus && rs.getString("status") != "active" ->
                        LoginResult.Error("Your account is inactive. Please contact support.")
                    else -> LoginResult.Ok(
                        Me(
                            id = rs.getLong("id"),
                            type = t.type,
                            name = rs.getString("name") ?: "",
                            adminName = rs.getString("admin") ?: "",
                        )
                    )
                }
            }
        }
    }

    /** Laravel stores bcrypt hashes as $2y$...; jBCrypt reads the identical $2a$ form. */
    private fun checkPassword(plain: String, hash: String): Boolean = try {
        val normalized = if (hash.startsWith("\$2y\$")) "\$2a\$" + hash.substring(4) else hash
        BCrypt.checkpw(plain, normalized)
    } catch (_: Exception) {
        false
    }

    /** False if the account was deleted or deactivated since the user logged in. */
    suspend fun revalidate(me: Me): Boolean {
        val t = accountTables.firstOrNull { it.type == me.type } ?: return false
        val sql = if (t.hasStatus) "SELECT status FROM ${t.table} WHERE id = ?"
        else "SELECT id FROM ${t.table} WHERE id = ?"
        return Db.use { c ->
            c.prepareStatement(sql).use { ps ->
                ps.setLong(1, me.id)
                ps.executeQuery().use { rs ->
                    rs.next() && (!t.hasStatus || rs.getString(1) == "active")
                }
            }
        }
    }

    // --------------------------------------------------------- participants

    /** Same people the web chat sidebar shows, sorted by unread count. */
    suspend fun participants(me: Me): List<Participant> = Db.use { c ->
        val list = mutableListOf<Participant>()

        fun load(sql: String, type: String) {
            c.prepareStatement(sql).use { ps ->
                ps.setString(1, me.adminName)
                ps.executeQuery().use { rs ->
                    while (rs.next()) {
                        list.add(Participant(rs.getLong("id"), type, rs.getString("name") ?: ""))
                    }
                }
            }
        }

        load("SELECT id, name FROM users WHERE admin = ?", "user")
        load("SELECT id, name FROM chargings WHERE admin = ?", "charging")
        load("SELECT id, name FROM admins WHERE name = ?", "admin")
        load("SELECT id, name FROM processors WHERE admin = ?", "processor")

        val unread = unreadCounts(c, me)
        val last = lastMessages(c, me)

        list
            .filter { p ->
                !(p.id == me.id && p.type == me.type) &&            // not yourself
                    !(me.type == "user" && p.type == "user") &&       // agents don't see agents
                    !(me.type == "processor" && p.type == "processor") // processors don't see processors
            }
            .map { p ->
                val key = "${p.type}_${p.id}"
                val lm = last[key]
                p.copy(
                    unread = unread[key] ?: 0,
                    lastId = lm?.id ?: 0L,
                    lastText = lm?.text,
                    lastTime = lm?.time,
                    lastMine = lm?.mine ?: false,
                )
            }
            // Unread first, then most recent conversation, like WhatsApp.
            .sortedWith(
                compareByDescending<Participant> { it.unread > 0 }
                    .thenByDescending { it.lastId }
            )
    }

    private data class LastMsg(val id: Long, val text: String, val time: String, val mine: Boolean)

    /** The newest message of every conversation [me] is part of, keyed by the other person. */
    private fun lastMessages(c: Connection, me: Me): Map<String, LastMsg> {
        val sql = "SELECT m.id, m.sender_id, m.sender_type, m.receiver_id, m.receiver_type, m.message, " +
            "DATE_FORMAT(m.created_at, '%Y-%m-%d %H:%i:%s') AS ts FROM chat_messages m " +
            "WHERE m.id IN (SELECT MAX(id) FROM chat_messages " +
            "WHERE admin_name = ? AND ((sender_id = ? AND sender_type = ?) OR (receiver_id = ? AND receiver_type = ?)) " +
            "GROUP BY IF(sender_id = ? AND sender_type = ?, " +
            "CONCAT(receiver_type, '_', receiver_id), CONCAT(sender_type, '_', sender_id)))"
        val out = HashMap<String, LastMsg>()
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, me.adminName)
            ps.setLong(2, me.id); ps.setString(3, me.type)
            ps.setLong(4, me.id); ps.setString(5, me.type)
            ps.setLong(6, me.id); ps.setString(7, me.type)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    val mine = rs.getLong("sender_id") == me.id && rs.getString("sender_type") == me.type
                    val key = if (mine) "${rs.getString("receiver_type")}_${rs.getLong("receiver_id")}"
                    else "${rs.getString("sender_type")}_${rs.getLong("sender_id")}"
                    out[key] = LastMsg(
                        id = rs.getLong("id"),
                        text = rs.getString("message") ?: "",
                        time = rs.getString("ts") ?: "",
                        mine = mine,
                    )
                }
            }
        }
        return out
    }

    private fun readColumn(type: String): String = when (type) {
        "admin" -> "read_by_admin"
        "charging" -> "read_by_charging"
        else -> "read_by_user" // users and processors share this column, same as the web
    }

    private fun unreadCounts(c: Connection, me: Me): Map<String, Int> {
        val col = readColumn(me.type)
        val sql = "SELECT sender_type, sender_id, COUNT(*) AS n FROM chat_messages " +
            "WHERE admin_name = ? AND receiver_id = ? AND receiver_type = ? AND $col = 0 " +
            "GROUP BY sender_type, sender_id"
        val out = HashMap<String, Int>()
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, me.adminName)
            ps.setLong(2, me.id)
            ps.setString(3, me.type)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out["${rs.getString("sender_type")}_${rs.getLong("sender_id")}"] = rs.getInt("n")
                }
            }
        }
        return out
    }

    // ------------------------------------------------------------- messages

    /** Messages between [me] and [other] with id greater than [afterId], oldest first. */
    suspend fun messages(me: Me, other: Participant, afterId: Long): List<Msg> = Db.use { c ->
        val sql = "SELECT id, sender_id, sender_type, sender_name, message, " +
            "DATE_FORMAT(created_at, '%Y-%m-%d %H:%i:%s') AS ts " +
            "FROM chat_messages WHERE admin_name = ? AND id > ? AND (" +
            "(sender_id = ? AND sender_type = ? AND receiver_id = ? AND receiver_type = ?) OR " +
            "(sender_id = ? AND sender_type = ? AND receiver_id = ? AND receiver_type = ?)" +
            ") ORDER BY id ASC"
        val out = mutableListOf<Msg>()
        c.prepareStatement(sql).use { ps ->
            ps.setString(1, me.adminName)
            ps.setLong(2, afterId)
            ps.setLong(3, me.id); ps.setString(4, me.type)
            ps.setLong(5, other.id); ps.setString(6, other.type)
            ps.setLong(7, other.id); ps.setString(8, other.type)
            ps.setLong(9, me.id); ps.setString(10, me.type)
            ps.executeQuery().use { rs ->
                while (rs.next()) {
                    out.add(
                        Msg(
                            id = rs.getLong("id"),
                            senderId = rs.getLong("sender_id"),
                            senderType = rs.getString("sender_type") ?: "",
                            senderName = rs.getString("sender_name") ?: "",
                            text = rs.getString("message") ?: "",
                            time = rs.getString("ts") ?: "",
                        )
                    )
                }
            }
        }
        out
    }

    /** Marks everything [other] sent to [me] as read (same as /chat/mark-read). */
    suspend fun markRead(me: Me, other: Participant) {
        val col = readColumn(me.type)
        Db.use { c ->
            c.prepareStatement(
                "UPDATE chat_messages SET $col = 1 WHERE admin_name = ? AND sender_id = ? " +
                    "AND sender_type = ? AND receiver_id = ? AND receiver_type = ? AND $col = 0"
            ).use { ps ->
                ps.setString(1, me.adminName)
                ps.setLong(2, other.id)
                ps.setString(3, other.type)
                ps.setLong(4, me.id)
                ps.setString(5, me.type)
                ps.executeUpdate()
            }
        }
    }

    /** Inserts a message exactly like ChatController::postMessage. */
    suspend fun send(me: Me, other: Participant, text: String) {
        val now = TimeFmt.now()
        Db.use(retry = false) { c ->
            c.prepareStatement(
                "INSERT INTO chat_messages (admin_name, sender_type, sender_id, sender_name, message, " +
                    "receiver_id, receiver_type, is_system, created_at, updated_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, 0, ?, ?)"
            ).use { ps ->
                ps.setString(1, me.adminName)
                ps.setString(2, me.type)
                ps.setLong(3, me.id)
                ps.setString(4, me.name)
                ps.setString(5, text)
                ps.setLong(6, other.id)
                ps.setString(7, other.type)
                ps.setString(8, now)
                ps.setString(9, now)
                ps.executeUpdate()
            }
        }
    }
}
