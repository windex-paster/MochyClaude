// a module that can only be asked for one known song (find it, then play it) shown as a source on the search page:
// the songs come from youtube music's search, and a press plays that song through the module alone, the way "test the module" does

package com.mochyy.mochymusic.modules

import com.mochyy.mochymusic.module.BrowsableSourceModule
import com.mochyy.mochymusic.module.ModuleUnavailable
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SearchHit
import com.mochyy.mochymusic.module.SourceModule
import com.mochyy.mochymusic.module.TrackQuery
import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import java.util.concurrent.ConcurrentHashMap

internal class ViaModule(val inner: SourceModule) : BrowsableSourceModule, SourceModule by inner {

    override val searchLabel: String get() = inner.info.name

    // what each found song is, so a press hands the module its title and artists
    private val seen = ConcurrentHashMap<String, TrackQuery>()

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val songs = YouTube.search(q, YouTube.SearchFilter.FILTER_SONG).getOrElse {
            throw ModuleUnavailable(it.message ?: "YouTube Music didn't answer")
        }.items.filterIsInstance<SongItem>().take(limit)
        return songs.map { song ->
            seen[song.id] = queryOf(song)
            SearchHit(
                id = song.id,
                title = song.title,
                artist = song.artists.joinToString(", ") { it.name },
                durationMs = song.duration?.takeIf { it > 0 }?.times(1000L),
                artworkUrl = song.thumbnail,
            )
        }
    }

    override suspend fun streamFor(id: String, picks: Map<String, String>): ResolveResult {
        // after a restart the search is gone: ask youtube music what the song is once more
        val track = seen[id] ?: YouTube.queue(listOf(id)).getOrNull()?.firstOrNull()?.let(::queryOf)?.also { seen[id] = it }
            ?: return ResolveResult.NotFound("Search again to play this song")
        return inner.resolve(track, picks)
    }

    private fun queryOf(song: SongItem) = TrackQuery(
        title = song.title,
        artists = song.artists.map { it.name },
        album = song.album?.name,
        durationMs = song.duration?.takeIf { it > 0 }?.times(1000L),
    )
}
