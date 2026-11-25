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
import io.github.onceberkant.reklamsizvpn.vpn.ServerProbe
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
    ranking: ServerProbe.Ranking?,
    onToggle: () -> Unit,
    onSelectCountry: (String) -> Unit,
    onRefresh: () -> Unit,
    onMeasure: () -> Unit,
    onCopyLog: () -> Unit,
) {
    val tunnelBusy = vpn.phase == Phase.CONNECTED || vpn.phase == Phase.CONNECTING ||
        vpn.phase == Phase.RECONNECTING
    val selectionName = if (ui.selectedCountry == AUTO_COUNTRY) "Otomatik"
    else countryNameTr(ui.selectedCountry, ui.countries.firstOrNull { it.code == ui.selectedCountry }?.englishName ?: ui.selectedCountry)
    val bestItems = ranking?.takeIf { it.key == ui.selectedCountry }?.items.orEmpty()

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

            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(key = "best_header") {
                    BestHeader(
                        selectionName = selectionName,
                        measuring = ui.measuring || (vpn.phase == Phase.CONNECTING && vpn.attempt == 0),
                        canMeasure = !tunnelBusy && !ui.measuring,
                        onMeasure = onMeasure,
                    )
                }
                if (bestItems.isEmpty()) {
                    item(key = "best_empty") {
                        Text(
                            when {
                                ui.measuring -> "Sunucular ölçülüyor…"
                                tunnelBusy -> "Ölçüm VPN kapalıyken yapılır."
                                else -> "Henüz ölçüm yok. \"Ölç\"e basın."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp),
                        )
                    }
                } else {
                    items(bestItems.size, key = { "best_" + bestItems[it].server.ip }) { i ->
                        BestRow(
                            rank = i + 1,
                            quality = bestItems[i],
                            active = vpn.server?.ip == bestItems[i].server.ip &&
                                vpn.phase != Phase.IDLE && vpn.phase != Phase.FAILED,
                        )
                    }
                }
                item(key = "country_header") {
                    Text(
                        "Ülke seçin",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
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
private fun BestHeader(
    selectionName: String,
    measuring: Boolean,
    canMeasure: Boolean,
    onMeasure: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "En iyi 6 sunucu · $selectionName",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Sıra: kayıp → ping + hız. Bağlan bu sırayla dener.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (measuring) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        } else {
            TextButton(onClick = onMeasure, enabled = canMeasure) { Text("Ölç") }
        }
    }
}

@Composable
private fun BestRow(rank: Int, quality: ServerProbe.Quality, active: Boolean) {
    val s = quality.server
    val bg = if (active) Green.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant
    val lossColor = if (quality.lossPercent == 0) Green else Amber
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$rank",
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(18.dp),
        )
        Text(flagEmoji(s.countryCode), fontSize = 20.sp)
        Spacer(Modifier.width(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                countryNameTr(s.countryCode, s.countryName) + if (active) "  · bağlı" else "",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                s.ip,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "${quality.pingMs} ms · ${formatMbps(s.speedMbps)}",
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "%${quality.lossPercent} kayıp",
                style = MaterialTheme.typography.bodySmall,
                color = lossColor,
            )
        }
    }
}

@Composable
private fun Footer(onCopyLog: () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            "Ping ve kayıp bu cihazdan 5 bağlantı denemesiyle ölçülür; hız VPN Gate'in " +
                "yayınladığı sunucu hızıdır. Sunucular VPN Gate (Tsukuba Üniversitesi) " +
                "gönüllüleri tarafından işletilir; sunucu sahibi HTTPS olmayan trafiği görebilir.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCopyLog) { Text("Bağlantı günlüğünü kopyala") }
    }
}
