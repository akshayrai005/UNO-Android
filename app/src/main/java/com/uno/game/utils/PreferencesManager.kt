package com.uno.game.utils

import android.content.Context

object PreferencesManager {
    private const val PREFS_NAME = "uno_prefs"
    private const val KEY_PLAYER_ID  = "player_id"
    private const val KEY_USERNAME   = "username"
    private const val KEY_LAST_ROOM  = "last_room_code"

    fun savePlayer(context: Context, playerId: String, username: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_PLAYER_ID, playerId)
            .putString(KEY_USERNAME, username)
            .apply()
    }

    fun saveLastRoom(context: Context, roomCode: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_LAST_ROOM, roomCode)
            .apply()
    }

    fun clearLastRoom(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .remove(KEY_LAST_ROOM)
            .apply()
    }

    fun getPlayerId(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_PLAYER_ID, null)

    fun getUsername(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_USERNAME, null)

    fun getLastRoom(context: Context): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LAST_ROOM, null)

    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().apply()
    }
}
