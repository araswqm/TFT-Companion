package com.araswqm.tftcompanion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** İlk açılış: bildirim dinleyici izni açıklaması ve etkinleştirme ekranı. */
@Composable
fun OnboardingScreen(
    viewModel: AppViewModel,
) {
    var granted by remember { mutableStateOf(viewModel.isNotificationAccessGranted()) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "TFT Companion",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Şarkı kapaklarınızı ESP32 anahtarlık ekranına göndermek için "
                + "uygulamanın müzik bildirimlerini dinlemesi gerekir.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = "1. Ayarlar > Bildirimler açılacak\n"
                + "2. \"TFT Medya Dinleyici\"yi etkinleştir\n"
                + "3. Geri dön ve devam et",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(24.dp))
        OutlinedButton(
            onClick = { viewModel.openNotificationSettings(); granted = viewModel.isNotificationAccessGranted() },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (granted) "Bildirim izni aktif ✓" else "Bildirim iznini etkinleştir")
        }
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { viewModel.setOnboardingDone(); viewModel.startWatching() },
            enabled = granted,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Devam et")
        }
    }
}
