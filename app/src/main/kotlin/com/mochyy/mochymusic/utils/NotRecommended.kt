// NotRecommended.kt

package com.mochyy.mochymusic.utils

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** songs you asked us to stop suggesting; autoplay, radios and home's picks all leave them out */
object NotRecommended {
    const val PREFS_NAME = "not_recommended"
    private const val KEY_SONGS = "songs"

    private lateinit var prefs: SharedPreferences

    @Volatile
    private var cached: Set<String>? = null

    // opening the file here starts loading it in the background, so the first check later doesn't wait on disk
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    val songs: Set<String>
        get() = cached ?: synchronized(this) {
            cached ?: prefs.getStringSet(KEY_SONGS, null).orEmpty().toSet().also { cached = it }
        }

    operator fun contains(songId: String): Boolean = songId in songs

    /** bumps on every change, so a screen listing the dislikes follows additions and removals */
    val version = kotlinx.coroutines.flow.MutableStateFlow(0)

    /** what the Dislikes page shows for one song; the title, artist and cover may be missing for old entries */
    data class Entry(val id: String, val title: String?, val artist: String?, val thumbnail: String?, val at: Long)

    private const val KEY_DETAILS = "details"

    @Synchronized
    fun add(songId: String, title: String? = null, artist: String? = null, thumbnail: String? = null) {
        if (title != null) {
            val all = detailsJson()
            all.put(songId, org.json.JSONObject().put("t", title).put("a", artist ?: "").put("c", thumbnail ?: "").put("at", System.currentTimeMillis()))
            prefs.edit { putString(KEY_DETAILS, all.toString()) }
        }
        save(songs + songId)
    }

    @Synchronized
    fun remove(songId: String) {
        val all = detailsJson()
        if (all.has(songId)) {
            all.remove(songId)
            prefs.edit { putString(KEY_DETAILS, all.toString()) }
        }
        save(songs - songId)
    }

    /** every disliked song, the newest first; songs added before details were kept come last */
    fun entries(): List<Entry> {
        val all = detailsJson()
        return songs.map { id ->
            val o = all.optJSONObject(id)
            Entry(id, o?.optString("t")?.ifEmpty { null }, o?.optString("a")?.ifEmpty { null }, o?.optString("c")?.ifEmpty { null }, o?.optLong("at") ?: 0L)
        }.sortedByDescending { it.at }
    }

    private fun detailsJson(): org.json.JSONObject =
        runCatching { org.json.JSONObject(prefs.getString(KEY_DETAILS, null) ?: "{}") }.getOrDefault(org.json.JSONObject())

    private fun save(next: Set<String>) {
        cached = next
        prefs.edit { putStringSet(KEY_SONGS, next) }
        version.value = version.value + 1
    }
}
