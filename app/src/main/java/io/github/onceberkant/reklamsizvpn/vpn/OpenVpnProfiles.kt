package io.github.onceberkant.reklamsizvpn.vpn

import android.util.Base64
import de.blinkt.openvpn.VpnProfile
import de.blinkt.openvpn.core.ConfigParser
import io.github.onceberkant.reklamsizvpn.data.VpnGateServer
import java.io.StringReader

/** VPN Gate'in .ovpn yapılandırmasını OpenVPN for Android profiline çevirir. */
object OpenVpnProfiles {

    fun build(server: VpnGateServer): VpnProfile {
        val raw = String(Base64.decode(server.configBase64, Base64.DEFAULT), Charsets.UTF_8)
            .replace("\r\n", "\n")

        val parser = ConfigParser()
        parser.parseConfig(StringReader(raw))
        val profile = parser.convertProfile()

        profile.mName = "VPN Gate ${server.countryCode} ${server.ip}"

        // VPN Gate sunucuları kullanıcı adı/şifre istemez; isterse herkese açık değer "vpn"dir.
        profile.mUsername = "vpn"
        profile.mPassword = "vpn"

        // VPN Gate (SoftEther) sunucuları şifre müzakeresini (NCP) her zaman desteklemez.
        // Yapılandırmadaki eski şifreyi (genelde AES-128-CBC) yedek olarak ekliyoruz.
        val fallback = profile.mCipher?.takeIf { it.isNotBlank() } ?: "AES-128-CBC"
        profile.mDataCiphers = listOf("AES-256-GCM", "AES-128-GCM", "CHACHA20-POLY1305", fallback)
            .distinct()
            .joinToString(":")
        profile.mUseCustomConfig = true
        profile.mCustomConfigOptions = (profile.mCustomConfigOptions ?: "") +
            "\ndata-ciphers-fallback $fallback\n"

        // DNS sızıntısını önlemek için sabit, gizlilik odaklı DNS
        profile.mOverrideDNS = true
        profile.mDNS1 = "1.1.1.1"
        profile.mDNS2 = "9.9.9.9"

        return profile
    }
}
