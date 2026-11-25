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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Tek tuşla bağlanma mantığı:
 * aday sunucuları sırayla dener, biri [ATTEMPT_TIMEOUT_MS] içinde bağlanmazsa sıradakine geçer.
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

    private const val ATTEMPT_TIMEOUT_MS = 25_000L
    private const val PREFS = "vpn_durum"

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()

    private val _traffic = MutableStateFlow(Traffic())
    val traffic: StateFlow<Traffic> = _traffic.asStateFlow()

    /** OpenVPN sürecinden gelen ham bağlantı seviyesi */
    private val ovpnLevel = MutableStateFlow(ConnectionStatus.LEVEL_NOTCONNECTED)

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
            ovpnLevel.value = level ?: ConnectionStatus.UNKNOWN_LEVEL
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
                // CONNECTING sırasında bağlanma döngüsü kendisi karar verir
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

    /** VPN izni alınmış olmalı (VpnService.prepare == null). */
    fun connect(candidates: List<VpnGateServer>) {
        connectJob?.cancel()
        if (candidates.isEmpty()) {
            _status.value = Status(Phase.FAILED, message = "Bu seçim için sunucu bulunamadı.")
            return
        }
        connectJob = scope.launch {
            val total = candidates.size
            for ((index, server) in candidates.withIndex()) {
                _status.value = Status(Phase.CONNECTING, server, index + 1, total, "Bağlanılıyor…")

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

                ovpnLevel.value = ConnectionStatus.LEVEL_START
                withContext(Dispatchers.IO) {
                    ProfileManager.setTemporaryProfile(appContext, profile)
                }
                VPNLaunchHelper.startOpenVpn(profile, appContext, "Reklamsız VPN", true)

                val result = withTimeoutOrNull(ATTEMPT_TIMEOUT_MS) {
                    ovpnLevel.first {
                        it == ConnectionStatus.LEVEL_CONNECTED || it == ConnectionStatus.LEVEL_AUTH_FAILED
                    }
                }
                if (result == ConnectionStatus.LEVEL_CONNECTED) {
                    saveLastServer(server)
                    _status.value = Status(Phase.CONNECTED, server, index + 1, total)
                    return@launch
                }
            }
            stopService()
            _status.value = Status(
                Phase.FAILED,
                message = "Denenen $total sunucunun hiçbirine bağlanılamadı. Başka bir ülke deneyin.",
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
