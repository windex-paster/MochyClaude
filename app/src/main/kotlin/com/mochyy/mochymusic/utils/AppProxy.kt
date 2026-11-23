// the proxy from settings for everything that talks to youtube, read on each request so a change needs no restart

package com.mochyy.mochymusic.utils

import androidx.datastore.preferences.core.Preferences
import com.mochyy.mochymusic.constants.ProxyAllKey
import com.mochyy.mochymusic.constants.ProxyEnabledKey
import com.mochyy.mochymusic.constants.ProxyPasswordKey
import com.mochyy.mochymusic.constants.ProxyTypeKey
import com.mochyy.mochymusic.constants.ProxyUrlKey
import com.mochyy.mochymusic.constants.ProxyUsernameKey
import com.mochyy.mochymusic.extensions.toEnum
import com.music.innertube.YouTube
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Credentials
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Authenticator
import java.net.InetSocketAddress
import java.net.PasswordAuthentication
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import java.util.concurrent.TimeUnit

data class ProxyConfig(
    val type: Proxy.Type,
    val address: InetSocketAddress,
    val username: String,
    val password: String,
    // false keeps it to youtube, true sends the whole app through it
    val everything: Boolean = true,
) {
    val hasLogin: Boolean get() = username.isNotEmpty() || password.isNotEmpty()
}

object AppProxy {
    @Volatile
    var current: ProxyConfig? = null
        private set

    // one pool and dispatcher for every client in the app, so dozens of helpers don't each spin up their own thread pools
    internal val connectionPool = ConnectionPool()
    internal val dispatcher = Dispatcher().apply {
        maxRequests = 64
        maxRequestsPerHost = 10
    }

    // shared base client so callers can use newBuilder() instead of allocating fresh pools and executors
    val sharedClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .dispatcher(dispatcher)
            .connectionPool(connectionPool)
            .followAppProxy()
            .build()
    }

    // the phone's own proxy setting, read before ours takes over as the process-wide one
    private val system: ProxySelector? = ProxySelector.getDefault()

    private fun viaProxy(uri: URI?): List<Proxy>? {
        val config = current ?: return null
        val host = uri?.host ?: return null
        // the TV, the car and other phones sit on the home network, never behind the proxy
        if (isLocalHost(host)) return listOf(Proxy.NO_PROXY)
        // yandex answers 451 to an address outside its countries, so a proxy abroad would shut it out
        if (isYandexHost(host)) return listOf(Proxy.NO_PROXY)
        return listOf(Proxy(config.type, config.address))
    }

    // with no proxy of ours the phone's own proxy setting still counts, as it did before
    internal val selector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            viaProxy(uri) ?: system?.select(uri) ?: listOf(Proxy.NO_PROXY)

        override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) = Unit
    }

    // every client in the process that never asked for the proxy by name: modules, lyrics, covers, updates, media streams
    private val everywhere = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> =
            (if (current?.everything == true) viaProxy(uri) else null) ?: system?.select(uri) ?: listOf(Proxy.NO_PROXY)

        override fun connectFailed(uri: URI?, address: SocketAddress?, error: IOException?) = Unit
    }

    private val installed by lazy { ProxySelector.setDefault(everywhere) }

    private val privateIpv4 = Regex("""^(127\.|10\.|192\.168\.|169\.254\.|172\.(1[6-9]|2\d|3[01])\.)""")

    private val yandexDomains = listOf("yandex.net", "yandex.ru", "yandex.com", "yandex.by", "yandex.kz", "ya.ru")

    private fun isYandexHost(host: String): Boolean {
        val h = host.lowercase()
        return yandexDomains.any { h == it || h.endsWith(".$it") }
    }

    private fun isLocalHost(host: String): Boolean {
        val h = host.trim('[', ']').lowercase()
        return h == "localhost" || h.endsWith(".local") || h == "::1" || h.startsWith("fe80:") || h.startsWith("fd") && ':' in h ||
            privateIpv4.containsMatchIn(h)
    }

    /** what the settings say, with or without the switch on, null while the address isn't host:port */
    fun configFrom(settings: Preferences, requireEnabled: Boolean = true): ProxyConfig? {
        if (requireEnabled && settings[ProxyEnabledKey] != true) return null
        val address = parseAddress(settings[ProxyUrlKey].orEmpty()) ?: return null
        return ProxyConfig(
            type = settings[ProxyTypeKey].toEnum(defaultValue = Proxy.Type.HTTP),
            address = address,
            username = settings[ProxyUsernameKey].orEmpty(),
            password = settings[ProxyPasswordKey].orEmpty(),
            everything = settings[ProxyAllKey] ?: true,
        )
    }

    /** sends youtube through config, or straight out when it's null, false when nothing changed */
    fun apply(config: ProxyConfig?): Boolean {
        installed
        if (config == current) return false
        YouTube.proxyAuth = config?.takeIf { it.type == Proxy.Type.HTTP && it.hasLogin }
            ?.let { Credentials.basic(it.username, it.password) }
        useSocksLogin(config)
        // after the login: the youtube client rebuilds itself here and takes the login with it
        YouTube.proxy = config?.let { Proxy(it.type, it.address) }
        current = config
        connectionPool.evictAll()
        return true
    }

    /** reads "1.2.3.4:8080", "proxy.example.com:1080" or "[2001:db8::1]:3128", skipping a pasted scheme or login in front */
    fun parseAddress(text: String): InetSocketAddress? {
        val hostPort = text.trim().substringAfter("://").substringAfterLast('@').trimEnd('/')
        val port = hostPort.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val host = if (hostPort.startsWith("[")) {
            hostPort.substringAfter('[').substringBefore(']')
        } else {
            hostPort.substringBeforeLast(':')
        }
        if (host.isBlank()) return null
        // unresolved: the proxy's name is looked up when connecting, never here on the main thread
        return InetSocketAddress.createUnresolved(host, port)
    }

    /** the address as people write it, an ipv6 host in brackets so its port stays readable */
    fun addressLabel(address: InetSocketAddress): String =
        if (':' in address.hostString) "[${address.hostString}]:${address.port}" else "${address.hostString}:${address.port}"

    /** asks youtube for an empty page through config, how many ms it took or why it failed */
    suspend fun check(config: ProxyConfig): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            useSocksLogin(config)
            val client = sharedClient.newBuilder()
                .proxy(Proxy(config.type, config.address))
                .proxyAuthenticator { _, response ->
                    if (config.type != Proxy.Type.HTTP || !config.hasLogin || response.request.header(ProxyAuthHeader) != null) {
                        null
                    } else {
                        response.request.newBuilder().header(ProxyAuthHeader, Credentials.basic(config.username, config.password)).build()
                    }
                }
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
            val started = System.nanoTime()
            client.newCall(Request.Builder().url("https://www.youtube.com/generate_204").build()).execute().use { response ->
                if (!response.isSuccessful) throw ProxyRefusedException(response.code)
            }
            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
        }
    }

    /** what one service answered through the proxy: a code means the tunnel works, no code means it never got through */
    data class SiteCheck(val name: String, val millis: Long?, val code: Int?, val problem: String?)

    // the places the app talks to: any answer at all (even a 404) proves the proxy lets the connection through
    private val sites = listOf(
        "YouTube Music" to "https://music.youtube.com/",
        "YouTube audio" to "https://redirector.googlevideo.com/",
        "SoundCloud" to "https://api-v2.soundcloud.com/",
        "Apple Music" to "https://amp-api.music.apple.com/",
    )

    /** asks each service in [sites] through the proxy at once, so it shows which ones get through and which don't */
    suspend fun checkSites(config: ProxyConfig): List<SiteCheck> = withContext(Dispatchers.IO) {
        useSocksLogin(config)
        val client = OkHttpClient.Builder()
            .proxy(Proxy(config.type, config.address))
            .proxyAuthenticator { _, response ->
                if (config.type != Proxy.Type.HTTP || !config.hasLogin || response.request.header(ProxyAuthHeader) != null) {
                    null
                } else {
                    response.request.newBuilder().header(ProxyAuthHeader, Credentials.basic(config.username, config.password)).build()
                }
            }
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
        sites.map { (name, url) ->
            async {
                val started = System.nanoTime()
                runCatching {
                    client.newCall(Request.Builder().url(url).build()).execute().use { it.code }
                }.fold(
                    onSuccess = { SiteCheck(name, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started), it, null) },
                    onFailure = { SiteCheck(name, null, null, it.javaClass.simpleName.removeSuffix("Exception").ifBlank { "error" }) },
                )
            }
        }.awaitAll()
    }

    // socks has no header for a login, java asks this one global authenticator instead
    private fun useSocksLogin(config: ProxyConfig?) {
        val login = config?.takeIf { it.type == Proxy.Type.SOCKS && it.hasLogin }
        Authenticator.setDefault(
            login?.let {
                object : Authenticator() {
                    override fun getPasswordAuthentication() = PasswordAuthentication(it.username, it.password.toCharArray())
                }
            },
        )
    }
}

private const val ProxyAuthHeader = "Proxy-Authorization"

// the proxy or youtube answered the check with an error code, 407 means the proxy wants a sign-in
class ProxyRefusedException(val code: Int) : IOException("HTTP $code")

/** sends this client through the proxy from settings, read on every request */
fun OkHttpClient.Builder.followAppProxy(): OkHttpClient.Builder =
    proxySelector(AppProxy.selector)
        .proxyAuthenticator { _, response ->
            val auth = YouTube.proxyAuth
            // already sent once and still refused, asking again would only loop
            if (auth == null || response.request.header(ProxyAuthHeader) != null) {
                null
            } else {
                response.request.newBuilder().header(ProxyAuthHeader, auth).build()
            }
        }
        .dispatcher(AppProxy.dispatcher)
        .connectionPool(AppProxy.connectionPool)
