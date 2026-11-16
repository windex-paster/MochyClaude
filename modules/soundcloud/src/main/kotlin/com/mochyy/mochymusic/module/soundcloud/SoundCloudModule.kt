package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.AccountState
import com.mochyy.mochymusic.module.BrowsableSourceModule
import com.mochyy.mochymusic.module.CatalogTrack
import com.mochyy.mochymusic.module.DiagnosticStep
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
class SoundCloudModule : BrowsableSourceModule, ModuleFields {

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
    }

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
        val body = apiGet { id ->
            "$Api/search/tracks?q=${enc(q)}&limit=$limit&client_id=$id"
        }
        return parseSearch(body)
    }

    internal fun parseSearch(body: String): List<SearchHit> {
        val items = runCatching { json.parseToJsonElement(body).jsonObject["collection"]?.jsonArray }.getOrNull()
            ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.let(::hitOf) }
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
        val trackJson = apiGet { cid -> "$Api/tracks/$id?client_id=$cid" }
        val track = json.parseToJsonElement(trackJson).jsonObject
        if (!playableInFull(track)) return null
        val authorization = track.str("track_authorization")
        val choice = pickTranscoding(track) ?: return null

        val resolved = apiGet { cid ->
            buildString {
                append(choice.url).append(if ('?' in choice.url) '&' else '?').append("client_id=").append(cid)
                if (authorization != null) append("&track_authorization=").append(enc(authorization))
            }
        }
        val url = json.parseToJsonElement(resolved).jsonObject.str("url") ?: return null

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

    internal class Transcoding(val url: String, val hls: Boolean, val codec: String, val label: String)

    /** a whole file when SoundCloud has one, else the pieces; MP3 before AAC before Opus, since every phone plays the first */
    internal fun pickTranscoding(track: JsonObject): Transcoding? {
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
        return all.minWithOrNull(
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
    private fun apiGet(urlFor: (clientId: String) -> String): String {
        var refreshed = false
        while (true) {
            val id = clientId(refresh = false)
            val response = try {
                host.http(HttpRequest(urlFor(id), headers = Headers))
            } catch (e: Exception) {
                throw ModuleUnavailable("SoundCloud didn't answer")
            }
            when {
                response.ok -> return response.text
                (response.code == 401 || response.code == 403) && !refreshed -> {
                    refreshed = true
                    clientId(refresh = true)
                }
                (response.code == 401 || response.code == 403) && manualId != null ->
                    throw ModuleUnavailable("SoundCloud turned down the client ID from the settings (${response.code})")
                response.code == 404 -> throw ModuleUnavailable("SoundCloud doesn't have it (404)")
                else -> throw ModuleUnavailable("SoundCloud answered ${response.code}")
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
            extractClientId(body.text)?.let { return KeyHunt(it) }
        }
        return KeyHunt(null, "not in any of its ${scripts.size} scripts ($failed didn't load)")
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
            "user-agent" to "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36",
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
