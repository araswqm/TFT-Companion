package com.araswqm.tftcompanion.data

// Uygulamanın kalıcı ayarları (DataStore üzerinde tutulur).
data class AppSettings(
    val mode: MediaMode = MediaMode.AUTO,          // Otomatik Şarkı Kapağı | Manuel Medya
    val ssid: String = "VinylTag",                 // ESP32 softAP SSID
    val password: String = "12345678",             // ESP32 softAP şifresi
    val ip: String = "192.168.4.1",                // ESP32 AP ağ geçidi
    val port: Int = 80,
    val darkTheme: Boolean = true,
    val spinEnabled: Boolean = true,          // Otomatik modda plak animasyonu mu, düz kapak mı gönderilsin
    // NFC tag'e yazılan "şu an çalan şarkı" sayfası (Wispbyte'da çalışan vinyltag.py).
    // Boş ise push devre dışı. Token VINYLTAG_TOKEN ile aynı olmalı.
    val nfcServerUrl: String = "",
    val nfcServerToken: String = "",
) {
    val baseUrl: String get() = "http://$ip:$port"
}

enum class MediaMode { AUTO, MANUAL }
