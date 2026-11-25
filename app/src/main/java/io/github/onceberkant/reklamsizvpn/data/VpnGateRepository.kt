package io.github.onceberkant.reklamsizvpn.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * VPN Gate sunucu listesini indirir ve son başarılı listeyi diske kaydeder.
 * Siteye ulaşılamazsa uygulama önbellekteki listeyle çalışmaya devam eder.
 */
class VpnGateRepository(context: Context) {

    private val cacheFile = File(context.applicationContext.filesDir, "vpngate.csv")

    fun cached(): List<VpnGateServer> = try {
        if (cacheFile.exists()) VpnGateParser.parse(cacheFile.readText()) else emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    /** Önbelleğin son güncellenme zamanı (ms), yoksa 0. */
    fun cacheTime(): Long = if (cacheFile.exists()) cacheFile.lastModified() else 0L

    suspend fun refresh(): List<VpnGateServer> = withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        for (url in API_URLS) {
            try {
                val text = download(url)
                val servers = VpnGateParser.parse(text)
                if (servers.isNotEmpty()) {
                    cacheFile.writeText(text)
                    return@withContext servers
                }
                lastError = IOException("Liste boş geldi")
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Bilinmeyen hata")
    }

    private fun download(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 45_000
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) ReklamsizVPN")
        try {
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private val API_URLS = listOf(
            "https://www.vpngate.net/api/iphone/",
            "http://www.vpngate.net/api/iphone/",
        )
    }
}
