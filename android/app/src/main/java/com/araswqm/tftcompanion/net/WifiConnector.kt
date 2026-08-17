package com.araswqm.tftcompanion.net

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
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
     * Eşleşme sırası:
     *  1. NetworkCapabilities.getSSID() ile SSID karşılaştırması — API 29+'da
     *     konum izni gerektirmez (WifiManager.connectionInfo.ssid'den farklı
     *     olarak "<unknown ssid" kısıtlamasına takılmaz).
     *  2. Ağ üzerindeki IP 192.168.4.x ise ESP32 softAP ağı say (firmware'in
     *     softAPConfig'i 192.168.4.1/24 kullanır). Bazı cihazlar SSID alanını
     *     boş bırakabilir; bu durumda IP kontrolüyle emin olunur.
     */
    private fun findWifiNetwork(ssid: String): Network? {
        return cm.allNetworks.firstOrNull { net ->
            val caps = cm.getNetworkCapabilities(net)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) {
                return@firstOrNull false
            }

            // 1) SSID (konum izni gerektirmez, API 29+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // getSSID() akronim getter olduğundan Kotlin property adı
                // belirsizdir (ne 'ssid' ne 'SSID' derlenir); metodu doğrudan çağır.
                val capSsid = caps.getSSID()?.trim('"')?.trim()
                if (!capSsid.isNullOrEmpty()) {
                    return@firstOrNull capSsid.equals(ssid, ignoreCase = true)
                }
            }

            // 2) ESP32 softAP alt ağı (192.168.4.x)
            val onEspSubnet = cm.getLinkProperties(net)?.linkAddresses.orEmpty().any { a ->
                val addr = a.address
                addr is java.net.Inet4Address &&
                    addr.address.size == 4 &&
                    addr.address[0] == 192.toByte() &&
                    addr.address[1] == 168.toByte() &&
                    addr.address[2] == 4.toByte()
            }
            onEspSubnet
        }
    }
}
