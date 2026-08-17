package com.araswqm.tftcompanion.ui

import android.graphics.Bitmap
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Manuel gönderim önizlemesi: 128x128 kare, dosya boyutu ve Gönder butonu. */
@Composable
fun PreviewScreen(
    preview: Bitmap?,
    fileName: String,
    sizeKb: Int,
    working: Boolean,
    progressLabel: String?,
    onSend: () -> Unit,
    onBack: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Medya Önizleme", style = MaterialTheme.typography.headlineSmall)

        Spacer(Modifier.height(20.dp))

        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
            Box(
                modifier = Modifier
                    .padding(12.dp)
                    .size(128.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(androidx.compose.ui.graphics.Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "128x128 önizleme",
                        modifier = Modifier.size(128.dp),
                    )
                } else {
                    Text("🎬", fontSize = MaterialTheme.typography.headlineLarge.fontSize)
                }
            }
        }

        Spacer(Modifier.height(16.dp))
        Text("128×128 • $fileName", fontWeight = FontWeight.SemiBold)
        Text(
            "Tahmini boyut: $sizeKb KB",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            "LittleFS boş alanına göre JPEG kalitesi otomatik ayarlanır.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(20.dp))

        if (working) {
            CircularProgressIndicator()
            Spacer(Modifier.height(8.dp))
            Text(progressLabel ?: "Hazırlanıyor…")
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = onBack) { Text("Geri") }
                Button(onClick = onSend) { Text("ESP32'ye Gönder") }
            }
        }
    }
}
