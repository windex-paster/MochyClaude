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
import com.mochyy.mochymusic.module.StreamOption
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
class YandexMusicModule : BrowsableSourceModule, ModuleFields, com.mochyy.mochymusic.module.ModuleArt {

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

    // set when Yandex turns the token down, so the module's status says so instead of "Token set"
    @Volatile private var refusal: String? = null

    override val fields = listOf(
        ModuleField(
            id = FieldToken,
            label = "OAuth token",
            kind = FieldKind.Text,
            hint = "y0_… or AQAA…",
            description = "Your own Yandex Music token. It stays on this phone and is sent only to api.music.yandex.net. " +
                "How to get one is described in the yandex-music-api project's documentation (search \"yandex-music-api token\"). " +
                "To see your liked songs, open the Яндекс tab in Search and search for \"likes\" (or \"мне нравится\").",
        ),
        ModuleField(
            id = FieldQuality,
            label = "Quality",
            kind = FieldKind.Select,
            default = QualityBest,
            options = listOf(
                StreamOption(QualityBest, "Best", "The highest quality plain file Yandex offers"),
                StreamOption(QualitySaver, "Data saver", "Smaller files for slow or limited internet"),
            ),
        ),
    )

    @Volatile private var saver = false

    override fun onFields(values: Map<String, String>) {
        token = cleanToken(values[FieldToken])
        saver = values[FieldQuality] == QualitySaver
        refusal = null
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

    override fun accountState(): AccountState =
        when {
            token == null -> AccountState.SignedOut
            refusal != null -> AccountState.Expired("Token refused, get a new one")
            else -> AccountState.SignedIn("Token set")
        }

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
        if (q.lowercase() in LikesWords) return likedTracks(limit.coerceAtLeast(100))
        val body = apiGet("the search") { "$Api/search?text=${enc(q)}&type=track&page=0&nocorrect=false" }
        return parseSearch(body).take(limit)
    }

    /** the songs this account has liked, newest first: the ids from the library, then their details in one call */
    private fun likedTracks(limit: Int): List<SearchHit> {
        val status = apiGet("the account") { "$Api/account/status" }
        val uid = runCatching {
            val account = json.parseToJsonElement(status).jsonObject["result"]?.jsonObject?.get("account")?.jsonObject
            account?.str("uid") ?: account?.long("uid")?.toString()
        }.getOrNull() ?: throw ModuleUnavailable("Yandex didn't say whose account this is")
        val likes = apiGet("your liked songs") { "$Api/users/$uid/likes/tracks" }
        val ids = parseLikedIds(likes).take(limit)
        if (ids.isEmpty()) return emptyList()
        val details = apiGet("the liked songs' details") { "$Api/tracks?track-ids=${enc(ids.joinToString(","))}" }
        val byId = runCatching {
            json.parseToJsonElement(details).jsonObject["result"]?.jsonArray.orEmpty()
                .mapNotNull { (it as? JsonObject)?.let(::hitOf) }
        }.getOrDefault(emptyList())
        // keep the order of the likes, not the order the details came back in
        val order = ids.withIndex().associate { (i, id) -> id.substringBefore(':') to i }
        return byId.sortedBy { order[it.id.substringBefore(':')] ?: Int.MAX_VALUE }
    }

    internal fun parseLikedIds(body: String): List<String> = runCatching {
        json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("library")?.jsonObject?.get("tracks")?.jsonArray.orEmpty()
            .mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val id = o.str("id") ?: o.long("id")?.toString() ?: return@mapNotNull null
                val album = o.str("albumId") ?: o.long("albumId")?.toString()
                if (album != null) "$id:$album" else id
            }
    }.getOrDefault(emptyList())

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
            val choice = pickFile(listing, saver)
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
        val quality = if (saver) "nq" else "hq"
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
    internal fun pickFile(listing: String, saver: Boolean = false): FileChoice? {
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
        return all.sortedWith(
            compareBy<FileChoice>({ if (it.codec == "mp3") 0 else 1 }, { if (saver) it.bitrate else -it.bitrate }),
        ).firstOrNull()
    }

    companion object {
        const val Id = "yandex"
        private const val Api = "https://api.music.yandex.net"
        private const val FieldToken = "token"
        private const val FieldQuality = "quality"
        private const val QualityBest = "best"
        private const val QualitySaver = "saver"
        private val LikesWords = setOf("likes", "my likes", "liked", "мне нравится", "понравившиеся", "лайки")
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
            response.ok -> {
                refusal = null
                response.text
            }
            response.code == 401 || response.code == 403 -> {
                refusal = "refused"
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


private const val IconPng =
        "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" +
        ""
