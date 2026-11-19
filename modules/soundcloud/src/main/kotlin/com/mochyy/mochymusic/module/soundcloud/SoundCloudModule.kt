package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.BrowsableSourceModule
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
import com.mochyy.mochymusic.module.EntryKind
import com.mochyy.mochymusic.module.SourceEntry
import com.mochyy.mochymusic.module.FieldKind
import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.MatchedTrack
import com.mochyy.mochymusic.module.ModuleField
import com.mochyy.mochymusic.module.ModuleFields
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleInfo
import com.mochyy.mochymusic.module.ModuleUnavailable
import com.mochyy.mochymusic.module.NO_ACCOUNT_LABEL
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.SearchHit
import com.mochyy.mochymusic.module.SourceStream
import com.mochyy.mochymusic.module.StreamFormat
import com.mochyy.mochymusic.module.TrackMatcher
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
import java.util.concurrent.ConcurrentHashMap

/**
 * SoundCloud, searched and played directly: its own results and ids, no account.
 *
 * SoundCloud has no public API for new apps, so this talks to the same api-v2 its website uses,
 * with the public client id the website's own scripts carry. That id is found when first needed
 * and found again when SoundCloud turns it down. Tracks SoundCloud only lets Go+ listeners hear in
 * full (it serves 30-second previews to everyone else) are left out rather than played cut short.
 */
class SoundCloudModule : BrowsableSourceModule, ModuleFields, com.mochyy.mochymusic.module.ModuleArt {

    override val info = ModuleInfo(
        id = Id,
        name = "SoundCloud",
        version = "1.0",
        description = "Search and play SoundCloud tracks, no account needed. Independent artists, remixes and mixes",
    )

    override val searchLabel = "SoundCloud"

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = ConcurrentHashMap<String, SearchHit>()

    // the tracks of the latest searches as SoundCloud described them, so a press on one needn't ask for it again
    private val seenTracks = ConcurrentHashMap<String, JsonObject>()

    // a key people pasted in the module's settings: used as it is, never looked for on the website
    @Volatile private var manualId: String? = null

    override val fields = listOf(
        ModuleField(
            id = FieldClientId,
            label = "Client ID (optional)",
            kind = FieldKind.Text,
            hint = "32 letters and digits",
            description = "Only needed if search says it couldn't get SoundCloud's key. Open soundcloud.com in a desktop browser, " +
                "open the network tab, and copy the client_id from any request to api-v2.soundcloud.com.",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        manualId = values[FieldClientId]?.trim()?.takeIf { ClientIdFormat.matches(it) }
    }

    override fun attach(host: ModuleHost) {
        this.host = host
        // the module's own icon, a picture of its own drawing, written where mochy can load it from
        iconPath = runCatching {
            val file = java.io.File(host.folder, "icon2.png")
            file.writeBytes(java.util.Base64.getDecoder().decode(IconPng))
            file.path
        }.getOrNull()
    }

    @Volatile private var iconPath: String? = null
    override val icon: String? get() = iconPath

    override fun accountState(): AccountState = AccountState.SignedIn(NO_ACCOUNT_LABEL)

    override suspend fun verifyAccount(): AccountState = try {
        clientId(refresh = true)
        AccountState.SignedIn("$NO_ACCOUNT_LABEL · SoundCloud reachable")
    } catch (e: ModuleUnavailable) {
        AccountState.Expired(e.message ?: "SoundCloud didn't answer")
    }

    override fun signOut() {
        matches.clear()
        host.storage.set(KeyClientId, null)
    }

    // ---- search -------------------------------------------------------------------------------

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val body = apiGet("the search") { id ->
            "$Api/search/tracks?q=${enc(q)}&limit=$limit&client_id=$id"
        }
        return parseSearch(body)
    }

    override suspend fun related(id: String, limit: Int): List<SearchHit> {
        val body = apiGet("the related songs") { cid ->
            "$Api/tracks/${enc(id)}/related?limit=$limit&client_id=$cid"
        }
        return parseSearch(body).filter { it.id != id }
    }

    override val entryKinds = listOf(EntryKind.Artist, EntryKind.Album, EntryKind.Playlist)

    override suspend fun searchEntries(query: String, kind: EntryKind, limit: Int): List<SourceEntry> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val path = when (kind) {
            EntryKind.Artist -> "users"
            EntryKind.Album -> "albums"
            EntryKind.Playlist -> "playlists_without_albums"
        }
        val body = apiGet("the search") { cid -> "$Api/search/$path?q=${enc(q)}&limit=$limit&client_id=$cid" }
        val items = runCatching { json.parseToJsonElement(body).jsonObject["collection"]?.jsonArray }.getOrNull() ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.let { o -> entryOf(kind, o) } }
    }

    private fun entryOf(kind: EntryKind, o: JsonObject): SourceEntry? {
        val id = o.long("id") ?: return null
        return if (kind == EntryKind.Artist) {
            val name = o.str("username") ?: return null
            val fans = o.long("followers_count")
            SourceEntry(kind, id.toString(), name, fans?.let { "$it followers" }.orEmpty(), o.str("avatar_url")?.let(::biggerArt))
        } else {
            val title = o.str("title") ?: return null
            val owner = o.obj("user")?.str("username").orEmpty()
            val count = o.long("track_count")
            SourceEntry(
                kind, id.toString(), title,
                listOfNotNull(owner.takeIf { it.isNotEmpty() }, count?.let { "$it tracks" }).joinToString(" • "),
                (o.str("artwork_url") ?: o.obj("user")?.str("avatar_url"))?.let(::biggerArt),
            )
        }
    }

    override suspend fun entryTracks(kind: EntryKind, id: String): List<SearchHit> {
        if (kind == EntryKind.Artist) {
            val body = apiGet("the artist's tracks") { cid -> "$Api/users/${enc(id)}/tracks?limit=50&client_id=$cid" }
            return parseSearch(body)
        }
        val body = apiGet("the playlist") { cid -> "$Api/playlists/${enc(id)}?client_id=$cid" }
        val tracks = json.parseToJsonElement(body).jsonObject["tracks"] as? JsonArray ?: return emptyList()
        val rows = tracks.mapNotNull { it as? JsonObject }
        // SoundCloud sends only the first few tracks whole and the rest as bare ids: ask for those in one go
        val bare = rows.filter { it.str("title") == null }.mapNotNull { it.long("id") }.take(100)
        val filled = HashMap<Long, JsonObject>()
        if (bare.isNotEmpty()) {
            runCatching {
                val more = apiGet("the playlist's tracks") { cid -> "$Api/tracks?ids=${bare.joinToString(",")}&client_id=$cid" }
                (json.parseToJsonElement(more) as? JsonArray).orEmpty().forEach { e ->
                    (e as? JsonObject)?.let { t -> t.long("id")?.let { filled[it] = t } }
                }
            }
        }
        if (seenTracks.size > 300) seenTracks.clear()
        return rows.mapNotNull { row ->
            val track = if (row.str("title") != null) row else row.long("id")?.let { filled[it] } ?: return@mapNotNull null
            hitOf(track)?.also { seenTracks[it.id] = track }
        }
    }

    internal fun parseSearch(body: String): List<SearchHit> {
        val items = runCatching { json.parseToJsonElement(body).jsonObject["collection"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        if (seenTracks.size > 300) seenTracks.clear()
        return items.mapNotNull { item ->
            val track = item as? JsonObject ?: return@mapNotNull null
            hitOf(track)?.also { seenTracks[it.id] = track }
        }
    }

    /** null for what can't be played whole: previews, blocked and region-locked tracks, or a row that isn't a track */
    private fun hitOf(track: JsonObject): SearchHit? {
        if (track.str("kind") != null && track.str("kind") != "track") return null
        val id = track.long("id") ?: return null
        val title = track.str("title") ?: return null
        if (!playableInFull(track)) return null
        val artist = track.obj("publisher_metadata")?.str("artist")
            ?: track.obj("user")?.str("username")
            ?: ""
        return SearchHit(
            id = id.toString(),
            title = title,
            artist = artist,
            durationMs = track.long("full_duration") ?: track.long("duration"),
            artworkUrl = (track.str("artwork_url") ?: track.obj("user")?.str("avatar_url"))?.let(::biggerArt),
            pageUrl = track.str("permalink_url"),
        )
    }

    private fun playableInFull(track: JsonObject): Boolean {
        if (track.bool("streamable") == false) return false
        return when (track.str("policy")) {
            "BLOCK", "SNIP" -> false
            else -> true
        }
    }

    // SoundCloud sends "-large" (100 px); the same file name says "-t500x500" for a real cover
    private fun biggerArt(url: String) = url.replace("-large.", "-t500x500.")

    // ---- stream -------------------------------------------------------------------------------

    override suspend fun streamFor(id: String, picks: Map<String, String>): ResolveResult {
        val stream = try {
            streamOrNull(id)
        } catch (e: ModuleUnavailable) {
            return ResolveResult.Unavailable(e.message ?: "SoundCloud didn't answer")
        } catch (e: kotlinx.serialization.SerializationException) {
            return ResolveResult.Unavailable("SoundCloud sent something unexpected")
        } catch (e: IllegalArgumentException) {
            return ResolveResult.Unavailable("SoundCloud sent something unexpected")
        }
        return if (stream == null) ResolveResult.NotFound("SoundCloud can't play this track in full") else ResolveResult.Found(stream)
    }

    private fun streamOrNull(id: String): SourceStream? {
        // the track as the search described it first; if its stream links are refused (they expire), once more as SoundCloud has it now
        var problem: ModuleUnavailable? = null
        for (fresh in listOf(false, true)) {
            val track = trackOf(id, fresh) ?: continue
            if (!playableInFull(track)) return null
            try {
                return streamOf(id, track)
            } catch (e: ModuleUnavailable) {
                problem = e
            }
        }
        throw problem ?: ModuleUnavailable("SoundCloud sent no track data")
    }

    private fun trackOf(id: String, fresh: Boolean): JsonObject? {
        if (!fresh) return seenTracks[id]
        val body = apiGet("the track's details") { cid -> "$Api/tracks/$id?client_id=$cid" }
        return json.parseToJsonElement(body).jsonObject.also { seenTracks[id] = it }
    }

    /** the first of the track's files that SoundCloud gives a link to, best first */
    private fun streamOf(id: String, track: JsonObject): SourceStream? {
        val authorization = track.str("track_authorization")
        val choices = transcodings(track)
        if (choices.isEmpty()) return null
        var problem: ModuleUnavailable? = null
        for (choice in choices) {
            val resolved = try {
                apiGet("the ${choice.label} stream link") { cid ->
                    buildString {
                        append(choice.url).append(if ('?' in choice.url) '&' else '?').append("client_id=").append(cid)
                        if (authorization != null) append("&track_authorization=").append(enc(authorization))
                    }
                }
            } catch (e: ModuleUnavailable) {
                problem = e
                continue
            }
            val url = json.parseToJsonElement(resolved).jsonObject.str("url") ?: continue
            val hit = hitOf(track)
            return SourceStream(
                url = url,
                format = if (choice.hls) StreamFormat.Hls else StreamFormat.Progressive,
                headers = emptyMap(),
                sourceLabel = "SoundCloud",
                qualityLabel = choice.label,
                matched = MatchedTrack(
                    id = id,
                    title = hit?.title ?: track.str("title").orEmpty(),
                    artist = hit?.artist.orEmpty(),
                    album = null,
                    durationMs = hit?.durationMs,
                    confidence = 1f,
                ),
                codec = choice.codec,
            )
        }
        throw problem ?: ModuleUnavailable("SoundCloud gave no stream link")
    }

    internal class Transcoding(val url: String, val hls: Boolean, val codec: String, val label: String)

    /** a whole file when SoundCloud has one, else the pieces; MP3 before AAC before Opus, since every phone plays the first */
    internal fun pickTranscoding(track: JsonObject): Transcoding? = transcodings(track).firstOrNull()

    /** every plain file of the track, best first */
    internal fun transcodings(track: JsonObject): List<Transcoding> {
        val all = track.obj("media")?.get("transcodings")?.let { it as? JsonArray }.orEmpty()
            .mapNotNull { it as? JsonObject }
            .mapNotNull { t ->
                val url = t.str("url") ?: return@mapNotNull null
                val format = t.obj("format") ?: return@mapNotNull null
                val protocol = format.str("protocol") ?: return@mapNotNull null
                val mime = format.str("mime_type").orEmpty()
                // "encrypted-hls" is DRM'd, "hls" and "progressive" are plain
                if (protocol != "progressive" && protocol != "hls") return@mapNotNull null
                val codec = when {
                    "mpeg" in mime -> "MP3"
                    "mp4a" in mime -> "AAC"
                    "opus" in mime -> "Opus"
                    else -> return@mapNotNull null
                }
                Transcoding(url, protocol == "hls", codec, if (protocol == "hls") "$codec · stream" else codec)
            }
        return all.sortedWith(
            compareBy<Transcoding>({ it.hls }, { listOf("MP3", "AAC", "Opus").indexOf(it.codec) }),
        )
    }

    // ---- as a playback provider: the song Mochy wants, found by name --------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val key = "${track.title}|${track.artists.joinToString(",")}"
        report(DiagnosticStep("Find the song", null, "Searching SoundCloud…"))
        val hit = matches[key] ?: run {
            val query = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
            val hits = try {
                search(query, 15)
            } catch (e: ModuleUnavailable) {
                report(DiagnosticStep("Find the song", false, e.message ?: "SoundCloud didn't answer"))
                return ResolveResult.Unavailable(e.message ?: "SoundCloud didn't answer")
            }
            val best = TrackMatcher.ranked(
                track,
                hits.map { CatalogTrack(it.id, it.title, it.artist, durationMs = it.durationMs) },
            ).firstOrNull()?.let { scored -> hits.first { it.id == scored.track.id } }
            if (best == null) {
                report(DiagnosticStep("Find the song", false, "No matching recording of “${track.title}”"))
                return ResolveResult.NotFound("No matching recording on SoundCloud")
            }
            matches[key] = best
            best
        }
        report(DiagnosticStep("Find the song", true, "${hit.title} · ${hit.artist}"))
        report(DiagnosticStep("Stream", null, "Opening the stream…"))
        val result = streamFor(hit.id, emptyMap())
        report(
            when (result) {
                is ResolveResult.Found -> DiagnosticStep("Stream", true, result.stream.qualityLabel)
                is ResolveResult.NotFound -> DiagnosticStep("Stream", false, result.reason)
                is ResolveResult.Unavailable -> DiagnosticStep("Stream", false, result.reason)
            },
        )
        return result
    }

    // ---- talking to soundcloud ----------------------------------------------------------------

    /** one GET against api-v2; a turned-down client id is found again once, then it's SoundCloud that's out */
    private fun apiGet(what: String, urlFor: (clientId: String) -> String): String {
        var refreshed = false
        while (true) {
            val id = clientId(refresh = false)
            val response = try {
                host.http(HttpRequest(urlFor(id), headers = Headers))
            } catch (e: Exception) {
                throw ModuleUnavailable("SoundCloud didn't answer for $what")
            }
            when {
                response.ok -> return response.text
                (response.code == 401 || response.code == 403) && !refreshed -> {
                    refreshed = true
                    clientId(refresh = true)
                }
                (response.code == 401 || response.code == 403) && manualId != null ->
                    throw ModuleUnavailable("SoundCloud turned down the client ID from the settings ($what, ${response.code})")
                response.code == 404 -> throw ModuleUnavailable("SoundCloud has no $what (404)")
                else -> throw ModuleUnavailable("SoundCloud answered ${response.code} for $what")
            }
        }
    }

    /** the key from the settings if there is one, else the public client id the website's own scripts carry, kept for a day since SoundCloud rotates it now and then */
    private fun clientId(refresh: Boolean): String {
        manualId?.let { return it }
        if (!refresh) {
            val saved = host.storage.get(KeyClientId)
            val at = host.storage.get(KeyClientIdAt)?.toLongOrNull() ?: 0L
            if (saved != null && System.currentTimeMillis() - at < ClientIdLifeMs) return saved
        }
        val hunt = findClientId()
        val found = hunt.id ?: run {
            // an old id may still work: better than none
            host.storage.get(KeyClientId)?.takeIf { !refresh }?.let { return it }
            throw ModuleUnavailable("Couldn't get SoundCloud's key: ${hunt.problem}. A client ID can be pasted in this module's settings")
        }
        host.storage.set(KeyClientId, found)
        host.storage.set(KeyClientIdAt, System.currentTimeMillis().toString())
        return found
    }

    /** what looking for the key came to: the key, or which step it stopped at */
    private class KeyHunt(val id: String?, val problem: String = "")

    private fun findClientId(): KeyHunt {
        val page = try {
            host.http(HttpRequest(Site, headers = PageHeaders))
        } catch (e: Exception) {
            return KeyHunt(null, "the website didn't answer (${e.javaClass.simpleName})")
        }
        if (!page.ok) return KeyHunt(null, "the website answered ${page.code}")
        val html = page.text
        // some builds put it in the page itself
        extractClientId(html)?.let { return KeyHunt(it) }
        val scripts = extractScriptUrls(html)
        if (scripts.isEmpty()) return KeyHunt(null, "no scripts found on its page (${html.length} characters came back)")
        var failed = 0
        // where the words "client_id" first show up, for a report of how the site writes it now
        var nearest: String? = nearClientId(html)
        // the id sits in one of the last scripts the page loads
        for (script in scripts.asReversed()) {
            val body = try {
                host.http(HttpRequest(script, headers = PageHeaders))
            } catch (e: Exception) {
                failed++
                continue
            }
            if (!body.ok) {
                failed++
                continue
            }
            val text = body.text
            extractClientId(text)?.let { return KeyHunt(it) }
            if (nearest == null) nearest = nearClientId(text)
        }
        val seen = nearest?.let { "; nearest text: «$it»" } ?: "; the words client_id aren't in any of them"
        return KeyHunt(null, "not in any of its ${scripts.size} scripts ($failed didn't load)$seen")
    }

    /** a few words around the first "client_id" in [text], on one line; null when it isn't there */
    private fun nearClientId(text: String): String? {
        val at = text.indexOf("client_id")
        if (at < 0) return null
        return text.substring((at - 20).coerceAtLeast(0), (at + 70).coerceAtMost(text.length)).replace(Regex("\\s+"), " ")
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    companion object {
        const val Id = "soundcloud"
        private const val Api = "https://api-v2.soundcloud.com"
        private const val Site = "https://soundcloud.com"
        private const val KeyClientId = "client_id"
        private const val KeyClientIdAt = "client_id_at"
        private const val ClientIdLifeMs = 24L * 60 * 60 * 1000
        private const val FieldClientId = "client_id"
        private val ClientIdFormat = Regex("[A-Za-z0-9]{32}")
        private val Headers = mapOf(
            "accept" to "application/json",
            // a desktop browser: asked as a phone, the website sends its mobile scripts, which don't carry the key
            "user-agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        )

        // the website's own page and scripts, asked for as a browser would
        private val PageHeaders = mapOf(
            "accept" to "text/html,application/xhtml+xml,application/javascript,*/*;q=0.8",
            "accept-language" to "en-US,en;q=0.9",
            "user-agent" to Headers.getValue("user-agent"),
        )

        // any script tag whatever the order of its attributes or its quotes
        private val ScriptRegex = Regex("""<script[^>]*?\ssrc\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        // client_id:"…", client_id="…", "client_id":"…" and the like
        private val ClientIdRegex = Regex("""["']?client_id["']?\s*[:=]\s*["']([A-Za-z0-9]{32})["']""")

        internal fun extractScriptUrls(html: String): List<String> =
            ScriptRegex.findAll(html).map { it.groupValues[1] }
                .map { if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) Site + it else it }
                .filter { it.startsWith("https://") && "sndcdn.com" in it && ".js" in it }
                .distinct().toList()

        internal fun extractClientId(script: String): String? =
            ClientIdRegex.find(script)?.groupValues?.get(1)

        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
        private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
    }
}


private const val IconPng =
    "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"
