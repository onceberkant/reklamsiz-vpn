package io.github.onceberkant.reklamsizvpn

import android.app.Application
import android.os.Build
import de.blinkt.openvpn.core.ICSOpenVPNApplication
import io.github.onceberkant.reklamsizvpn.vpn.VpnController
import java.io.File

/**
 * OpenVPN çekirdeğinin uygulama sınıfını genişletir (bildirim kanalları ve
 * :openvpn süreciyle durum köprüsü orada kuruluyor).
 */
class VpnApp : ICSOpenVPNApplication() {

    override fun onCreate() {
        super.onCreate()
        // onCreate hem ana süreçte hem ":openvpn" sürecinde çalışır
        if (currentProcessName() == packageName) {
            VpnController.init(this)
        }
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Application.getProcessName()
        } else {
            try {
                File("/proc/self/cmdline").readText().trim { it <= ' ' || it == '\u0000' }
            } catch (e: Exception) {
                packageName
            }
        }
}
