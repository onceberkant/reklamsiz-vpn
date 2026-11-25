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

/**
 * Sunucu kalitesi ölçümü ve "en iyi 6" sıralaması.
 *
 * Ölçütler:
 *  - Kayıp: sunucuya [ROUNDS] kez TCP bağlantısı açılır; başarısız denemelerin oranı.
 *  - Ping: başarılı bağlantıların ortanca süresi (bu cihazdan, gerçek ağdan ölçülür).
 *  - Hız: VPN Gate'in sunucu için yayınladığı hat hızı (bağlanmadan ölçülemez).
 *
 * Sıralama: önce kayıp (en az), sonra ping sırası + hız sırası toplamı (en küçük),
 * eşitlikte düşük ping. Yani hem hızlı hem düşük gecikmeli, kayıpsız sunucular öne çıkar.
 */
object ServerProbe {

    const val BEST_COUNT = 6
    private const val ROUNDS = 5
    private const val TIMEOUT_MS = 1_500
    private const val PARALLEL = 24

    data class Endpoint(val host: String, val port: Int, val tcp: Boolean)

    data class Quality(
        val server: VpnGateServer,
        val pingMs: Long,
        val lossPercent: Int,
    )

    /** Bir seçim (ülke kodu ya da otomatik) için son ölçüm sonucu */
    data class Ranking(val key: String, val items: List<Quality>, val time: Long)

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

    /**
     * [pool] içindeki sunucuları ölçer, en iyi [BEST_COUNT] tanesini döndürür ve
     * sonucu [ranking] üzerinden yayınlar.
     */
    suspend fun measureBest(key: String, pool: List<VpnGateServer>): List<Quality> {
        val measured = measureAll(pool)
        val best = pickBest(measured)
        _ranking.value = Ranking(key, best, System.currentTimeMillis())
        return best
    }

    private suspend fun measureAll(pool: List<VpnGateServer>): List<Quality> = coroutineScope {
        val gate = Semaphore(PARALLEL)
        pool.map { server ->
            async(Dispatchers.IO) { gate.withPermit { measureOne(server) } }
        }.awaitAll().filterNotNull()
    }

    /** UDP sunucuları TCP ile sınanamadığı için ölçüme alınmaz. */
    private suspend fun measureOne(server: VpnGateServer): Quality? {
        val ep = endpoint(server) ?: return null
        if (!ep.tcp) return null

        val times = ArrayList<Long>(ROUNDS)
        var failures = 0
        for (round in 0 until ROUNDS) {
            val t = connectOnce(ep)
            if (t == null) {
                failures++
                // İlk denemede hiç yanıt yoksa sunucu kapalı: kalan turlarla vakit kaybetme
                if (round == 0) return Quality(server, Long.MAX_VALUE, 100)
            } else {
                times += t
            }
            delay(120)
        }
        if (times.isEmpty()) return Quality(server, Long.MAX_VALUE, 100)
        times.sort()
        val median = times[times.size / 2]
        return Quality(server, median, failures * 100 / ROUNDS)
    }

    private suspend fun connectOnce(ep: Endpoint): Long? = withContext(Dispatchers.IO) {
        try {
            Socket().use { socket ->
                val start = System.nanoTime()
                socket.connect(InetSocketAddress(ep.host, ep.port), TIMEOUT_MS)
                (System.nanoTime() - start) / 1_000_000
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun pickBest(measured: List<Quality>): List<Quality> {
        val alive = measured.filter { it.lossPercent < 100 }
        if (alive.isEmpty()) return emptyList()

        val pingRank = alive.sortedBy { it.pingMs }
            .withIndex().associate { (i, q) -> q.server.ip to i }
        val speedRank = alive.sortedByDescending { it.server.speedBps }
            .withIndex().associate { (i, q) -> q.server.ip to i }

        return alive.sortedWith(
            compareBy<Quality> { it.lossPercent }
                .thenBy { (pingRank[it.server.ip] ?: 0) + (speedRank[it.server.ip] ?: 0) }
                .thenBy { it.pingMs }
        ).take(BEST_COUNT)
    }
}
