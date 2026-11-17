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
            val file = java.io.File(host.folder, "icon.png")
            if (!file.exists()) file.writeBytes(java.util.Base64.getDecoder().decode(IconPng))
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
        "iVBORw0KGgoAAAANSUhEUgAAAMAAAADACAYAAABS3GwHAAAaFUlEQVR42u2df5BcZZnvP897TvdMJsmEBEyMGAgghE0wEIFgCVwr" +
        "F0MQZWXB5GZlWUAMbq3lguv1Kj/iMALqrrtyXb3WbrjskrKw5jLCUuvdvUREYUFLghKIgHGzgAiYH5KQX0xmps953/vH+57uzmSS" +
        "zGQmM+d0P9+qrp7pmUz69Pk+7/P7eYRxgAPhPgyALCPd52dfYSoRpyKcjjCblAUYpmGZhHAqDkWeIYBjA4Y9WLYTsQ7Hb3A8S8oG" +
        "uZE397nf9xEBsAwrjP3dlTElvkPoxtST3v0DbexhPimLcZwHzAfeThkwgPUSgwMS5VchEAdmSd097AdgM7Ae4QkiHmYS6+WT9Owj" +
        "DEuxImMnCGMiAK4DwzwkI777Bi30cRERl2K5AMMsWoAUqIRnsDgcEt6j/0qUXQWAv2+u7msvChFQAiKgD7C8iuERUh6khYfkevqq" +
        "gvA8TjqxhRaAgSe++yrvJOZahOVEnIoJJ0OCC7T3H5QSvXEFo6bTI2KEcnglZQOOLhLuli/w2lhpBDliNn4HkXR6o8V9jdNw/CXC" +
        "5ZRppwJUsAg2KEmj7GhK2KDpDSUMJaCfXTjuR/i6fI7nggUR00l6JHwEORLmTqa63O0cS5nbEK6gTJlewJJWVaJCUS8MDochohXo" +
        "px/HvfSzUm7h9YHcyqUAuA5i6SRx11HiZD6P8GlKTGcv4EgRzFg73opCGkoWIWICUGErjm+ykb+SVVQyjuVKAFxHCGl2Yt2XOYeY" +
        "b9DKOewFLElwe5T4imFa0qQYYiYAvTxJwvVyE0/W823cBcDdR1Tn5HZguIWImL5AfHVoFSN3nFNaiElJsNwuX6BzIPfGRQCqJs/t" +
        "zKTEP9HKEnqq5k6kd08xioLgOdUG9LKGCtfILWwaqUkko0D+hbTwIDEz2UsSiK+nvuJI6YOUCcQkbKKPS+UW1o5ECGSE5P84rfxP" +
        "LJNJSPA5QIXiSCMhJsawm15ukFv4x8MVgmELgPsxsSwicXewglZWhcytDREehWKsdIElCrmDXq6Tm7kr4+Zw/owZ9slfT/5+UqyS" +
        "X8F4FN0ZLJZ+UlpZ5e5ghSwicR3Ds0LMsM2e2wL5+0ixIYurFZqK8QiSgsFi6AtCcBsrpHN4QiDDJP9VtHFPlfwa4lTkxTU2WFqI" +
        "6OFqWcnqofoEMtTSBnc7CynxYxytIfKqZo+CXJVS+PhjLxUWhejQIUsn5JAZ3k4cdzAdw3oM00MRm5JfkU/HuITBshXLfG5mKx3I" +
        "wYTg4ESeF6q6Hd+hxHQqJFWbXx/6yNsDDBUSSkzH8R1c4PDhOMGug1iWkXIbX6SNxezVOL+CYvSj7SWhjcXcxhdlGenBnGI5QCOL" +
        "EcG6r7AA4UnS4G2r06soklMcYXGcIzeyLuP0IQXAOYRlGOYSUeJnlFlAn9b2KApYO9RCRD/rqPBeXiDlvv27y/Y3gbox0k1KxOdo" +
        "YwG9oapTbUx9FMsfiOgloY0FRHxOuknp3p/vsl/U51YctzKDMs8jHEWizeiKAptCMQ7HDvqZx61s4dZ9o0JmYNRHBEfEV2lhGknd" +
        "VAaFgsKVSwgJjhamEfFVEdzAqJDs5/jewhzaeBZLCRumu6gIKIpbLuEwOAwVejhdbufX9Q5xXGf7e5qXuIkSLfRUe3jRWh9FofVA" +
        "iqWFFkrcBFxV5XqmAZzzpo+7kRm08SLQhtWzX9FQYVGAHno4Sb7ClozzJov8ANDKJ2llYihxVvIrGscXsFhamUgrn6znvCAh9v8Z" +
        "WjmK54g5kaQ6sEqhoGGK5WIMCS+xg9O4k14RnHFfJBZwtHMBLUp+RcPCkGBp4UTauUDAuS8Sm7offzSUuVn9rBQNqwV8ROijVevI" +
        "EcyfKbxIzEwqOIza/4oGHb5YQkjYxE5O4k56YwHn2jmdmBkkodZfw56Kxk2MWWJm0M7pAj8z4QdLKFdXGSgUja0HyhiEJQAmzFk8" +
        "lxS/hEKhoKFzAhJaes91HZgYaMcxnyQkjRWKxhYAE7g+H2g3wLswWvimaLICOcM04F0GWEhMCauur6KJokExJWChQTg+1HyqACia" +
        "RQu4wPnjY1LOUAdY0ZSOcMoZBjhaPxFFk+JoA0wKxo9qAEUzGUEAkwyGOVS09l/RZJGgCmCYE1dzv+oCK2i+LcWa+FLQ3DXSCgXN" +
        "PEdRTR+FagCFQgVAoVABUCjUB1AoVAMoFM2gARRjAxEQc4jVbEEdOwtOVbMKAAUdyCpBuYrxLzgLiYMkpTp4xtXJQfa1Cc8xEGUC" +
        "I/7fY/f9Nwr1AXJ5yhsTCG+hP9SZlIHJx8D0uSARzJwPrVPr6k8Eet+ETevBpbD1Bdj9BvSntX8f1/1t1Q6qAfJFfOPJn6TQl3rC" +
        "HnUcnHQBnHA+vH0eHHMKtB41tL/XuwPe+A/Y/Dy8/Di8+Ajs+K0XiBYgjrwQOKuf/UhvnbtJdcCI7fq+FBJg2gyY+4dw5tUw8wwo" +
        "tw0wj+y+z4MJUv1zhv4e2PQM/OIeeOFfYPsWf3S1RKoRRiwAN6oAHBZMBJUU+oHjToP3XQ/zLoFJM2q/Y1Nv5kgw8GWIBrwLjoIL" +
        "Rr+p20+4Zws8/3346Tfgt895bVOKwv+lUAEYi1MfgR4LxxwLS26DBVdAVK6RXqT2e6PlWbvwyIQh7Yd198KalfDG69Bmar+nUAE4" +
        "oqe+Bc77NHzgFpg4PRA/8Q6uHOEwjXPeUTbBfXtrK/zwdnjimz6KpNpABeCIkb8nhWkz4b/9E5y8pEZ8E41DfNJ5omeCsHEN/J9r" +
        "YPsmaFMhGLoAfEEF4NDkj6EngXlLPPknzxy7E384GmH3Ji8Ez6+Btti/R4WWQow4xLkzgfNXwCceCuQPhJMcZKVE/HuxqX9vn3jI" +
        "v9edyf7RJIUKwPBCnDFULFzaAZetCiFHu29UJk8mWvb+Llvl33PF+msQTR+jmeDhCkAEuxJYfiecd0M49U2+axGy0gubwgdu9Ym3" +
        "rs9AewxOzSHVAMOx+d9K4L+u8ORPK7W6nALM/ECMf8/n3eCv4a2k5iwrVAAOiijyhDn7o96UyJO9fzh+wWWr/LW8lfhrU6gAHNSE" +
        "6E1h5olwebD5q0mtIvowoZL08lX+mnpTdYz3EwAXihKb/YH4BJcpwRXfhQlTfYixyIQR469hwlR/TaYUlmCJ3u/w0OOgnix7LSy5" +
        "GWadU5fgaoAEnk38NS252V+jagE1gfb9FEJF56w5sOgmn1iSBrKXJfLXtOgmf419WURLoZ8CdSrx0r+DqBQ6rxoodi7B5IlK/hqr" +
        "Zp9C8wAmgr0pzF0EJ18YygqixrxOl/prPHURvPBjmKA1Q6oBnPPh/Q+sbPwp2dm1fWClv2YtnW5yARDj+3aPm+dbF+vr7RtV2znn" +
        "r/W4ef7am9whbu4wKMZ3dJ31cZ84ck1gDmSVo2d9HBLjG3kkDo/IfyZONAzaFMVuSQJTp8AZf1yLljRDjRPOX3OLg+29sCfxj57U" +
        "a4VME45Ln4NOhRg786eSwikLoH1myPqaJmnpdP6al98Nv38FYuNbLF9dCzteg22vwJ4ez/1yNoWiMZvv42ZeFEUKzLm4Nqmhaezh" +
        "cKqfdc3+P7Ip7HwdNv4IfvnP8OKjsGtXXfO9bahIgbjPNmsgVMBG8Nm1cOwCf2ObLTmUTa3YZ77RgM9gx2vw5N3wxLdh21Zow39O" +
        "1moUqNBmQOqgfSocfWKdadCEORAT1x5SN8rRJv75qHfCkg74/C/hwyvBlaDXNkxlqWla+z8B3nYKtEyuVX0qav0EmUA454Vh0nS4" +
        "+Etw/eMw6z2wO4Uo1jBoMR/iBWDWOeEm64jBQ/YWZIJw3DnwF4/C+R+HHWEwQIGrS00z74ilZbIS/HCabFomwx/fDR+8wZeRuIL2" +
        "TDRvJtj5+Ne04w8xr19xwGyyTeHSO+FPVkGfBSIVgELV/xjgmJOV0IetDSLfd/zeFXDhDbA78dWmaDVocQrDkn4l80gQxV4I/vBO" +
        "eGsHPHYPTI4gTVUDFCcrqhhRxMjEPojw0f8FJ8z3PkGB8ikqAIpRaLZxUGqDK1d7M8gWxyk2Td0FllSUwKPZd/yOM+BDN/uiOjGF" +
        "qJgwTRP3R8Iw28hPR0gjePXnNH4XzBj3HV9wE8w6uWYKaR5gvGzTLM0fspmJ8zelJ4WePv/cu0uJeyT6jj98R62vQKtBx3oYVFDH" +
        "e1Nf7RkBkyZAeSK8fa4XDIn8kKhsh5eOCRndAb2nL4V3/TVs/Hnu+44bIwyaxaUrCfQmfl3QKefAqRfC7PfBjD+A1naYMOXgy+kU" +
        "o9Rj7eDcT8GGa3I/gULc9QUXARP59aR7gekz4KwrYeGVcOz8QW6OHaTwSyNBoy4AALYCXz0NfrcRyvmtt4oLv5R6TwrtU+Ciz8D5" +
        "fwaTZ+y7OYW6hXV60o/NfbGJ7zV+90fgN38DLfkVAFPo2POuFM5ZBv9jLVzc4cmf1bFnxVsmqi2yVowtrc6+CtrK6gOM+hjDigUp" +
        "wcf+Ghbd4F9Pk1rkRzH+9wgHM+bAjBPhtQ1eC+Swiywu3gxPCxOnwp9/H044t7aXN1Lik7d2S1OCP/ggvBQEAKsm0IjI32+hbSr8" +
        "xRpP/rRSM3EU+Wy8P+m/+FB0TidKmEKULIhABWidCtevgVlnB0erpDzLe53V8WfBxAnBDxAthRj+9DbABdPnT+/y5E8rausXoe8a" +
        "B5PeBkfP9gdYDlsnTWE2tF98A5x+uSe/nvwUJicQtcC02b4HW0RNoMNaXHHcHPjI39QW1ikKIgDB6T3uzNpqJg2DDrNx3QJL/y6U" +
        "3KYazy/iOPb60minGmAYiyssnLbI1/TYBl1c0QyO8LFn5DYSZHLfuL5kpRKp6Jh4dG4XcpjcRhD6LMyeByc1weKKRodN0FKIw0l6" +
        "LbwmTB5IGjvTO7BWpuGEXfYPbWspxEGQpDCxDKd9uPHr9Z3dn/ANN6rdaUfYsE7/XguzToBjTgjZCtO45BcD6x+An672r73vKph/" +
        "2dCFwLn8R8ZyHLrOoQkUBte+43RfU96o0Z9sGffa1XDX1TVv7Bf/AivugYVXHXxht7O1XgcXbIu8aY3M6X316VoizKoTPITFFfhS" +
        "Wgq6ynNI79lA0gv/1gElgUll/yiJfy3pPfDtca7W4/DWm6EX2uT3s+rdFawgzQQPY27nCcUbXpXt0cpO5QN1QWW/098De3dC7HyJ" +
        "R1rxX+/d6X9WPd0Hmk0Cv30KvnYufOkU//zbp8Lv5+iIze7dtldyO384vwLwtpML2Aw+zFO5+jsDq18P0MGWkX/bS/Cti+A/fgq9" +
        "b/jnb13kX8+TEGTvZfOvcpwIy2sVaJEG147oVHZDj5pkdv+zD8C27dBe9t+3l/33zz7gv8+DAGQHQt8e+P2LXgCs1WrQ4fZTFIb8" +
        "Y3kq9+0GI7WNjdb67/t2588P2roRdmyHSDQT3Cihs3E/lSUaxDdw+Vr0nV3viz/xs5pyulTP5HaB3avPFCsKNKansiP3CadsJOWv" +
        "HvYsy+l9zGcphAN2bqJ4w2FzfiqPtf2/dye8+ASU6ux/1QBD6SICXnumbsRGkdP9rjknQuDguX+F7Tv9hnltih+GAMTApudDLNwU" +
        "MxlGs/cDCzx1b9AG6H6AIT+shdjAltfh9V8eYKanIr+nf4iKbd4Azz0KLQKp1f0AhzUA6+nvFbccgibugxSBH/4t7O6BOMr1/TO5" +
        "tSFbgLX3Qv9bPoSWdyE4UMlGM/UwZzmRXZvh6QdhguR6Lmh+BcA5KEewaRM8fX9+spsHQ1rZvxpTjH+92aI/D94Iv38DSvn333I8" +
        "GCtEg/7ty74jTCTfH+a02ZDY2gYaE/nvp80ewzS5jPMs0AheXQePf9cvKUms7ggbkTPVGsHLv4bH/yFEg2xO4//WD+2adzZs64P+" +
        "1D/PO9u/7uzo5gNsMri2Ga/e2+xgSiuw+lqo9AdmOV2TOmKbssXAAyth15baDqrc2f4CE46CT62Bj/x3mHOBf/7UGv/6qG2iCX9j" +
        "1pk+spJNxZYQaZl15vhoAhtG0/+/O+BX66AtzuUodAo3GMs5b0fueBPuvhI+s8Z/sJHkq1ouM88mTIU/+towWxZl6KZMdgDM+xBc" +
        "uAJ+cJfPmST47+d9aPAe4yPq+4QhxRt+CN/rhEmRf60ggbv8V5ylKUyM4amH4ftfgks68jkftNoEk9bMNYkOQf7s92XA30kPbD6I" +
        "gMSwfBXMuwReeRqOfw+8+5Kxzzxn0zpe+Tl8a3k4mGyhkt/FKLlME5gcwf23wlHvgPNX5FcIJD70JIusPLqlHY45ETaug/YW/7Nd" +
        "fTDrRP+zQRvjpUb0d18ygPhjqBlt6snf8yZ8exns3gYTTGFMnwLuCLN+2+Bd18Hjd3ny27TYSTITw7K/hylHw5t9/jHlaP/aIcvB" +
        "Q4w9TcZ+9n62jmrrRvjbJbD5ZWiLCkd+vyb1EwVSWCJ++3ifhSvvhMU31DlhcUHj5gLbX4anH/CvvecymHZCPsedZCaeieE3T8HX" +
        "LoLd2z3507SQZ1CxBKA+6rLHwvnL4E+/7WdPZnX4RRuhMpiZk7fBWM7VTB6Af//fcO9noX8XtBSX/MUUgAxRDLsSOO4UuKwTFi6v" +
        "O6VssVajOlsL74rJCfkdWOdNz0y77vgddH0WnujypSqxKaTZU3wByN5xFEFv6ucIzV8MF38O5i3el1hZdWKjj1gctTZGt39L6q4t" +
        "8KO/hx99G7ZthYmhxCHnpc5DE4BrC96xIcbfhB7rSydOfh+cfzXM/yBMfaeSeiRRnpeehF/8M/z0O7BlC0zAN7fYtGEus/gCMDBJ" +
        "1OsgBaZOhhMXwgnvhePPhMlHw8y5uW3OJg+h5t+9AL9/CV5ZD7/6Abz6AvQDrUA5Ln7UraEFYOBo8ST1Ny8J2Q4DTJlS216Irlra" +
        "J3/gLOzc6T8vB5SBstRWUzVoT0bccC2rWUTCiE/MVDO0DvbsVL4fDGXjO7gkTLewNtfLLZonEzySmHU9Ij31Dx2Naq5LjptuXIdC" +
        "UcxSCIVCfQCFQjWAQqECoFCMmgCoGaSgCdMfQIzl10TMIcUhmh1SNAX5HRFCyq8NsCfQXvWAonlEwHN+jwG26eehaFJsixGeQbgw" +
        "KAaFonk0gPCMAV4JU7LU/lc0D/39Yf+KIWUtKRWMCoCCZol9CikVUtYaDP9JyvYQAVIjSNEM57+Qsh3Df8bMZhcvs56IxaRYfF+V" +
        "QtGosEREpKxnNruMdGKBn/hNfqoBFE2QA/Dp359IJ9aEGUtrqGARLY1QNDgEE7i+BiB2IPTwLC1sIWYmqfYLKhr4/I8wVNhEH8/6" +
        "dR4dRNLNXuAHRDgcqX5Oigalf0qEA34g3eylg8jwaFU1fC/kAtQMUtCwAVAfA/LbFx8FI4+ROIfQyyP08xIxBofuJVU02ulviTH0" +
        "8xK9POIcIo+R+NO+GyPd7MWxuhoNyvluJ33oY5gPF7i9WrrZS7e3dKRus6tzy5lBiReBthAQVWdY0UjVnz1UOEm62JJxPpMC55YS" +
        "SRdbsNxPCVEzSNFQ5k8JwXK/dLHFLSWSUPVQc3jnVpNgXyahr1oaoakxRbG7vrzbm9AHfHkA1/c1cdxSIukmdR/jHlq5il5SREsj" +
        "FAUPfbYS0ctq+S5XZxwfXAB8mMhxNTNIeR7hKCyivoCisPT3jN5BxDzuYQviBz8y2FQIAcsyjKxmM46vU8JoYkxR6NPfc/jrsprN" +
        "LMPUk3/QKI8DYSkGiCjxM0osoEKqVaKKgiGlRESFdVR4L5DSjZUBJf9mkN2Djrk46aYfy7WkVLRpXlHIsGdKBcu10k0/c3EyCIcH" +
        "LXuQTqzrIJYu1pFwB2UiNYUUhTJ9ykQk3CFdrHMdxKHsf7Blswf5O0uJ6MbyMdZQYjH9JEiTTZRWFI38CWViKjzMd1nCUkx91Gd4" +
        "AkBIHi9nOob1GKaTaN+AIuf1PpatWObTxdawWNce1mxQAUsHIl1swXEJjp4gEpolVuSP/J6bPTgukS620HFw8g+51se9n1geI3HL" +
        "uYoy95CQhtIizQ8o8uL0WmIi+rlaulidcZbRmA4tj5EEp3g1/VxHRATYaqGExocU47Ur2nPQN7r3c510sdp1DI38w672DN504paz" +
        "ghKrSEixiPoEinE0exwxERWuky7uyjjKkFuEh/t/7i8EYNUxVowL+Q0xHC75D2tBhnRWzaG7qHAtht2hiyzRu6IYs1BnjMGwmwrX" +
        "Hi75R9TwUnWMl7KQmAeJmEmFBF8yoc6x4khZ/iklYlI2kXCpdLN2qA7vqK5IksdI3PuJpZu19HImCWsoEwfya9ZYwajX9oBQJiZh" +
        "Db2cOVLyj0rLY319tVtOB8ItGGIS1QaKUTz1Y2IsCY7bpYvOgdxjvJbkSTepA+M6MNJFJ47zcDxJmRhBgm+ggVLF4QwxTJBw6jue" +
        "xHGedNHpOjCOg5c4jJkGGNQvOJMSJ/N54NPETKe/qsI0eaYYyonvhzSXgYStwDfZyF/JL6iM1OQ5ogIQwqTZwF3cUo7FcBvCFcSU" +
        "qYRKPae5A8UgYU3BIUSUgIR+HPdiWSndvD6QW7kVgGpTzfuJMkl1yzgN4S+ByynRTgqhqM4GraDCQJOOKvcVBYYYQwRU2AXcj+Pr" +
        "ch/PZZYFj5HKETCl5QjrMqkvR3VX8E5SrsWxnIhTEQiJNFf18tVMagbzxgERJhTX+7u/AaGLiLvlXl6rL8eXI+hDjgnRXAeGF5Cq" +
        "IFxEC1O4CMelwAUYZhGHjyatfkReJdZ2l4nuMS6Q+0p1tJqrmrwmxAVN9eB7FXgE4UF28pA8RF+V+HNxo23ujJsAHEgjALgP00Yb" +
        "8xEWYzkPmA+8vZpRsAPODgUFGEFbY5YJ9867rZuB9RiewPEwPayX/0sPAxqwZAyjhjJOejBrvGdgKMt9iKm0cSoRpwOzSVmAMA3H" +
        "JIRTNaBK3hdQgGMDwh4c24lYB/yGlGfpYYP8K2/u13UIjDXxM/x/RHAy0MQFeFkAAAAASUVORK5CYII=" +
        ""
