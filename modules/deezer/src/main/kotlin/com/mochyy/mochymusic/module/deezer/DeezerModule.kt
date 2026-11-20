package com.mochyy.mochymusic.module.deezer

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.BrowsableSourceModule
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.EntryKind
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ModuleUnavailable
import com.mochyy.mochymusic.module.NO_ACCOUNT_LABEL
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SearchHit
import com.mochyy.mochymusic.module.SourceEntry
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.TrackQuery
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder

/**
 * Deezer's catalog: search, artists, albums, playlists and charts through Deezer's open API
 * (api.deezer.com), no account and no key.
 *
 * Deezer's open API gives no full-length audio, only 30-second previews, and this module does not
 * go around that. So a Deezer song is played by Mochy from the user's other sources, matched by
 * title and artist, and the 30-second preview is only the last resort when none of them has it.
 */
class DeezerModule : BrowsableSourceModule {

    override val info = ModuleInfo(
        id = Id,
        name = "Deezer",
        version = "1.0",
        description = "Search Deezer's catalog: songs, artists, albums and playlists. Plays from your other sources by name; Deezer itself gives only 30-second previews",
    )

    override val searchLabel = "Deezer"

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override fun accountState(): AccountState = AccountState.SignedIn(NO_ACCOUNT_LABEL)

    override suspend fun verifyAccount(): AccountState = try {
        get("/chart/0/tracks?limit=1", "the chart")
        AccountState.SignedIn("$NO_ACCOUNT_LABEL · Deezer reachable")
    } catch (e: ModuleUnavailable) {
        AccountState.Expired(e.message ?: "Deezer didn't answer")
    }

    // ---- search -------------------------------------------------------------------------------

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return tracksOf(get("/search?q=${enc(q)}&limit=$limit", "the search"))
    }

    /** more from the same artist: Deezer's open API has no per-song radio */
    override suspend fun related(id: String, limit: Int): List<SearchHit> {
        val artist = trackObject(id)?.get("artist")?.let { (it as? JsonObject)?.long("id") } ?: return emptyList()
        return tracksOf(get("/artist/$artist/top?limit=$limit", "the artist's songs")).filter { it.id != id }
    }

    override val entryKinds = listOf(EntryKind.Artist, EntryKind.Album, EntryKind.Playlist)

    override suspend fun searchEntries(query: String, kind: EntryKind, limit: Int): List<SourceEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val path = when (kind) {
            EntryKind.Artist -> "artist"
            EntryKind.Album -> "album"
            EntryKind.Playlist -> "playlist"
        }
        return dataOf(get("/search/$path?q=${enc(q)}&limit=$limit", "the search")).mapNotNull { entryOf(kind, it) }
    }

    private fun entryOf(kind: EntryKind, o: JsonObject): SourceEntry? {
        val id = o.long("id") ?: return null
        return when (kind) {
            EntryKind.Artist -> SourceEntry(
                kind, id.toString(), o.string("name") ?: return null,
                o.long("nb_fan")?.let { "$it fans" }.orEmpty(),
                o.string("picture_big") ?: o.string("picture_medium"),
            )
            EntryKind.Album -> SourceEntry(
                kind, id.toString(), o.string("title") ?: return null,
                (o["artist"] as? JsonObject)?.string("name").orEmpty(),
                o.string("cover_big") ?: o.string("cover_medium"),
            )
            EntryKind.Playlist -> SourceEntry(
                kind, id.toString(), o.string("title") ?: return null,
                listOfNotNull(o.long("nb_tracks")?.let { "$it tracks" }, (o["user"] as? JsonObject)?.string("name")).joinToString(" · "),
                o.string("picture_big") ?: o.string("picture_medium"),
            )
        }
    }

    override suspend fun entryTracks(kind: EntryKind, id: String): List<SearchHit> {
        val safe = id.filter { it.isDigit() }
        if (safe.isEmpty()) return emptyList()
        return when (kind) {
            EntryKind.Artist -> tracksOf(get("/artist/$safe/top?limit=50", "the artist's songs"))
            EntryKind.Playlist -> tracksOf(get("/playlist/$safe/tracks?limit=200", "the playlist"))
            EntryKind.Album -> {
                // an album's track rows carry no cover of their own, so they get the album's
                val cover = (json.parseToJsonElement(get("/album/$safe", "the album")) as? JsonObject)?.string("cover_big")
                tracksOf(get("/album/$safe/tracks?limit=200", "the album")).map { if (it.artworkUrl == null) it.copy(artworkUrl = cover) else it }
            }
        }
    }

    // ---- as a source: only the preview --------------------------------------------------------

    /** the 30-second preview Deezer's open API offers; Mochy asks for this only when no other source has the song */
    override suspend fun streamFor(id: String, picks: Map<String, String>): ResolveResult {
        val track = try {
            trackObject(id)
        } catch (e: ModuleUnavailable) {
            return ResolveResult.Unavailable(e.message ?: "Deezer didn't answer")
        } ?: return ResolveResult.NotFound("Deezer doesn't have this song")
        val preview = track.string("preview")?.takeIf { it.startsWith("https://") }
            ?: return ResolveResult.NotFound("Deezer has no preview of this song")
        return ResolveResult.Found(
            SourceStream(
                url = preview,
                format = StreamFormat.Progressive,
                sourceLabel = "Deezer",
                qualityLabel = "30 s preview",
                codec = "MP3",
                matched = MatchedTrack(
                    id = id,
                    title = track.string("title").orEmpty(),
                    artist = (track["artist"] as? JsonObject)?.string("name").orEmpty(),
                    album = (track["album"] as? JsonObject)?.string("title"),
                    durationMs = track.long("duration")?.times(1000),
                    confidence = 1f,
                ),
            ),
        )
    }

    // as a provider for songs from elsewhere, Deezer has nothing to give: no full audio
    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        ResolveResult.NotFound("Deezer's open API has only 30-second previews")

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        report(DiagnosticStep("Reach Deezer", null, "Asking for the chart…"))
        return try {
            get("/chart/0/tracks?limit=1", "the chart")
            report(DiagnosticStep("Reach Deezer", true, "Deezer answers. Its open API has only 30-second previews, so songs play from your other sources"))
            resolve(track, picks)
        } catch (e: ModuleUnavailable) {
            report(DiagnosticStep("Reach Deezer", false, e.message ?: "Deezer didn't answer"))
            ResolveResult.Unavailable(e.message ?: "Deezer didn't answer")
        }
    }

    // ---- reading deezer's answers -------------------------------------------------------------

    private fun trackObject(id: String): JsonObject? {
        val safe = id.filter { it.isDigit() }
        if (safe.isEmpty()) return null
        val o = json.parseToJsonElement(get("/track/$safe", "the song")) as? JsonObject ?: return null
        return o.takeIf { it["error"] == null }
    }

    private fun dataOf(body: String): List<JsonObject> {
        val data = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray }.getOrNull() ?: return emptyList()
        return data.mapNotNull { it as? JsonObject }
    }

    private fun tracksOf(body: String): List<SearchHit> = dataOf(body).mapNotNull { o ->
        val id = o.long("id") ?: return@mapNotNull null
        val title = o.string("title") ?: return@mapNotNull null
        // a row Deezer lists but can't open anywhere is left out
        if (o["readable"]?.let { (it as? JsonPrimitive)?.contentOrNull } == "false") return@mapNotNull null
        val album = o["album"] as? JsonObject
        SearchHit(
            id = id.toString(),
            title = title,
            artist = (o["artist"] as? JsonObject)?.string("name").orEmpty(),
            durationMs = o.long("duration")?.times(1000),
            artworkUrl = album?.string("cover_big") ?: album?.string("cover_medium"),
            pageUrl = o.string("link"),
        )
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull

    // ---- talking to deezer --------------------------------------------------------------------

    private fun get(path: String, what: String): String {
        val response = try {
            host.http(HttpRequest("$Api$path", headers = mapOf("accept" to "application/json")))
        } catch (e: Exception) {
            throw ModuleUnavailable("Deezer didn't answer for $what")
        }
        return when {
            response.ok -> response.text
            response.code == 404 -> throw ModuleUnavailable("Deezer has no $what (404)")
            response.code == 403 || response.code == 451 -> throw ModuleUnavailable("Deezer isn't open from this network for $what (${response.code})")
            else -> throw ModuleUnavailable("Deezer answered ${response.code} for $what")
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val Id = "deezer"
        private const val Api = "https://api.deezer.com"
    }
}
