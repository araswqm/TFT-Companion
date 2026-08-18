package com.araswqm.tftcompanion.net

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * NFC sunucusuna (Wispbyte'da bot ile aynı süreçte çalışan "şu an çalan şarkı"
 * aiohttp servisi) giden HTTP istemcisi.
 *
 * Esp32Api'den FARKI: bu istekler İNTERNET üzerinden gider, bu yüzden soketler
 * ESP32 ağına bind edilmez — normal (varsayılan network'ü kullanan) OkHttpClient.
 * WifiNetworkSpecifier ile ESP32'ye bağlıyken telefonun interneti (mobil veri)
 * açık kalır, dolayısıyla push çalışır. ESP32'ye elle bağlanılırsa internet
 * kesik olabilir; o durumda istek başarısız olur ve yalnızca loglanır —
 * best-effort'tur, hiçbir zaman hata olarak kullanıcıya gösterilmez.
 *
 * Sunucu protokolü (vinyltag.py):
 *   POST /current   Authorization: Bearer <token>   {"title","artist"}
 *   - title boş string ise mevcut şarkı temizlenir (müzik durdu).
 */
class NfcServerApi {

    private companion object {
        private const val TAG = "NfcServerApi"
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * POST /current — şarkıyı sunucuya bildir. Başlık boşsa sunucudaki mevcut
     * şarkı temizlenir. Başarı/başarısızlık Boolean döner; arayan hata göstermez.
     */
    suspend fun pushNowPlaying(baseUrl: String, token: String, title: String, artist: String): Boolean =
        withContext(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject()
                    .put("title", title)
                    .put("artist", artist)
                    .toString()
                val req = Request.Builder()
                    .url(normalizeEndpoint(baseUrl))
                    .header("Authorization", "Bearer $token")
                    .post(payload.toRequestBody(JSON))
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "POST /current HTTP ${resp.code}")
                        return@use false
                    }
                    true
                }
            }.getOrElse { e ->
                Log.w(TAG, "NFC sunucusuna gönderim başarısız: ${e.message}")
                false
            }
        }

    /**
     * Kullanıcı ister https://subdomain/current ister https://subdomain girsin;
     * adresi "/current" ile biten forma indirger (sonda / varsa temizlenir).
     */
    private fun normalizeEndpoint(url: String): String {
        var u = url.trim()
        if (u.endsWith("/")) u = u.dropLast(1)
        return if (u.endsWith("/current")) u else "$u/current"
    }
}
