package ali.paf.contacts.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

object HttpClientFactory {

    /**
     * Builds an OkHttpClient that:
     * - Attaches HTTP Basic auth to every request
     * - Trusts ALL server certificates (self-signed, expired, any CA)
     * - Always uses mobile data (binds socket to the cellular network)
     * - Does not check network availability — assumes internet is always present
     */
    fun create(context: Context, username: String, password: String): OkHttpClient {

        // ── Trust-all X509TrustManager ───────────────────────────────────────
        val trustAll = object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustAll), SecureRandom())
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.HEADERS
        }

        val builder = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Authorization", Credentials.basic(username, password))
                    .header("User-Agent", "AliPafContacts/1.0 (Android)")
                    .build()
                chain.proceed(request)
            }
            .addInterceptor(logging)
            .sslSocketFactory(sslContext.socketFactory, trustAll)
            .hostnameVerifier { _, _ -> true }
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)

        // ── Force mobile data ────────────────────────────────────────────────
        // Bind sockets to the cellular network so traffic always goes over
        // mobile data regardless of whether Wi-Fi is connected.
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            @Suppress("DEPRECATION")
            val mobileNetwork = cm.allNetworks.firstOrNull { network ->
                cm.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            }
            if (mobileNetwork != null) {
                builder.socketFactory(mobileNetwork.socketFactory)
            }
        } catch (_: Exception) {
            // No mobile network available — fall through to default routing
        }

        return builder.build()
    }
}
