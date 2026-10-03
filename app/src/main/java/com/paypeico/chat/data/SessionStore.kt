package com.paypeico.chat.data

import android.content.Context

/** Keeps the user logged in between app launches. */
object SessionStore {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("session", Context.MODE_PRIVATE)

    fun load(ctx: Context): Me? {
        val p = prefs(ctx)
        val type = p.getString("type", null) ?: return null
        return Me(
            id = p.getLong("id", 0L),
            type = type,
            name = p.getString("name", "") ?: "",
            adminName = p.getString("admin_name", "") ?: "",
        )
    }

    fun save(ctx: Context, me: Me) {
        prefs(ctx).edit()
            .putLong("id", me.id)
            .putString("type", me.type)
            .putString("name", me.name)
            .putString("admin_name", me.adminName)
            .apply()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().apply()
    }
}
