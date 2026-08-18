package com.araswqm.tftcompanion.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.araswqm.tftcompanion.data.MediaMode

/** Ana ekran: mod seçimi, şu an çalan medya, manuel gönderim, ESP32 ayarları. */
@Composable
fun MainScreen(
    state: AppViewModel.UiState,
    onModeChange: (MediaMode) -> Unit,
    onPickMedia: (android.net.Uri, String?) -> Unit,
    onStartWatching: () -> Unit,
    onSpinToggle: (Boolean) -> Unit,
    onSaveEsp32: (String, String, String, Int) -> Unit,
    onSaveNfc: (String, String) -> Unit,
    onOpenNls: () -> Unit,
    onConnect: () -> Unit,
    onEnterChargeMode: () -> Unit,
    isNlsGranted: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ModeSelector(state.mode, onModeChange)

        if (!isNlsGranted) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text("Bildirim dinleyici izni gerekli", fontWeight = FontWeight.Bold)
                    Text(
                        "Otomatik kapak gönderimi için izni açmanız gerekir. "
                            + "Manuel modda da çalışabilirsiniz.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = onOpenNls) { Text("İzni aç") }
                }
            }
        }

        // Bağlantı durumu
        ConnStatusCard(state, onConnect)

        when (state.mode) {
            MediaMode.AUTO -> AutoModeCard(state, onStartWatching, onSpinToggle)
            MediaMode.MANUAL -> ManualModeCard(onPickMedia)
        }

        Esp32SettingsCard(state, onSaveEsp32)

        NfcSettingsCard(state, onSaveNfc)

        ChargeModeCard(
            enabled = state.connState == AppViewModel.ConnState.CONNECTED,
            working = state.working,
            onEnterChargeMode = onEnterChargeMode,
        )
    }
}

@Composable
private fun ModeSelector(current: MediaMode, onModeChange: (MediaMode) -> Unit) {
    val options = listOf(
        MediaMode.AUTO to "Otomatik Şarkı Kapağı",
        MediaMode.MANUAL to "Manuel Medya",
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (mode, label) ->
            SegmentedButton(
                selected = current == mode,
                onClick = { onModeChange(mode) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun ConnStatusCard(state: AppViewModel.UiState, onConnect: () -> Unit) {
    val (color, text) = when (state.connState) {
        AppViewModel.ConnState.IDLE -> Color(0xFF8A8A9A) to "Bağlantı yok"
        AppViewModel.ConnState.CONNECTING -> Color(0xFFFFA500) to "ESP32'ye bağlanılıyor…"
        AppViewModel.ConnState.CONNECTED -> Color(0xFF4CAF50) to "ESP32 bağlı"
        AppViewModel.ConnState.ERROR -> Color(0xFFE94560) to "Bağlantı hatası"
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(color.copy(alpha = 0.15f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(color),
            )
            Spacer(Modifier.size(8.dp))
            Text(text, color = MaterialTheme.colorScheme.onSurface)
            Spacer(Modifier.weight(1f))
            if (state.working) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            }
        }

        // Bağlı değilken kullanıcıya elle bağlanma imkânı + internet ipucu.
        // "Bağlan" uygulama üzerinden WifiNetworkSpecifier ile bağlanır (telefonun
        // interneti korunur); Ayarlar'dan elle bağlanırsa internet kesilir.
        if (state.connState == AppViewModel.ConnState.IDLE ||
            state.connState == AppViewModel.ConnState.ERROR
        ) {
            Button(
                onClick = onConnect,
                enabled = !state.working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.connState == AppViewModel.ConnState.ERROR) "Tekrar Bağlan" else "ESP32'ye Bağlan")
            }
            Text(
                "Ayarlar'dan ESP32 ağına elle bağlanmayın — telefonun interneti kesilir. "
                    + "Bu butonla bağlandığınızda internetiniz korunur.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AutoModeCard(
    state: AppViewModel.UiState,
    onStartWatching: () -> Unit,
    onSpinToggle: (Boolean) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Şu An Çalan", style = MaterialTheme.typography.titleMedium)
            val np = state.nowPlaying
            if (np == null) {
                Text(
                    "Henüz bir şey çalmıyor. Müzik çalarken buraya kapak görünür.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val art = np.albumArt
                    if (art != null) {
                        Image(
                            bitmap = art.asImageBitmap(),
                            contentDescription = null,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center,
                        ) { Text("🎵") }
                    }
                    Spacer(Modifier.size(12.dp))
                    Column {
                        Text(np.title, fontWeight = FontWeight.SemiBold)
                        Text(np.artist, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if (state.progressLabel != null) {
                Text(state.progressLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            // Plak animasyonu açıkken dönen kapak + overlay (MJPEG), kapalıyken düz tek kare JPEG
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Plak animasyonu",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = state.settings.spinEnabled,
                    onCheckedChange = onSpinToggle,
                )
            }
            OutlinedButton(onClick = onStartWatching) {
                Text("İzlemeyi başlat / yeniden dene")
            }
        }
    }
}

@Composable
private fun ManualModeCard(onPickMedia: (android.net.Uri, String?) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            onPickMedia(uri, type)
        }
    }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Manuel Medya", style = MaterialTheme.typography.titleMedium)
            Text(
                "Görüntü (JPEG/PNG), GIF veya video (MP4 vb.) seçin. "
                    + "ESP32 ekranına otomatik 128×128 formata dönüştürülür.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { picker.launch("*/*") },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Medya Seç ve Gönder")
            }
        }
    }
}

@Composable
private fun Esp32SettingsCard(
    state: AppViewModel.UiState,
    onSaveEsp32: (String, String, String, Int) -> Unit,
) {
    var ssid by remember(state.settings) { mutableStateOf(state.settings.ssid) }
    var password by remember(state.settings) { mutableStateOf(state.settings.password) }
    var ip by remember(state.settings) { mutableStateOf(state.settings.ip) }
    var port by remember(state.settings) { mutableStateOf(state.settings.port.toString()) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("ESP32 Ayarları", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = ssid,
                onValueChange = { ssid = it },
                label = { Text("SSID") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Şifre") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = ip,
                onValueChange = { ip = it },
                label = { Text("IP") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = port,
                onValueChange = { port = it.filter(Char::isDigit) },
                label = { Text("Port") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveEsp32(ssid, password, ip, port.toIntOrNull() ?: 80) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Kaydet")
            }
        }
    }
}

@Composable
private fun NfcSettingsCard(
    state: AppViewModel.UiState,
    onSaveNfc: (String, String) -> Unit,
) {
    var url by remember(state.settings) { mutableStateOf(state.settings.nfcServerUrl) }
    var token by remember(state.settings) { mutableStateOf(state.settings.nfcServerToken) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("NFC Tag — Şu An Çalan Şarkı", style = MaterialTheme.typography.titleMedium)
            Text(
                "Otomatik modda çalan şarkı, buradaki adrese internetten bildirilir. "
                    + "NFC tag'i okutan herkes şarkının YouTube Music sayfasını görür. "
                    + "Sayfa adresi tag'e (ör. NFC Tools ile) yazılır; URL aynen şöyle olmalı: "
                    + "https://<subdomain>/current",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("Sunucu adresi (…/current)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token (VINYLTAG_TOKEN)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onSaveNfc(url, token) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Kaydet")
            }
        }
    }
}

@Composable
private fun ChargeModeCard(
    enabled: Boolean,
    working: Boolean,
    onEnterChargeMode: () -> Unit,
) {
    var confirmOpen by remember { mutableStateOf(false) }
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Güç", style = MaterialTheme.typography.titleMedium)
            Text(
                "Şarj modu: ESP32'nin tüm fonksiyonlarını kapatır (ekran, Wi-Fi). " +
                    "Cihaz, güç kesilip geri gelene kadar (switch'i kapatıp açana kadar) " +
                    "kapalı kalır. Şarj etmeden önce buradan tetikleyin.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = { confirmOpen = true },
                enabled = enabled && !working,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Şarj Moduna Geç")
            }
        }
    }
    if (confirmOpen) {
        AlertDialog(
            onDismissRequest = { confirmOpen = false },
            title = { Text("Şarj moduna geçilsin mi?") },
            text = {
                Text(
                    "ESP32 kapatılacak ve şarj bitene kadar kapalı kalacak. " +
                        "Şarj işlemi bittikten sonra anahtarlığın switch'ini kapatıp " +
                        "tekrar açın.",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        confirmOpen = false
                        onEnterChargeMode()
                    },
                ) { Text("Evet, kapat") }
            },
            dismissButton = {
                TextButton(onClick = { confirmOpen = false }) { Text("Vazgeç") }
            },
        )
    }
}
