// the media id of a song that comes from a source's own search (SoundCloud): "BR_soundcloud_123456", so the player asks that source by its own id and never YouTube

package com.mochyy.mochymusic.modules

import com.mochyy.mochymusic.models.MediaMetadata
import com.mochyy.mochymusic.module.SearchHit

object BrowsedIds {
    private const val Prefix = "BR_"

    fun mediaId(moduleId: String, hitId: String) = "$Prefix${moduleId}_$hitId"

    fun isBrowsed(mediaId: String) = mediaId.startsWith(Prefix) && mediaId.count { it == '_' } >= 2

    /** the module and the source's own id, null when [mediaId] isn't one of these */
    fun parse(mediaId: String): Pair<String, String>? {
        if (!isBrowsed(mediaId)) return null
        val rest = mediaId.removePrefix(Prefix)
        return rest.substringBefore('_') to rest.substringAfter('_')
    }

    fun metadataOf(moduleId: String, hit: SearchHit) = MediaMetadata(
        id = mediaId(moduleId, hit.id),
        title = hit.title,
        artists = listOf(MediaMetadata.Artist(id = null, name = hit.artist.ifBlank { "Unknown artist" })),
        duration = ((hit.durationMs ?: 0L) / 1000L).toInt().takeIf { it > 0 } ?: -1,
        thumbnailUrl = hit.artworkUrl,
    )
}
