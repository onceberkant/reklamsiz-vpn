package io.github.onceberkant.reklamsizvpn.vpn

import android.util.Base64
import io.github.onceberkant.reklamsizvpn.data.VpnGateServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Sunucu kalitesi ölçümü ve "en iyi 6" sıralaması.
 *
 * Ölçütler:
 *  - Kayıp ve ping: sistemin `ping` komutuyla 4 ICMP paketi (gerçek paket kaybı ve
 *    ortalama gecikme). Sunucu ICMP'yi engelliyorsa tek bir TCP bağlantısıyla gecikme
 *    ölçülür, kayıp "ölçülemedi" olarak işaretlenir.
 *  - Hız: VPN Gate'in yayınladığı hat hızı (bağlanmadan ölçülemez).
 *
 * VPN sunucusunun bağlantı portuna art arda bağlantı açmak SoftEther'in saldırı
 * korumasını tetikleyip bağlantının reddedilmesine yol açabildiği için TCP ile en
 * fazla bir kez dokunulur.
 *
 * Sıralama: önce kayıp (bilinen %0 → ölçülemedi → kayıplı), sonra ping sırası +
 * hız sırası toplamı (en küçük), eşitlikte düşük ping.
 */
object ServerProbe {

    const val BEST_COUNT = 6
    private const val ICMP_COUNT = 4
    private const val TCP_TIMEOUT_MS = 1_500
    private const val PARALLEL = 16

    data class Endpoint(val host: String, val port: Int, val tcp: Boolean)

    data class Quality(
        val server: VpnGateServer,
        val pingMs: Long,
        /** null: sunucu ICMP'yi engelliyor, kayıp ölçülemedi */
        val lossPercent: Int?,
    ) {
        /** Sıralama anahtarı: 0 = kayıpsız, 1 = ölçülemedi, 2+ = kayıplı */
        val lossRank: Int get() = when (lossPercent) {
            null -> 1
            0 -> 0
            else -> 1 + lossPercent
        }
    }

    /**
     * @param items Ekranda gösterilen en iyi 6
     * @param all   Ulaşılabilen tüm sunucular, aynı sırayla (yedek denemeler için)
     */
    data class Ranking(
        val key: String,
        val items: List<Quality>,
        val all: List<Quality>,
        val time: Long,
    )

    private val _ranking = MutableStateFlow<Ranking?>(null)
    val ranking: StateFlow<Ranking?> = _ranking.asStateFlow()

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

    /** Ölçer, sıralar ve sonucu [ranking] üzerinden yayınlar. Sıralı tüm listeyi döndürür. */
    suspend fun measureBest(key: String, pool: List<VpnGateServer>): List<Quality> {
        val measured = measureAll(pool)
        val ranked = rank(measured)
        _ranking.value = Ranking(key, ranked.take(BEST_COUNT), ranked, System.currentTimeMillis())
        return ranked
    }

    private suspend fun measureAll(pool: List<VpnGateServer>): List<Quality> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        pool.map { server ->
            async(Dispatchers.IO) { gate.withPermit { measureOne(server) } }
        }.awaitAll().filterNotNull()
    }

    private suspend fun measureOne(server: VpnGateServer): Quality? {
        val ep = endpoint(server) ?: return null
        // UDP sunucular TCP ile doğrulanamıyor; kaliteli liste için yalnızca TCP
        if (!ep.tcp) return null

        val icmp = icmpPing(ep.host)
        if (icmp != null && icmp.second < 100) {
            // ICMP yanıt veriyor; bağlantı portunun açık olduğunu da bir kez doğrula
            connectOnce(ep) ?: return null
            return Quality(server, icmp.first, icmp.second)
        }
        // ICMP kapalı: tek TCP bağlantısıyla gecikme
        val t = connectOnce(ep) ?: return null
        return Quality(server, t, null)
    }

    /** @return (ortalama ms, kayıp yüzdesi) ya da ping komutu yoksa null */
    private suspend fun icmpPing(host: String): Pair<Long, Int>? = withContext(Dispatchers.IO) {
        try {
            val process = ProcessBuilder(
                "/system/bin/ping", "-n", "-q",
                "-c", ICMP_COUNT.toString(),
                "-i", "0.2",
                "-W", "1",
                host,
            ).redirectErrorStream(true).start()
            if (!process.waitFor(4, TimeUnit.SECONDS)) {
                process.destroy()
                return@withContext null
            }
            val out = process.inputStream.bufferedReader().readText()
            val loss = Regex("(\\d+(?:\\.\\d+)?)% packet loss").find(out)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.toInt()
                ?: return@withContext null
            val avg = Regex("= [\\d.]+/([\\d.]+)/").find(out)
                ?.groupValues?.get(1)?.toDoubleOrNull()?.toLong()
                ?: Long.MAX_VALUE
            avg to loss
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun connectOnce(ep: Endpoint): Long? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(ep.host, ep.port), TCP_TIMEOUT_MS)
                (System.nanoTime() - start) / 1_000_000
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun rank(measured: List<Quality>): List<Quality> {
        if (measured.isEmpty()) return emptyList()
        val pingRank = measured.sortedBy { it.pingMs }
            .withIndex().associate { (i, q) -> q.server.ip to i }
        val speedRank = measured.sortedByDescending { it.server.speedBps }
            .withIndex().associate { (i, q) -> q.server.ip to i }

        return measured.sortedWith(
            compareBy<Quality> { it.lossRank }
                .thenBy { (pingRank[it.server.ip] ?: 0) + (speedRank[it.server.ip] ?: 0) }
                .thenBy { it.pingMs }
        )
    }

    @Suppress("unused")
    private suspend fun pause() = delay(0)
}
