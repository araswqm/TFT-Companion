package com.araswqm.tftcompanion.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi

/**
 * ESP32 softAP'ye programatik bağlantı.
 *
 * API 29+ : WifiNetworkSpecifier + ConnectivityManager.requestNetwork ile
 *           telefona interneti kaybettirmeden yalnızca ESP32 ağına bağlanır.
 *           Dikkat: ESP32 AP yalnızca yerel ağdır (internet yok), bu yüzden
 *           isteğe NET_CAPABILITY_INTERNET EKLENMEZ.
 * API <29 : WifiNetworkSpecifier desteklenmez; kullanıcı Ayarlar üzerinden
 *           elle bağlanır (onOpenWifiSettings geri çağrısı).
 *
 * Kullanıcının mobil interneti asla kapatılmaz; ESP32'ye giden soketler
 * seçilen Network'e bağlanır (bkz. Esp32Api).
 */
class WifiConnector(context: Context) {

    companion object {
        private const val TAG = "WifiConnector"
    }

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var requestId = 0
    private var activeCallback: ConnectivityManager.NetworkCallback? = null

    fun connect(
        ssid: String,
        password: String,
        onConnected: (Network) -> Unit,
        onUnavailable: (String) -> Unit,
        onOpenWifiSettings: () -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "API < 29, WifiNetworkSpecifier desteklenmiyor")
            mainHandler.post(onOpenWifiSettings)
            return
        }

        // Boş değerlerle builder çağırmak IllegalArgumentException fırlatır;
        // güvenli varsayılanlara düş (firmware softAP varsayılanları).
        val safeSsid = ssid.trim().ifEmpty { "ESP32-TFT" }
        val safePassword = password.trim().ifEmpty { "12345678" }

        // Önce AP'nin görünür olduğundan emin olalım; değilse hızlıca bildir
        if (!isApVisible(safeSsid)) {
            Log.d(TAG, "AP '$safeSsid' yakında yok; ağ isteği yine de deneniyor (tarama gecikmeli)")
        }

        disconnect()
        val id = ++requestId

        // Specifier kurulumu izin/argüman hatasında fırlatabilir (ör. eksik
        // NEARBY_WIFI_DEVICES -> SecurityException). Ana iş parçacığını asla
        // çöktürmemeli; hata kullanıcıya gösterilir.
        val request = try {
            val specifier = WifiNetworkSpecifier.Builder().apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setSsid(safeSsid)
                } else {
                    // API 29: setSsidPattern ile tam eşleşme
                    setSsidPattern(android.os.PatternMatcher(safeSsid, android.os.PatternMatcher.PATTERN_LITERAL))
                }
                if (safePassword.isNotEmpty()) setWpa2Passphrase(safePassword)
            }.build()

            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .setNetworkSpecifier(specifier)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Wi-Fi ağ isteği oluşturulamadı: ${e.message}")
            mainHandler.post {
                onUnavailable("ESP32 ağına bağlanılamadı (izin engellenmiş olabilir): ${e.message}")
            }
            return
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (id != requestId) return
                Log.d(TAG, "ESP32 ağına bağlanıldı: $network")
                mainHandler.post { onConnected(network) }
            }

            override fun onUnavailable() {
                if (id != requestId) return
                Log.w(TAG, "ESP32 ağına bağlanılamadı (timeout/unavailable)")
                mainHandler.post { onUnavailable("ESP32 ağı bulunamadı: $safeSsid. Cihazın açık ve yakında olduğundan emin olun.") }
            }

            override fun onLost(network: Network) {
                if (id != requestId) return
                Log.w(TAG, "ESP32 ağı bağlantısı kesildi")
                mainHandler.post { onUnavailable("ESP32 bağlantısı koptu") }
            }
        }

        Log.d(TAG, "requestNetwork çağrılıyor: $safeSsid")
        runCatching {
            cm.requestNetwork(request, callback, mainHandler)
            activeCallback = callback
        }.onFailure { e ->
            Log.e(TAG, "requestNetwork başarısız: ${e.message}")
            mainHandler.post {
                onUnavailable("ESP32 ağına bağlanılamadı (izin engellenmiş olabilir): ${e.message}")
            }
        }
    }

    fun disconnect() {
        requestId++ // eski callback'leri geçersiz kıl
        activeCallback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
        }
        activeCallback = null
    }

    private fun isApVisible(ssid: String): Boolean {
        return runCatching {
            val wm = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifi = wm.connectionInfo
            wifi.ssid?.replace("\"", "") == ssid && wifi.networkId != -1
        }.getOrDefault(false)
    }
}
