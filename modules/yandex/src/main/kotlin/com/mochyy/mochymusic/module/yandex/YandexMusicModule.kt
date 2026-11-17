package com.mochyy.mochymusic.module.yandex

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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Yandex Music, searched and played with the listener's own account.
 *
 * Yandex has no public API for other apps, so this speaks the same api.music.yandex.net the
 * unofficial clients (yandex-music-api and the players built on it) use, with an OAuth token the
 * listener pastes in the module's settings. It plays the plain MP3 and AAC files that service hands
 * out; the encrypted lossless stream (Plus only) is not touched. Whatever the account can't hear in
 * full on Yandex it can't hear here either.
 */
class YandexMusicModule : BrowsableSourceModule, ModuleFields {

    override val info = ModuleInfo(
        id = Id,
        name = "Yandex Music",
        version = "1.0",
        description = "Search and play Yandex Music with your own account (token in settings). MP3 and AAC, no lossless",
    )

    override val searchLabel = "Яндекс"

    private lateinit var host: ModuleHost
    private val json = Json { ignoreUnknownKeys = true }
    private val matches = ConcurrentHashMap<String, SearchHit>()

    @Volatile private var token: String? = null

    override val fields = listOf(
        ModuleField(
            id = FieldToken,
            label = "OAuth token",
            kind = FieldKind.Text,
            hint = "y0_… or AQAA…",
            description = "Your own Yandex Music token. It stays on this phone and is sent only to api.music.yandex.net. " +
                "How to get one is described in the yandex-music-api project's documentation (search \"yandex-music-api token\").",
        ),
    )

    override fun onFields(values: Map<String, String>) {
        token = cleanToken(values[FieldToken])
    }

    override fun attach(host: ModuleHost) {
        this.host = host
    }

    override fun accountState(): AccountState =
        if (token == null) AccountState.SignedOut else AccountState.SignedIn("Token set")

    override suspend fun verifyAccount(): AccountState {
        if (token == null) return AccountState.SignedOut
        return try {
            val body = apiGet("the account") { "$Api/account/status" }
            val login = runCatching {
                json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("account")?.jsonObject?.str("login")
            }.getOrNull()
            AccountState.SignedIn(login ?: "Signed in")
        } catch (e: ModuleUnavailable) {
            AccountState.Expired(e.message ?: "Yandex didn't answer")
        }
    }

    override fun signOut() {
        matches.clear()
    }

    // ---- search -------------------------------------------------------------------------------

    override suspend fun search(query: String, limit: Int): List<SearchHit> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val body = apiGet("the search") { "$Api/search?text=${enc(q)}&type=track&page=0&nocorrect=false" }
        return parseSearch(body).take(limit)
    }

    internal fun parseSearch(body: String): List<SearchHit> {
        val items = runCatching {
            json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("tracks")?.jsonObject?.get("results")?.jsonArray
        }.getOrNull() ?: return emptyList()
        return items.mapNotNull { (it as? JsonObject)?.let(::hitOf) }
    }

    private fun hitOf(track: JsonObject): SearchHit? {
        val id = track.str("id") ?: track.long("id")?.toString() ?: return null
        val title = track.str("title") ?: return null
        if (track.bool("available") == false) return null
        val albumId = (track["albums"] as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }?.let { it.str("id") ?: it.long("id")?.toString() }
        val artist = (track["artists"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("name") }
            .joinToString(", ")
        return SearchHit(
            // the id Yandex wants for a track in an album is "track:album"
            id = if (albumId != null) "$id:$albumId" else id,
            title = title,
            artist = artist,
            durationMs = track.long("durationMs"),
            artworkUrl = (track.str("coverUri") ?: track.str("ogImage"))?.let(::coverUrl),
            pageUrl = albumId?.let { "https://music.yandex.ru/album/$it/track/$id" },
        )
    }

    private fun coverUrl(uri: String) = "https://" + uri.replace("%%", "400x400")

    // ---- stream -------------------------------------------------------------------------------

    override suspend fun streamFor(id: String, picks: Map<String, String>): ResolveResult {
        val stream = try {
            streamOrNull(id)
        } catch (e: ModuleUnavailable) {
            return ResolveResult.Unavailable(e.message ?: "Yandex didn't answer")
        } catch (e: kotlinx.serialization.SerializationException) {
            return ResolveResult.Unavailable("Yandex sent something unexpected")
        } catch (e: IllegalArgumentException) {
            return ResolveResult.Unavailable("Yandex sent something unexpected")
        }
        return if (stream == null) ResolveResult.NotFound("Yandex has no plain file for this track") else ResolveResult.Found(stream)
    }

    internal class FileChoice(val codec: String, val bitrate: Int, val infoUrl: String)

    private fun streamOrNull(id: String): SourceStream? {
        if (token == null) throw ModuleUnavailable("Put your Yandex Music token in this module's settings")
        // the older list of files first; Yandex keeps changing what it still answers there
        var oldNote = ""
        try {
            val listing = apiGet("the file list") { "$Api/tracks/${enc(id)}/download-info" }
            val choice = pickFile(listing)
            if (choice != null) {
                val xml = apiGet("the file's address") { choice.infoUrl }
                val url = directLink(xml)
                if (url != null) return streamOf(id, url, "${choice.codec} ${choice.bitrate} kbps", choice.codec)
                oldNote = "its address was unreadable"
            } else {
                oldNote = "no mp3 or aac in its list (${codecsIn(listing)})"
            }
        } catch (e: ModuleUnavailable) {
            oldNote = e.message ?: "it didn't answer"
        }
        // the newer one: asks for plain ("raw") files only, never the encrypted kind
        val fresh = try {
            fileInfo(id)
        } catch (e: ModuleUnavailable) {
            throw ModuleUnavailable("Yandex gave no plain file. Old way: $oldNote. New way: ${e.message}")
        }
        return streamOf(id, fresh.url, "${fresh.codec} ${fresh.bitrate} kbps".replace(" 0 kbps", ""), fresh.codec)
    }

    private fun streamOf(id: String, url: String, quality: String, codec: String) = SourceStream(
        url = url,
        format = StreamFormat.Progressive,
        headers = emptyMap(),
        sourceLabel = "Yandex Music",
        qualityLabel = quality,
        matched = MatchedTrack(id = id, title = "", artist = "", album = null, durationMs = null, confidence = 1f),
        codec = codec.uppercase(),
    )

    internal class FreshFile(val url: String, val codec: String, val bitrate: Int)

    private fun fileInfo(rawId: String): FreshFile {
        val trackId = rawId.substringBefore(':')
        val ts = System.currentTimeMillis() / 1000
        val codecs = listOf("aac-mp4", "he-aac-mp4", "aac", "he-aac", "mp3")
        val quality = "hq"
        val transport = "raw"
        val sign = fileInfoSign(ts, trackId, quality, codecs, transport)
        val body = apiGet("the file info", desktop = true) {
            "$Api/get-file-info?ts=$ts&trackId=${enc(trackId)}&quality=$quality&codecs=${enc(codecs.joinToString(","))}" +
                "&transports=$transport&sign=${enc(sign)}"
        }
        return parseFileInfo(body)
    }

    internal fun parseFileInfo(body: String): FreshFile {
        val info = runCatching {
            json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("downloadInfo") as? JsonObject
        }.getOrNull() ?: throw ModuleUnavailable("no download info in the answer (${body.take(80)})")
        val url = info.str("url") ?: (info["urls"] as? JsonArray)?.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
            ?: throw ModuleUnavailable("no address in the answer")
        val transport = info.str("transport")
        if (transport != null && transport != "raw") throw ModuleUnavailable("only an encrypted file was offered")
        return FreshFile(url, info.str("codec") ?: "audio", info.long("bitrate")?.toInt() ?: 0)
    }

    private fun codecsIn(listing: String): String = runCatching {
        json.parseToJsonElement(listing).jsonObject["result"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.str("codec") }.joinToString(", ").ifEmpty { "empty" }
    }.getOrDefault("unreadable")

    /** the best plain file: MP3 before AAC, the highest bitrate first */
    internal fun pickFile(listing: String): FileChoice? {
        val all = runCatching {
            json.parseToJsonElement(listing).jsonObject["result"]?.jsonArray
        }.getOrNull().orEmpty().mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            val codec = o.str("codec") ?: return@mapNotNull null
            val bitrate = o.long("bitrateInChannel")?.toInt() ?: return@mapNotNull null
            val url = o.str("downloadInfoUrl") ?: return@mapNotNull null
            if (codec != "mp3" && codec != "aac") return@mapNotNull null
            FileChoice(codec, bitrate, url)
        }
        return all.sortedWith(compareBy<FileChoice>({ if (it.codec == "mp3") 0 else 1 }, { -it.bitrate })).firstOrNull()
    }

    companion object {
        const val Id = "yandex"
        private const val Api = "https://api.music.yandex.net"
        private const val FieldToken = "token"
        // the service's own signing salt for plain file addresses, as the unofficial clients publish it
        private const val SignSalt = "XGRlBW9FXlekgbPrRHuSiA"

        /**
         * What was pasted, reduced to the token: people paste the whole address Yandex sent them to
         * (…#access_token=y0_…&token_type=bearer&expires_in=…), quote it, or put "OAuth " before it.
         */
        internal fun cleanToken(raw: String?): String? {
            var t = raw?.trim().orEmpty()
            Regex("access_token=([^&\\s]+)").find(t)?.let { t = it.groupValues[1] }
            t = t.trim().trim('"', '\'', '`').removePrefix("OAuth ").removePrefix("Bearer ").trim()
            return t.takeIf { it.length >= 20 && t.none { c -> c.isWhitespace() } }
        }

        /** a short piece of what Yandex answered with, so a refusal says why */
        internal fun reasonIn(body: String): String? {
            val text = Regex("\"(?:message|name|error_description|error)\"\\s*:\\s*\"([^\"]{1,120})\"").find(body)?.groupValues?.get(1)
            return (text ?: body.trim().takeIf { it.isNotEmpty() && !it.startsWith("<") })?.take(120)
        }

        // the desktop app's signing key for the newer file-info call, as the unofficial clients publish it
        private const val DesktopKey = "kzqU4XhfCaY6B6JTHODeq5"

        internal fun fileInfoSign(ts: Long, trackId: String, quality: String, codecs: List<String>, transport: String): String {
            val mac = javax.crypto.Mac.getInstance("HmacSHA256")
            mac.init(javax.crypto.spec.SecretKeySpec(DesktopKey.toByteArray(), "HmacSHA256"))
            val message = "$ts$trackId$quality${codecs.joinToString("")}$transport"
            return java.util.Base64.getEncoder().encodeToString(mac.doFinal(message.toByteArray())).dropLast(1)
        }

        /** builds the address from the XML Yandex answers a file's info link with: host, path, ts and s */
        internal fun directLink(xml: String): String? {
            fun tag(name: String) = Regex("<$name>([^<]*)</$name>").find(xml)?.groupValues?.get(1)
            val host = tag("host") ?: return null
            val path = tag("path") ?: return null
            val ts = tag("ts") ?: return null
            val s = tag("s") ?: return null
            val digest = MessageDigest.getInstance("MD5").digest((SignSalt + path.drop(1) + s).toByteArray())
            val sign = digest.joinToString("") { "%02x".format(it) }
            return "https://$host/get-mp3/$sign/$ts$path"
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
        private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
        private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull()
    }

    // ---- as a playback provider: the song Mochy wants, found by name --------------------------

    override suspend fun resolve(track: TrackQuery, picks: Map<String, String>): ResolveResult =
        diagnose(track, picks) {}

    override suspend fun diagnose(track: TrackQuery, picks: Map<String, String>, report: (DiagnosticStep) -> Unit): ResolveResult {
        val key = "${track.title}|${track.artists.joinToString(",")}"
        report(DiagnosticStep("Find the song", null, "Searching Yandex Music…"))
        val hit = matches[key] ?: run {
            val query = "${TrackMatcher.core(track.title)} ${track.artists.firstOrNull().orEmpty()}".trim()
            val hits = try {
                search(query, 15)
            } catch (e: ModuleUnavailable) {
                report(DiagnosticStep("Find the song", false, e.message ?: "Yandex didn't answer"))
                return ResolveResult.Unavailable(e.message ?: "Yandex didn't answer")
            }
            val best = TrackMatcher.ranked(
                track,
                hits.map { CatalogTrack(it.id, it.title, it.artist, durationMs = it.durationMs) },
            ).firstOrNull()?.let { scored -> hits.first { it.id == scored.track.id } }
            if (best == null) {
                report(DiagnosticStep("Find the song", false, "No matching recording of “${track.title}”"))
                return ResolveResult.NotFound("No matching recording on Yandex Music")
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

    // ---- talking to yandex --------------------------------------------------------------------

    private fun apiGet(what: String, desktop: Boolean = false, urlFor: () -> String): String {
        val t = token ?: throw ModuleUnavailable("Put your Yandex Music token in this module's settings")
        val response = try {
            host.http(HttpRequest(urlFor(), headers = headersFor(t, desktop)))
        } catch (e: Exception) {
            throw ModuleUnavailable("Yandex didn't answer for $what")
        }
        return when {
            response.ok -> response.text
            response.code == 401 || response.code == 403 -> {
                val why = reasonIn(response.text)?.let { ": $it" }.orEmpty()
                throw ModuleUnavailable("Yandex turned down the token ($what, ${response.code}$why). It may be cut short, expired, or from another app; get a new one")
            }
            response.code == 404 -> throw ModuleUnavailable("Yandex has no $what (404)")
            else -> throw ModuleUnavailable("Yandex answered ${response.code} for $what")
        }
    }

    private fun headersFor(t: String, desktop: Boolean) = mapOf(
        "accept" to "application/json",
        "authorization" to "OAuth $t",
        "user-agent" to "Yandex-Music-API",
        "x-yandex-music-client" to if (desktop) "YandexMusicDesktopAppWindows/5.13.2" else "YandexMusicAndroid/24023621",
    )
}
