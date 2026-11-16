package com.mochyy.mochymusic.module.yandex

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.security.MessageDigest

class YandexMusicModuleTest {

    @Test
    fun builds_the_direct_link_the_documented_way() {
        val xml = "<download-info><host>s1.storage.yandex.net</host><path>/rmusic/abc/1.mp3</path><ts>0005</ts><region>-1</region><s>deadbeef</s></download-info>"
        val md5 = MessageDigest.getInstance("MD5").digest(("XGRlBW9FXlekgbPrRHuSiA" + "rmusic/abc/1.mp3" + "deadbeef").toByteArray())
            .joinToString("") { "%02x".format(it) }
        assertEquals("https://s1.storage.yandex.net/get-mp3/$md5/0005/rmusic/abc/1.mp3", YandexMusicModule.directLink(xml))
        assertNull(YandexMusicModule.directLink("<download-info><host>h</host></download-info>"))
    }

    @Test
    fun picks_mp3_before_aac_and_the_highest_bitrate_and_skips_the_rest() {
        val listing = """{"result":[
            {"codec":"aac","bitrateInChannel":192,"downloadInfoUrl":"u/aac192"},
            {"codec":"mp3","bitrateInChannel":128,"downloadInfoUrl":"u/mp3128"},
            {"codec":"mp3","bitrateInChannel":320,"downloadInfoUrl":"u/mp3320"},
            {"codec":"flac","bitrateInChannel":900,"downloadInfoUrl":"u/flac"}]}"""
        val picked = YandexMusicModule().pickFile(listing)
        assertNotNull(picked)
        assertEquals("u/mp3320", picked!!.infoUrl)
        assertNull(YandexMusicModule().pickFile("""{"result":[{"codec":"flac","bitrateInChannel":900,"downloadInfoUrl":"x"}]}"""))
    }

    @Test
    fun search_reads_artists_album_and_cover() {
        val body = """{"result":{"tracks":{"results":[
            {"id":"111","title":"Song","durationMs":200000,"available":true,"artists":[{"name":"A"},{"name":"B"}],
             "albums":[{"id":222}],"coverUri":"avatars.yandex.net/get-music-content/1/x/%%"},
            {"id":"333","title":"Gone","available":false}]}}}"""
        val hits = YandexMusicModule().parseSearch(body)
        assertEquals(1, hits.size)
        assertEquals("111:222", hits[0].id)
        assertEquals("A, B", hits[0].artist)
        assertEquals("https://avatars.yandex.net/get-music-content/1/x/400x400", hits[0].artworkUrl)
    }
}
