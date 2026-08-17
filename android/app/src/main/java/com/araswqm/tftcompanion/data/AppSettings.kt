package com.araswqm.tftcompanion.data

// Uygulamanın kalıcı ayarları (DataStore üzerinde tutulur).
data class AppSettings(
    val mode: MediaMode = MediaMode.AUTO,          // Otomatik Şarkı Kapağı | Manuel Medya
    val ssid: String = "ESP32-TFT",                // ESP32 softAP SSID
    val password: String = "12345678",             // ESP32 softAP şifresi
    val ip: String = "192.168.4.1",                // ESP32 AP ağ geçidi
    val port: Int = 80,
    val darkTheme: Boolean = true,
) {
    val baseUrl: String get() = "http://$ip:$port"
}

enum class MediaMode { AUTO, MANUAL }
