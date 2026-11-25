package io.github.onceberkant.reklamsizvpn.ui

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.onceberkant.reklamsizvpn.data.VpnGateRepository
import io.github.onceberkant.reklamsizvpn.data.VpnGateServer
import io.github.onceberkant.reklamsizvpn.vpn.VpnController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CountryItem(
    val code: String,
    val englishName: String,
    val serverCount: Int,
)

data class UiState(
    val servers: List<VpnGateServer> = emptyList(),
    val countries: List<CountryItem> = emptyList(),
    val selectedCountry: String = AUTO_COUNTRY,
    val loading: Boolean = false,
    val error: String? = null,
    val lastUpdated: Long = 0L,
)

const val AUTO_COUNTRY = "AUTO"

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = VpnGateRepository(app)
    private val prefs = app.getSharedPreferences("ayarlar", Context.MODE_PRIVATE)

    private val _ui = MutableStateFlow(
        UiState(selectedCountry = prefs.getString(KEY_COUNTRY, AUTO_COUNTRY) ?: AUTO_COUNTRY)
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val vpnStatus = VpnController.status
    val traffic = VpnController.traffic

    init {
        viewModelScope.launch {
            val cached = withContext(Dispatchers.IO) { repo.cached() }
            setServers(cached, repo.cacheTime())
            val age = System.currentTimeMillis() - repo.cacheTime()
            if (cached.isEmpty() || age > REFRESH_AFTER_MS) refresh()
        }
    }

    fun refresh() {
        if (_ui.value.loading) return
        viewModelScope.launch { refreshNow() }
    }

    private suspend fun refreshNow(): Boolean {
        _ui.update { it.copy(loading = true, error = null) }
        return try {
            val servers = repo.refresh()
            setServers(servers, System.currentTimeMillis())
            true
        } catch (e: Exception) {
            _ui.update {
                it.copy(error = "Sunucu listesi alınamadı (${e.message ?: e.javaClass.simpleName}).")
            }
            false
        } finally {
            _ui.update { it.copy(loading = false) }
        }
    }

    private fun setServers(servers: List<VpnGateServer>, time: Long) {
        val countries = servers.groupBy { it.countryCode }
            .map { (code, list) -> CountryItem(code, list.first().countryName, list.size) }
            .sortedWith(compareByDescending<CountryItem> { it.serverCount }.thenBy { it.code })
        _ui.update { it.copy(servers = servers, countries = countries, lastUpdated = time) }
    }

    fun selectCountry(code: String) {
        prefs.edit().putString(KEY_COUNTRY, code).apply()
        _ui.update { it.copy(selectedCountry = code) }
    }

    /** VPN izni alındıktan sonra çağrılır. */
    fun connect() {
        viewModelScope.launch {
            if (_ui.value.servers.isEmpty() && !refreshNow()) return@launch
            val state = _ui.value
            VpnController.connect(pickCandidates(state.servers, state.selectedCountry))
        }
    }

    fun disconnect() = VpnController.disconnect()

    private fun pickCandidates(servers: List<VpnGateServer>, country: String): List<VpnGateServer> {
        val pool = if (country == AUTO_COUNTRY) servers else servers.filter { it.countryCode == country }
        return pool
            .sortedWith(compareByDescending<VpnGateServer> { it.score }.thenBy { it.pingMs })
            .distinctBy { it.ip }
            .take(PROBE_POOL)
    }

    companion object {
        private const val KEY_COUNTRY = "ulke"
        private const val REFRESH_AFTER_MS = 60 * 60 * 1000L
        /** Erişim testine sokulacak en fazla sunucu sayısı */
        private const val PROBE_POOL = 30
    }
}
