package me.erguotou.homehub.data

import com.wireguard.android.backend.Tunnel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import me.erguotou.homehub.wireguard.TunnelManager
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Builds the OkHttp client used for every HomeHub request.
 *
 * The old app trusted *all* certificates. Here TLS works like this:
 *  * `trustCustomCert = false` -> normal system trust (used for plain HTTP
 *    inside the WireGuard tunnel, and for public CAs)
 *  * `trustCustomCert = true`  -> only the certificate configured in the
 *    settings screen is trusted (self-signed servers)
 */
object HttpClientFactory {

    /** Upper bound for a cold-start retry: never hang forever on a bad tunnel. */
    private const val TUNNEL_WAIT_MS = 15_000L

    fun create(prefs: Prefs, verbose: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)

        // Cold start: the tunnel is brought up asynchronously in MainActivity,
        // while AlbumViewModel (and the other screens) fire their first request
        // as soon as the UI is composed. A socket created before the VPN exists
        // does not get adopted by the tunnel, so that request stays dead even
        // after WireGuard reports UP. Retry idempotent reads once the tunnel is
        // up. Non-GET/HEAD calls are left alone (uploads/mutations must not be
        // replayed), and without WireGuard configured the behaviour is unchanged.
        builder.addInterceptor(object : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request()
                val idempotent = request.method == "GET" || request.method == "HEAD"
                if (!idempotent || !prefs.isWireGuardConfigured()) {
                    return chain.proceed(request)
                }

                val upBeforeAttempt = TunnelManager.isConnected
                return try {
                    chain.proceed(request)
                } catch (e: IOException) {
                    // A genuine server failure while the tunnel was already up
                    // is not a startup race — don't paper over it with a retry.
                    if (upBeforeAttempt) throw e
                    val cameUp = runBlocking {
                        withTimeoutOrNull(TUNNEL_WAIT_MS) {
                            TunnelManager.state.first { it == Tunnel.State.UP }
                            // UP means the interface exists; the first handshake
                            // may still be in flight, so give it a beat.
                            delay(150)
                            true
                        }
                    }
                    if (cameUp != true) throw e
                    // First retry creates a fresh socket on the tunnel. If the
                    // initial handshake is still settling, give it one more beat.
                    return try {
                        chain.proceed(request)
                    } catch (second: IOException) {
                        Thread.sleep(300)
                        chain.proceed(request)
                    }
                }
            }
        })

        if (prefs.trustCustomCert && prefs.serverCertPem.isNotBlank()) {
            val (socketFactory, trustManager) = trustCustomCertificate(prefs.serverCertPem)
            if (socketFactory != null && trustManager != null) {
                builder.sslSocketFactory(socketFactory, trustManager)
            }
        }

        if (verbose) {
            builder.addInterceptor(
                HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BASIC }
            )
        }
        return builder.build()
    }

    fun retrofit(client: OkHttpClient, baseUrl: String): Retrofit =
        Retrofit.Builder()
            .baseUrl(withTrailingSlash(baseUrl))
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

    fun withTrailingSlash(url: String): String =
        if (url.endsWith("/")) url else "$url/"

    /** Build an SSLContext that trusts a single PEM certificate. */
    fun trustCustomCertificate(pem: String): Pair<javax.net.ssl.SSLSocketFactory?, X509TrustManager?> {
        return try {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = pem.byteInputStream().use { factory.generateCertificate(it) } as X509Certificate

            val keyStore = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                setCertificateEntry("homehub", cert)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(keyStore)
            }
            val trustManager = tmf.trustManagers.firstNotNullOf { it as? X509TrustManager }
            val sslContext = SSLContext.getInstance("TLS").apply {
                init(null, tmf.trustManagers, SecureRandom())
            }
            sslContext.socketFactory to trustManager
        } catch (e: Exception) {
            null to null
        }
    }
}
