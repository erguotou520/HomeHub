package me.erguotou.homehub.data

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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

    fun create(prefs: Prefs, verbose: Boolean = false): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS)

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

    /** Debug-only helper; never used in the app flow. */
    @SuppressLint("CustomX509TrustManager")
    val trustAll: Array<javax.net.ssl.TrustManager> = arrayOf(
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
    )
}
