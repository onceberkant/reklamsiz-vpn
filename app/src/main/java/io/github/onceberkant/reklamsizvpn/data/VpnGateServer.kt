package io.github.onceberkant.reklamsizvpn.data

/** VPN Gate açık listesindeki tek bir gönüllü sunucu. */
data class VpnGateServer(
    val hostName: String,
    val ip: String,
    val score: Long,
    val pingMs: Int,
    val speedBps: Long,
    val countryName: String,
    val countryCode: String,
    val sessions: Int,
    /** Base64 kodlu .ovpn dosyası */
    val configBase64: String,
) {
    val speedMbps: Double get() = speedBps / 1_000_000.0
}

/**
 * https://www.vpngate.net/api/iphone/ CSV çıktısını ayrıştırır.
 *
 * Biçim:
 *   *vpn_servers
 *   #HostName,IP,Score,Ping,Speed,CountryLong,CountryShort,NumVpnSessions,Uptime,
 *    TotalUsers,TotalTraffic,LogType,Operator,Message,OpenVPN_ConfigData_Base64
 *   ...satırlar...
 *   *
 *
 * Operator / Message alanları virgül içerebildiği için ilk 8 alan baştan,
 * yapılandırma ise sondan okunur.
 */
object VpnGateParser {

    fun parse(csv: String): List<VpnGateServer> {
        val result = ArrayList<VpnGateServer>()
        for (raw in csv.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("*") || line.startsWith("#")) continue
            val parts = line.split(',')
            if (parts.size < 15) continue

            val config = parts.last().trim()
            if (config.length < 100) continue

            val code = parts[6].trim().uppercase()
            if (code.length != 2) continue

            result += VpnGateServer(
                hostName = parts[0].trim(),
                ip = parts[1].trim(),
                score = parts[2].trim().toLongOrNull() ?: 0L,
                pingMs = parts[3].trim().toIntOrNull() ?: -1,
                speedBps = parts[4].trim().toLongOrNull() ?: 0L,
                countryName = parts[5].trim(),
                countryCode = code,
                sessions = parts[7].trim().toIntOrNull() ?: 0,
                configBase64 = config,
            )
        }
        return result
    }
}
