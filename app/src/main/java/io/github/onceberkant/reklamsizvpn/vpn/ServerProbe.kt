package io.github.onceberkant.reklamsizvpn.vpn

import android.util.Base64
import io.github.onceberkant.reklamsizvpn.data.VpnGateServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.net.InetSocketAddress
import java.net.Socket

/**
 * VPN Gate listesindeki sunucuların bir kısmı kapanmış ya da dolu oluyor.
 * Bağlanmadan önce hepsine paralel TCP bağlantı testi yapıp yanıt verenleri
 * gerçek gecikmeye (bu cihazdan ölçülen) göre sıralar.
 */
object ServerProbe {

    data class Endpoint(val host: String, val port: Int, val tcp: Boolean)

    /** UDP sunucuları TCP ile test edilemez; listenin sonuna konur. */
    private const val UDP_RANK_MS = 5_000L

    fun endpoint(server: VpnGateServer): Endpoint? = try {
        val text = String(Base64.decode(server.configBase64, Base64.DEFAULT), Charsets.UTF_8)
        var tcp = false
        var host: String? = null
        var port = 1194
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("#") || line.startsWith(";")) continue
            val parts = line.split(Regex("\\s+"))
            when (parts.firstOrNull()) {
                "proto" -> tcp = parts.getOrNull(1)?.startsWith("tcp") == true
                "remote" -> if (host == null) {
                    host = parts.getOrNull(1)
                    port = parts.getOrNull(2)?.toIntOrNull() ?: port
                    parts.getOrNull(3)?.let { tcp = it.startsWith("tcp") }
                }
            }
        }
        host?.let { Endpoint(it, port, tcp) }
    } catch (e: Exception) {
        null
    }

    /** Ulaşılabilen sunucuları en hızlıdan yavaşa döndürür. */
    suspend fun rank(servers: List<VpnGateServer>, timeoutMs: Int = 2_500): List<VpnGateServer> =
        coroutineScope {
            servers.map { server ->
                async(Dispatchers.IO) { server to measure(server, timeoutMs) }
            }.awaitAll()
                .mapNotNull { (server, ms) -> ms?.let { server to it } }
                .sortedBy { it.second }
                .map { it.first }
        }

    private fun measure(server: VpnGateServer, timeoutMs: Int): Long? {
        val ep = endpoint(server) ?: return null
        if (!ep.tcp) return UDP_RANK_MS
        return try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(ep.host, ep.port), timeoutMs)
                (System.nanoTime() - start) / 1_000_000
            }
        } catch (e: Exception) {
            null
        }
    }
}
