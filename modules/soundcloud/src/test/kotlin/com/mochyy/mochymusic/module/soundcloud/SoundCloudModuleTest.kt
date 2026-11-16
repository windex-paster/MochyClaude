package com.mochyy.mochymusic.module.soundcloud

import com.mochyy.mochymusic.module.HttpRequest
import com.mochyy.mochymusic.module.HttpResponse
import com.mochyy.mochymusic.module.ModuleHost
import com.mochyy.mochymusic.module.ModuleStorage
import com.mochyy.mochymusic.module.ResolveResult
import com.mochyy.mochymusic.module.StreamFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The module against answers shaped like api-v2's. The fixtures are written by hand from the
 * fields the module reads, not captured from SoundCloud, so a change on SoundCloud's side shows
 * up only when the module runs on a phone.
 */
class SoundCloudModuleTest {

    private val clientId = "a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6"

    private val searchJson = """
        {"collection":[
          {"kind":"track","id":111,"title":"Full Song","duration":200000,"full_duration":200000,"streamable":true,"policy":"ALLOW",
           "artwork_url":"https://i1.sndcdn.com/artworks-abc-large.jpg","permalink_url":"https://soundcloud.com/a/full",
           "user":{"username":"uploader"},"publisher_metadata":{"artist":"Real Artist"},
           "track_authorization":"auth-111",
           "media":{"transcodings":[
             {"url":"https://api-v2.soundcloud.com/media/111/hls","format":{"protocol":"hls","mime_type":"audio/mpeg"}},
             {"url":"https://api-v2.soundcloud.com/media/111/prog","format":{"protocol":"progressive","mime_type":"audio/mpeg"}},
             {"url":"https://api-v2.soundcloud.com/media/111/enc","format":{"protocol":"encrypted-hls","mime_type":"audio/mp4; codecs=\"mp4a.40.2\""}}
           ]}},
          {"kind":"track","id":222,"title":"Only A Preview","duration":30000,"streamable":true,"policy":"SNIP","user":{"username":"x"}},
          {"kind":"track","id":333,"title":"Blocked","streamable":true,"policy":"BLOCK","user":{"username":"x"}},
          {"kind":"user","id":444,"username":"not a track"}
        ]}
    """.trimIndent()

    private class FakeHost(val routes: (HttpRequest) -> HttpResponse) : ModuleHost {
        val requests = ArrayList<HttpRequest>()
        override val storage = object : ModuleStorage {
            val map = HashMap<String, String>()
            override fun get(key: String) = map[key]
            override fun set(key: String, value: String?) {
                if (value == null) map.remove(key) else map[key] = value
            }
        }
        override fun http(request: HttpRequest): HttpResponse {
            requests += request
            return routes(request)
        }
        override fun log(message: String) = Unit
        override val folder: File = createTempDirectory("soundcloud").toFile()
    }

    private fun ok(text: String) = HttpResponse(200, text.toByteArray())

    private fun module(extra: (HttpRequest) -> HttpResponse? = { null }): Pair<SoundCloudModule, FakeHost> {
        val host = FakeHost { request ->
            extra(request) ?: when {
                request.url == "https://soundcloud.com" ->
                    ok("""<html><script src="https://a-v2.sndcdn.com/assets/0-aaa.js"></script><script src="https://a-v2.sndcdn.com/assets/50-bbb.js"></script></html>""")
                request.url.endsWith("0-aaa.js") -> ok("var nothing=1")
                request.url.endsWith("50-bbb.js") -> ok("""x={client_id:"$clientId",y:1}""")
                "/search/tracks?" in request.url -> ok(searchJson)
                "/tracks/111?" in request.url -> ok(searchJson.let { Json.parseToJsonElement(it).jsonObject["collection"]!!.let { c -> (c as kotlinx.serialization.json.JsonArray)[0].toString() } })
                "/media/111/prog?" in request.url -> ok("""{"url":"https://cf-media.sndcdn.com/111.mp3?sig=1"}""")
                "/media/111/hls?" in request.url -> ok("""{"url":"https://cf-hls-media.sndcdn.com/111.m3u8"}""")
                else -> HttpResponse(404, ByteArray(0))
            }
        }
        return SoundCloudModule().also { it.attach(host) } to host
    }

    @Test
    fun finds_the_client_id_in_the_pages_scripts() {
        assertEquals(clientId, SoundCloudModule.extractClientId("""a=1,client_id:"$clientId",b=2"""))
        assertNull(SoundCloudModule.extractClientId("""client_id:"tooshort""""))
        assertEquals(
            listOf("https://a-v2.sndcdn.com/assets/1.js", "https://a-v2.sndcdn.com/assets/2.js"),
            SoundCloudModule.extractScriptUrls(
                """<script src="https://a-v2.sndcdn.com/assets/1.js"></script><script src="https://a-v2.sndcdn.com/assets/2.js"></script><script src="https://other.com/x.js"></script>""",
            ),
        )
    }

    @Test
    fun search_keeps_full_tracks_only_and_reads_the_real_artist() = runBlocking {
        val (module, host) = module()
        val hits = module.search("full song")
        assertEquals(1, hits.size)
        val hit = hits.single()
        assertEquals("111", hit.id)
        assertEquals("Full Song", hit.title)
        assertEquals("Real Artist", hit.artist)
        assertEquals(200_000L, hit.durationMs)
        assertEquals("https://i1.sndcdn.com/artworks-abc-t500x500.jpg", hit.artworkUrl)
        // the client id came from the website and rode on the search
        assertTrue(host.requests.any { "client_id=$clientId" in it.url && "/search/tracks?" in it.url })
    }

    @Test
    fun stream_prefers_a_whole_mp3_file_over_pieces() = runBlocking {
        val (module, _) = module()
        val result = module.streamFor("111", emptyMap())
        assertTrue(result is ResolveResult.Found)
        val stream = (result as ResolveResult.Found).stream
        assertEquals("https://cf-media.sndcdn.com/111.mp3?sig=1", stream.url)
        assertEquals(StreamFormat.Progressive, stream.format)
        assertEquals("MP3", stream.codec)
        assertEquals("111", stream.matched.id)
    }

    @Test
    fun stream_falls_back_to_hls_and_never_picks_encrypted() {
        val (module, _) = module()
        val track = Json.parseToJsonElement(
            """{"media":{"transcodings":[
                {"url":"u/enc","format":{"protocol":"encrypted-hls","mime_type":"audio/mpeg"}},
                {"url":"u/hls-aac","format":{"protocol":"hls","mime_type":"audio/mp4; codecs=\"mp4a.40.2\""}},
                {"url":"u/hls-mp3","format":{"protocol":"hls","mime_type":"audio/mpeg"}}
            ]}}""",
        ).jsonObject
        val picked = module.pickTranscoding(track)
        assertNotNull(picked)
        assertEquals("u/hls-mp3", picked!!.url)
        assertTrue(picked.hls)
        assertNull(module.pickTranscoding(Json.parseToJsonElement("""{"media":{"transcodings":[{"url":"u","format":{"protocol":"encrypted-hls","mime_type":"audio/mpeg"}}]}}""").jsonObject))
    }

    @Test
    fun a_turned_down_client_id_is_found_again_once() = runBlocking {
        var searches = 0
        val (module, host) = module { request ->
            if ("/search/tracks?" in request.url && searches++ == 0) HttpResponse(401, ByteArray(0)) else null
        }
        val hits = module.search("full song")
        assertEquals(1, hits.size)
        assertEquals(2, host.requests.count { "/search/tracks?" in it.url })
        assertEquals(2, host.requests.count { it.url == "https://soundcloud.com" })
        assertFalse(searches == 0)
    }

    @Test
    fun script_and_key_patterns_take_other_markup() {
        // another attribute order, upper-case and single-quoted, and a link without its scheme; a script off sndcdn.com is left out
        assertEquals(
            listOf("https://a-v2.sndcdn.com/assets/3.js"),
            SoundCloudModule.extractScriptUrls(
                """<script crossorigin async SRC='//a-v2.sndcdn.com/assets/3.js'></script><script defer src="/assets/4.js"></script>""",
            ),
        )
        assertEquals(clientId, SoundCloudModule.extractClientId("""{"client_id":"$clientId"}"""))
        assertEquals(clientId, SoundCloudModule.extractClientId("""a=1;client_id='$clientId'"""))
    }

    @Test
    fun a_key_from_the_settings_is_used_without_visiting_the_website() = runBlocking {
        val (module, host) = module()
        module.onFields(mapOf("client_id" to "ffffffffffffffffffffffffffffffff"))
        module.search("full song")
        assertTrue(host.requests.none { it.url == "https://soundcloud.com" })
        assertTrue(host.requests.any { "client_id=ffffffffffffffffffffffffffffffff" in it.url })
    }

    @Test
    fun a_missing_key_says_which_step_failed() {
        val (module, _) = module { request ->
            if (request.url == "https://soundcloud.com") HttpResponse(403, ByteArray(0)) else null
        }
        val message = try {
            runBlocking { module.search("x") }
            ""
        } catch (e: com.mochyy.mochymusic.module.ModuleUnavailable) {
            e.message.orEmpty()
        }
        assertTrue(message, "answered 403" in message)
    }

    @Test
    fun a_pressed_search_result_streams_without_asking_for_the_track_again() = runBlocking {
        val (module, host) = module()
        module.search("full song")
        assertTrue(module.streamFor("111", emptyMap()) is ResolveResult.Found)
        assertTrue(host.requests.none { "/tracks/111?" in it.url })
    }

    @Test
    fun a_file_that_404s_is_skipped_for_the_next_one() = runBlocking {
        val (module, _) = module { request ->
            if ("/media/111/prog?" in request.url) HttpResponse(404, ByteArray(0)) else null
        }
        val result = module.streamFor("111", emptyMap())
        assertTrue(result is ResolveResult.Found)
        assertEquals(StreamFormat.Hls, (result as ResolveResult.Found).stream.format)
    }

    @Test
    fun a_failure_names_the_step() = runBlocking {
        val (module, _) = module { request ->
            if ("/tracks/111?" in request.url) HttpResponse(404, ByteArray(0)) else null
        }
        val result = module.streamFor("111", emptyMap())
        assertTrue(result is ResolveResult.Unavailable)
        assertTrue((result as ResolveResult.Unavailable).reason, "the track's details" in result.reason)
    }
}
