package io.github.onceberkant.reklamsizvpn.ui

import java.util.Locale

private val turkish = Locale.forLanguageTag("tr-TR")

/** "JP" -> 🇯🇵 */
fun flagEmoji(code: String): String {
    if (code.length != 2 || !code.all { it in 'A'..'Z' }) return "🌐"
    val first = Character.toChars(0x1F1E6 + (code[0] - 'A'))
    val second = Character.toChars(0x1F1E6 + (code[1] - 'A'))
    return String(first) + String(second)
}

/** "JP" -> "Japonya" (bulunamazsa VPN Gate'in İngilizce adı) */
fun countryNameTr(code: String, fallback: String): String {
    val name = Locale.Builder().let {
        try {
            it.setRegion(code).build().getDisplayCountry(turkish)
        } catch (e: Exception) {
            ""
        }
    }
    return if (name.isBlank() || name.equals(code, ignoreCase = true)) fallback else name
}

fun formatMbps(mbps: Double): String =
    if (mbps >= 100) String.format(turkish, "%.0f Mbps", mbps)
    else String.format(turkish, "%.1f Mbps", mbps)

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val kb = bytes / 1024.0
    if (kb < 1024) return String.format(turkish, "%.0f KB", kb)
    val mb = kb / 1024.0
    if (mb < 1024) return String.format(turkish, "%.1f MB", mb)
    return String.format(turkish, "%.2f GB", mb / 1024.0)
}
