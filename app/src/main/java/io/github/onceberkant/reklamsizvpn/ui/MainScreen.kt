package io.github.onceberkant.reklamsizvpn.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.onceberkant.reklamsizvpn.data.VpnGateServer
import io.github.onceberkant.reklamsizvpn.vpn.VpnController.Phase
import io.github.onceberkant.reklamsizvpn.vpn.VpnController.Status
import io.github.onceberkant.reklamsizvpn.vpn.VpnController.Traffic

private val Green = Color(0xFF1E8E4E)
private val Amber = Color(0xFFE0A100)

@Composable
fun MainScreen(
    ui: UiState,
    vpn: Status,
    traffic: Traffic,
    onToggle: () -> Unit,
    onSelectCountry: (String) -> Unit,
    onRefresh: () -> Unit,
    onCopyLog: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = 16.dp),
        ) {
            Header(loading = ui.loading, onRefresh = onRefresh)

            ConnectButton(phase = vpn.phase, onClick = onToggle)

            StatusText(vpn = vpn, traffic = traffic, error = ui.error)

            Spacer(Modifier.height(12.dp))
            Text(
                "Ülke seçin",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(6.dp))

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = AUTO_COUNTRY) {
                    CountryRow(
                        flag = "⚡",
                        name = "En iyi sunucu (otomatik)",
                        detail = "${ui.servers.size} sunucu",
                        selected = ui.selectedCountry == AUTO_COUNTRY,
                        onClick = { onSelectCountry(AUTO_COUNTRY) },
                    )
                }
                items(ui.countries, key = { it.code }) { country ->
                    CountryRow(
                        flag = flagEmoji(country.code),
                        name = countryNameTr(country.code, country.englishName),
                        detail = "${country.serverCount} sunucu",
                        selected = ui.selectedCountry == country.code,
                        onClick = { onSelectCountry(country.code) },
                    )
                }
                if (ui.countries.isEmpty() && !ui.loading) {
                    item {
                        Text(
                            "Liste henüz yüklenmedi. Sağ üstten yenileyin.",
                            modifier = Modifier.padding(8.dp),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Footer(onCopyLog = onCopyLog)
        }
    }
}

@Composable
private fun Header(loading: Boolean, onRefresh: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Reklamsız VPN",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f),
        )
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(8.dp))
        }
        TextButton(onClick = onRefresh, enabled = !loading) { Text("Yenile") }
    }
}

@Composable
private fun ConnectButton(phase: Phase, onClick: () -> Unit) {
    val (label, color) = when (phase) {
        Phase.CONNECTED -> "BAĞLANTIYI\nKES" to Green
        Phase.CONNECTING, Phase.RECONNECTING -> "İPTAL" to Amber
        Phase.IDLE, Phase.FAILED -> "BAĞLAN" to MaterialTheme.colorScheme.primary
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (phase == Phase.CONNECTING || phase == Phase.RECONNECTING) {
            CircularProgressIndicator(
                modifier = Modifier.size(196.dp),
                color = Amber,
                strokeWidth = 4.dp,
            )
        }
        Button(
            onClick = onClick,
            modifier = Modifier.size(172.dp),
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = color, contentColor = Color.White),
        ) {
            Text(
                label,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp,
            )
        }
    }
}

@Composable
private fun StatusText(vpn: Status, traffic: Traffic, error: String?) {
    val serverLine = vpn.server?.let { describe(it) }
    val (title, detail) = when (vpn.phase) {
        Phase.IDLE -> "Bağlı değil" to vpn.message.ifBlank { null }
        Phase.CONNECTING ->
            if (vpn.attempt == 0) vpn.message to null
            else "Bağlanılıyor (${vpn.attempt}/${vpn.total})" to serverLine
        Phase.RECONNECTING -> "Yeniden bağlanılıyor…" to serverLine
        Phase.CONNECTED -> "Bağlı" to listOfNotNull(
            serverLine,
            "↓ ${formatBytes(traffic.inBytes)}   ↑ ${formatBytes(traffic.outBytes)}",
        ).joinToString("\n")
        Phase.FAILED -> "Bağlanılamadı" to vpn.message
    }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = when (vpn.phase) {
                Phase.CONNECTED -> Green
                Phase.FAILED -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onBackground
            },
        )
        if (!detail.isNullOrBlank()) {
            Text(
                detail,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!error.isNullOrBlank()) {
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

private fun describe(server: VpnGateServer): String {
    val name = countryNameTr(server.countryCode, server.countryName)
    return "${flagEmoji(server.countryCode)} $name · ${server.ip}"
}

@Composable
private fun CountryRow(
    flag: String,
    name: String,
    detail: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer
    else MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(flag, fontSize = 22.sp)
        Spacer(Modifier.width(12.dp))
        Text(
            name,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f),
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text("✓", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Footer(onCopyLog: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Sunucular VPN Gate (Tsukuba Üniversitesi) gönüllüleri tarafından işletilir. " +
                "Bağlantı şifrelenir; ancak sunucu sahibi HTTPS olmayan trafiği görebilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCopyLog) { Text("Bağlantı günlüğünü kopyala") }
    }
}
