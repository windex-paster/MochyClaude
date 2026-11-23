// the place picked on the search page (YouTube Music, SoundCloud, a module...) travels to the results page through here

package com.mochyy.mochymusic.ui.screens.search

/**
 * Set just before opening the results; the results page takes it once when it starts.
 * Kept out of the route on purpose so the existing "search/{query}" address stays exactly as it was.
 */
internal object SearchSourceHandoff {
    @Volatile
    private var id: String? = null

    fun set(sourceId: String?) {
        id = sourceId
    }

    fun take(): String? = id.also { id = null }
}
