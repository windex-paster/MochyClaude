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

    override val searchLabel = "Яндекс Музыка"

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
            val file = java.io.File(host.folder, "icon2.png")
            file.writeBytes(java.util.Base64.getDecoder().decode(IconPng))
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

    override suspend fun related(id: String, limit: Int): List<SearchHit> {
        val body = apiGet("the similar songs") { "$Api/tracks/${enc(id)}/similar" }
        val tracks = runCatching {
            json.parseToJsonElement(body).jsonObject["result"]?.jsonObject?.get("similarTracks")?.jsonArray
        }.getOrNull() ?: return emptyList()
        val own = id.substringBefore(':')
        return tracks.mapNotNull { (it as? JsonObject)?.let(::hitOf) }.filter { it.id.substringBefore(':') != own }.take(limit)
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
            response.code == 451 -> throw ModuleUnavailable("Yandex refuses this connection's region (451). A VPN or proxy that exits outside Russia does that: turn it off for Mochy, or pick a Russian exit")
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
    "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"
