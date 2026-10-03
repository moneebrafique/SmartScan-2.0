package com.paypeico.chat.data

import com.paypeico.chat.BuildConfig

object Config {
    /**
     * Must match 'timezone' in paypeico's config/app.php (or APP_TIMEZONE in .env).
     * Used so messages sent from the phone get the same kind of timestamps as the web.
     */
    const val LARAVEL_TIMEZONE = "Asia/Karachi"

    /** How often an open conversation checks for new messages. */
    const val MESSAGE_POLL_MS = 2_000L

    /** How often the chat list refreshes unread counts. */
    const val LIST_POLL_MS = 3_000L

    val jdbcUrl: String
        get() = "jdbc:mysql://${BuildConfig.DB_HOST}:${BuildConfig.DB_PORT}/${BuildConfig.DB_NAME}" +
            "?useSSL=false&useUnicode=true&characterEncoding=UTF-8" +
            "&connectTimeout=10000&socketTimeout=20000"
}
