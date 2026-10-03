package com.paypeico.chat.data

/** The logged-in person. type is one of: admin, user, charging, processor. */
data class Me(
    val id: Long,
    val type: String,
    val name: String,
    val adminName: String,
)

data class Participant(
    val id: Long,
    val type: String,
    val name: String,
    val unread: Int = 0,
    val lastId: Long = 0L,
    val lastText: String? = null,
    val lastTime: String? = null,
    val lastMine: Boolean = false,
)

data class Msg(
    val id: Long,
    val senderId: Long,
    val senderType: String,
    val senderName: String,
    val text: String,
    val time: String,
)

sealed class LoginResult {
    data class Ok(val me: Me) : LoginResult()
    data class Error(val message: String) : LoginResult()
}

fun typeLabel(type: String): String = when (type) {
    "admin" -> "Admin"
    "user" -> "Agent"
    "charging" -> "Charging"
    "processor" -> "Processor"
    else -> type
}
