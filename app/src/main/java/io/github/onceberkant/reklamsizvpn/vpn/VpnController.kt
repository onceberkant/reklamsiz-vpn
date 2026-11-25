package io.github.onceberkant.reklamsizvpn.vpn

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import de.blinkt.openvpn.R
import de.blinkt.openvpn.core.ConnectionStatus
import de.blinkt.openvpn.core.IOpenVPNServiceInternal
import de.blinkt.openvpn.core.OpenVPNService
import de.blinkt.openvpn.core.ProfileManager
import de.blinkt.openvpn.core.VPNLaunchHelper
import de.blinkt.openvpn.core.VpnStatus
import io.github.onceberkant.reklamsizvpn.data.VpnGateServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Tek tuşla bağlanma mantığı:
 * 1. Aday sunuculara paralel erişim testi yapılır, yanıt vermeyenler elenir.
 * 2. Kalanlar en hızlıdan başlanarak sırayla denenir.
 * 3. Sunucu reddederse (AUTH_FAILED), ulaşılamazsa ya da bağlandıktan kısa süre
 *    sonra koparsa otomatik olarak sıradakine geçilir.
 */
object VpnController {

    enum class Phase { IDLE, CONNECTING, CONNECTED, RECONNECTING, FAILED }

    data class Status(
        val phase: Phase = Phase.IDLE,
        val server: VpnGateServer? = null,
        val attempt: Int = 0,
        val total: Int = 0,
        val message: String = "",
    )

    data class Traffic(val inBytes: Long = 0, val outBytes: Long = 0)

    /** OpenVPN sürecinden gelen her durum değişikliği (sıra numaralı). */
    private data class StateEvent(
        val seq: Long,
        val state: String,
        val message: String,
        val level: ConnectionStatus,
    )

    private const val MAX_ATTEMPTS = 8
    private const val ATTEMPT_TIMEOUT_MS = 20_000L
    /** Bağlandıktan sonra bu süre içinde koparsa sunucu "kararsız" sayılır */
    private const val STABILITY_WINDOW_MS = 45_000L
    private const val BLACKLIST_MS = 15 * 60_000L
    private const val PREFS = "vpn_durum"

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    private val ovpnLevel = MutableStateFlow(ConnectionStatus.LEVEL_NOTCONNECTED)
    private val seq = AtomicLong(0)
    private val events = MutableSharedFlow<StateEvent>(
        replay = 64,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** Kısa süre önce başarısız olan sunucular (IP -> bitiş zamanı) */
    private val blacklist = ConcurrentHashMap<String, Long>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var connectJob: Job? = null
    private var initialized = false
    private lateinit var appContext: Context

    private val stateListener = object : VpnStatus.StateListener {
        override fun updateState(
            state: String?,
            logmessage: String?,
            localizedResId: Int,
            level: ConnectionStatus?,
            intent: Intent?,
        ) {
            val lvl = level ?: ConnectionStatus.UNKNOWN_LEVEL
            ovpnLevel.value = lvl
            events.tryEmit(StateEvent(seq.incrementAndGet(), state ?: "", logmessage ?: "", lvl))
        }

        override fun setConnectedVPN(uuid: String?) {}
    }

    private val byteListener = VpnStatus.ByteCountListener { inBytes, outBytes, _, _ ->
        _traffic.value = Traffic(inBytes, outBytes)
    }

    /** Yalnızca ana uygulama sürecinde, bir kez çağrılır. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        VpnStatus.addStateListener(stateListener)
        VpnStatus.addByteCountListener(byteListener)
        scope.launch { ovpnLevel.collect { onLevelChanged(it) } }
    }

    private fun onLevelChanged(level: ConnectionStatus) {
        val current = _status.value
        when (level) {
            ConnectionStatus.LEVEL_CONNECTED -> {
                if (current.phase != Phase.CONNECTING) {
                    _status.value = current.copy(
                        phase = Phase.CONNECTED,
                        server = current.server ?: lastServer(),
                        message = "",
                    )
                }
            }

            ConnectionStatus.LEVEL_NOTCONNECTED -> {
                if (current.phase == Phase.CONNECTED || current.phase == Phase.RECONNECTING) {
                    _status.value = Status(Phase.IDLE, message = "Bağlantı kesildi")
                }
            }

            ConnectionStatus.LEVEL_CONNECTING_NO_SERVER_REPLY_YET,
            ConnectionStatus.LEVEL_CONNECTING_SERVER_REPLIED,
            ConnectionStatus.LEVEL_NONETWORK,
            ConnectionStatus.LEVEL_VPNPAUSED,
            ConnectionStatus.LEVEL_START -> {
                if (current.phase == Phase.CONNECTED) {
                    _status.value = current.copy(phase = Phase.RECONNECTING)
                }
            }

            else -> Unit
        }
    }

    /** Sunucunun bağlantıyı reddettiğini ya da koptuğunu gösteren durumlar */
    private fun isFailure(e: StateEvent): Boolean =
        e.level == ConnectionStatus.LEVEL_AUTH_FAILED ||
            e.state == "RECONNECTING" ||
            // Önceki oturumu bilerek kapattığımızda gelen SIGINT çıkışı hata değil
            (e.state == "EXITING" && !e.message.contains("SIGINT"))

    private fun isTunnelActive(): Boolean =
        ovpnLevel.value != ConnectionStatus.LEVEL_NOTCONNECTED &&
            ovpnLevel.value != ConnectionStatus.LEVEL_AUTH_FAILED

    /**
     * @param pool Seçili ülkenin (ya da tümünün) puana göre sıralı sunucuları.
     * VPN izni alınmış olmalı (VpnService.prepare == null).
     */
    fun connect(pool: List<VpnGateServer>) {
        connectJob?.cancel()
        if (pool.isEmpty()) {
            _status.value = Status(Phase.FAILED, message = "Bu seçim için sunucu bulunamadı.")
            return
        }
        connectJob = scope.launch {
            _status.value = Status(Phase.CONNECTING, message = "Sunucular test ediliyor…")

            // Açık bir tünel varsa önce kapat: testler gerçek ağdan yapılsın
            if (isTunnelActive()) {
                stopService()
                delay(1_500)
            }

            val now = System.currentTimeMillis()
            val fresh = pool.filter { (blacklist[it.ip] ?: 0L) < now }.ifEmpty { pool }
            val reachable = ServerProbe.rank(fresh)
            VpnStatus.logInfo("Erişim testi: ${fresh.size} sunucudan ${reachable.size} tanesi yanıt verdi")
            val candidates = reachable.ifEmpty { fresh }.take(MAX_ATTEMPTS)

            val total = candidates.size
            for ((index, server) in candidates.withIndex()) {
                _status.value = Status(Phase.CONNECTING, server, index + 1, total, "Bağlanılıyor…")

                if (index > 0 && isTunnelActive()) {
                    stopService()
                    delay(1_200)
                }

                val profile = try {
                    withContext(Dispatchers.Default) { OpenVpnProfiles.build(server) }
                } catch (e: Exception) {
                    VpnStatus.logError("Yapılandırma okunamadı (${server.ip}): ${e.message}")
                    continue
                }
                if (profile.checkProfile(appContext) != R.string.no_error_found) {
                    VpnStatus.logError("Profil kontrolü başarısız: ${server.ip}")
                    continue
                }

                val startSeq = seq.get()
                ovpnLevel.value = ConnectionStatus.LEVEL_START
                withContext(Dispatchers.IO) {
                    ProfileManager.setTemporaryProfile(appContext, profile)
                }
                VPNLaunchHelper.startOpenVpn(profile, appContext, "Reklamsız VPN", true)

                val outcome = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                    events.first {
                        it.seq > startSeq &&
                            (it.level == ConnectionStatus.LEVEL_CONNECTED || isFailure(it))
                    }
                }
                if (outcome == null || outcome.level != ConnectionStatus.LEVEL_CONNECTED) {
                    val reason = when {
                        outcome == null -> "zaman aşımı"
                        outcome.level == ConnectionStatus.LEVEL_AUTH_FAILED -> "sunucu reddetti (dolu olabilir)"
                        else -> "bağlantı kurulamadı"
                    }
                    VpnStatus.logInfo("Sunucu atlandı (${server.ip}): $reason")
                    blacklist[server.ip] = System.currentTimeMillis() + BLACKLIST_MS
                    continue
                }

                saveLastServer(server)
                _status.value = Status(Phase.CONNECTED, server, index + 1, total)

                // Bağlandıktan hemen sonra kopan sunucuları ele
                val connectedSeq = outcome.seq
                val dropped = withTimeoutOrNull(STABILITY_WINDOW_MS) {
                    events.first { it.seq > connectedSeq && isFailure(it) }
                }
                if (dropped == null) return@launch

                VpnStatus.logInfo("Sunucu bağlandıktan sonra yanıt vermeyi kesti (${server.ip}), sıradakine geçiliyor")
                blacklist[server.ip] = System.currentTimeMillis() + BLACKLIST_MS
            }

            stopService()
            _status.value = Status(
                Phase.FAILED,
                message = "Denenen $total sunucunun hiçbiri çalışmadı. Başka bir ülke deneyin ya da listeyi yenileyin.",
            )
        }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        stopService()
        _status.value = Status(Phase.IDLE)
    }

    private fun stopService() {
        ProfileManager.setConnectedVpnProfileDisconnected(appContext)
        val intent = Intent(appContext, OpenVPNService::class.java)
            .setAction(OpenVPNService.START_SERVICE)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                try {
                    IOpenVPNServiceInternal.Stub.asInterface(binder)?.stopVPN(false)
                } catch (e: Exception) {
                    VpnStatus.logException(e)
                }
                try {
                    appContext.unbindService(this)
                } catch (_: Exception) {
                }
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        appContext.bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }

    private fun saveLastServer(server: VpnGateServer) {
        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("ip", server.ip)
            .putString("host", server.hostName)
            .putString("country", server.countryCode)
            .putString("countryName", server.countryName)
            .apply()
    }

    private fun lastServer(): VpnGateServer? {
        val p = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val ip = p.getString("ip", null) ?: return null
        return VpnGateServer(
            hostName = p.getString("host", "") ?: "",
            ip = ip,
            score = 0,
            pingMs = -1,
            speedBps = 0,
            countryName = p.getString("countryName", "") ?: "",
            countryCode = p.getString("country", "") ?: "",
            sessions = 0,
            configBase64 = "",
        )
    }
}
