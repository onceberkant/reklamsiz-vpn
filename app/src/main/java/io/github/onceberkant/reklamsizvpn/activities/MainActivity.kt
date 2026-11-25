package io.github.onceberkant.reklamsizvpn.activities

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.blinkt.openvpn.core.VpnStatus
import io.github.onceberkant.reklamsizvpn.BuildConfig
import io.github.onceberkant.reklamsizvpn.CrashRecorder
import io.github.onceberkant.reklamsizvpn.ui.MainScreen
import io.github.onceberkant.reklamsizvpn.ui.MainViewModel
import io.github.onceberkant.reklamsizvpn.vpn.VpnController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private val vm: MainViewModel by viewModels()

    private val vpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                vm.connect()
            } else {
                toast("VPN izni verilmedi. Bağlanmak için izin gerekiyor.")
            }
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AppTheme {
                val ui by vm.ui.collectAsStateWithLifecycle()
                val vpn by vm.vpnStatus.collectAsStateWithLifecycle()
                val traffic by vm.traffic.collectAsStateWithLifecycle()
                val ranking by vm.ranking.collectAsStateWithLifecycle()
                MainScreen(
                    ui = ui,
                    vpn = vpn,
                    traffic = traffic,
                    ranking = ranking,
                    onToggle = ::onToggle,
                    onSelectCountry = ::onSelectCountry,
                    onRefresh = vm::refresh,
                    onMeasure = { vm.measure(force = true) },
                    onCopyLog = ::copyLog,
                )
            }
        }
    }

    private fun onToggle() {
        when (vm.vpnStatus.value.phase) {
            VpnController.Phase.CONNECTED,
            VpnController.Phase.CONNECTING,
            VpnController.Phase.RECONNECTING -> vm.disconnect()

            VpnController.Phase.IDLE,
            VpnController.Phase.FAILED -> startConnect()
        }
    }

    private fun onSelectCountry(code: String) {
        val changed = vm.ui.value.selectedCountry != code
        vm.selectCountry(code)
        // Bağlıyken ülke değişirse tek dokunuşla yeni ülkeye geç
        if (changed && vm.vpnStatus.value.phase == VpnController.Phase.CONNECTED) {
            startConnect()
        }
    }

    private fun startConnect() {
        askNotificationPermission()
        val intent = VpnService.prepare(this)
        if (intent == null) {
            vm.connect()
            return
        }
        try {
            vpnPermission.launch(intent)
        } catch (e: ActivityNotFoundException) {
            toast("Bu cihaz VPN desteklemiyor.")
        }
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun copyLog() {
        val format = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
        val log = VpnStatus.getlogbuffer().joinToString("\n") { item ->
            format.format(Date(item.logtime)) + "  " + item.getString(this)
        }.ifBlank { "Günlük boş." }
        val crash = CrashRecorder.read(this)
        val text = buildString {
            append("Reklamsız VPN ").append(BuildConfig.VERSION_NAME).append("\n\n")
            if (crash != null) append("=== SON ÇÖKME ===\n").append(crash).append("\n\n")
            append("=== BAĞLANTI GÜNLÜĞÜ ===\n").append(log)
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Reklamsız VPN günlüğü", text))
        toast("Günlük panoya kopyalandı")
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }
}

@Composable
private fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}
