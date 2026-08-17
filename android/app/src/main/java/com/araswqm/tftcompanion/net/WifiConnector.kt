package com.araswqm.tftcompanion.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
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

        // Kullanıcı ESP32 ağına Ayarlar üzerinden elle bağlandıysa
        // WifiNetworkSpecifier KULLANILMAZ: sistem zaten bağlı olan bir ağı
        // specifier üzerinden yeniden tahsis edemez ve istek onUnavailable'a
        // düşer (uygulama "ağ bulunamadı" hatası verir). Bu durumda telefonun
        // zaten üzerinde olduğu Wi-Fi ağını (ESP32) doğrudan kullan.
        findWifiNetwork(safeSsid)?.let { wifiNet ->
            Log.d(TAG, "'$safeSsid' ağına zaten bağlı; mevcut Wi-Fi ağı kullanılıyor: $wifiNet")
            mainHandler.post { onConnected(wifiNet) }
            return
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

    /**
     * Şu an bağlı olan Wi-Fi ağlarından ESP32'ye ait olanı bulur.
     *
     * NOT: NetworkCapabilities.getSSID() Android'de gizli (hidden) API'dir —
     * public SDK'da yoktur, o yüzden SSID oradan okunamaz. Eşleşme sırası:
     *  1. Ağ üzerindeki IP 192.168.4.x ise ESP32 softAP ağı say — firmware'in
     *     softAPConfig'i 192.168.4.1/24 kullanır ve bu, konum izni gerektirmeyen
     *     kesin bir işarettir.
     *  2. WifiManager.connectionInfo.ssid — Android 10+ konum izni olmadan
     *     "<unknown ssid" döndürür; yalnızca gerçek değer geldiğinde karşılaştır
     *     (izin verilmişse). İzinsiz durumda 1. adım zaten yeterli.
     */
    private fun findWifiNetwork(ssid: String): Network? {
        // Android 10+ konum izni olmadan "<unknown ssid" döner; gerçek değer
        // geldiğinde SSID karşılaştırması yapılır. runCatching: OEM sapmaları
        // ve izin istisnalarına karşı ana iş parçacığını çöktürme.
        val currentSsid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                (appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .connectionInfo.getSSID()?.trim('"')?.trim()
            }.getOrNull()
        } else {
            null
        }

        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return@firstOrNull false
            }

            // 1) ESP32 softAP alt ağı (192.168.4.x)
            val onEspSubnet = cm.getLinkProperties(net)?.linkAddresses.orEmpty().any { a ->
                val addr = a.address
                addr is java.net.Inet4Address &&
                    addr.address.size == 4 &&
                    addr.address[0] == 192.toByte() &&
                    addr.address[1] == 168.toByte() &&
                    addr.address[2] == 4.toByte()
            }
            if (onEspSubnet) return@firstOrNull true

            // 2) Yedek SSID eşleşmesi (konum izni varsa gerçek değer gelir)
            if (!currentSsid.isNullOrEmpty() && !currentSsid.startsWith("<unknown", ignoreCase = true)) {
                return@firstOrNull currentSsid.equals(ssid, ignoreCase = true)
            }

            false
        }
    }
}
