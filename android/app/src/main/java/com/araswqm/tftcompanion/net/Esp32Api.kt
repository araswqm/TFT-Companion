package com.araswqm.tftcompanion.net

import android.net.Network
import android.util.Log
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * ESP32'ye giden HTTP istemcisi. Kritik nokta: istekler yalnızca
 * ESP32 ağına (Network) bağlı soketler üzerinden gönderilir — böylece
 * telefonun mobil interneti/mevcut Wi-Fi'ı etkilenmez.
 *
 * OkHttpClient, network.getSocketFactory() ile bind edilir; DNS'e gerek
 * yok çünkü ESP32'ye IP üzerinden ulaşılır.
 */
class Esp32Api(private val network: Network) {

    companion object {
        private const val TAG = "Esp32Api"
        private val MIME_JPEG = "image/jpeg".toMediaType()
        private val MIME_GIF = "image/gif".toMediaType()
        private val MIME_MJPEG = "video/x-mjpeg".toMediaType()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .socketFactory(network.getSocketFactory())
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    data class Esp32Status(
        val freeSpace: Long,
        val currentMedia: String,
        val uptime: Long,
    )

    /** GET /status — LittleFS boş alanı, mevcut medya türü ve uptime. */
    suspend fun getStatus(baseUrl: String): Esp32Status? = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/status").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "GET /status HTTP ${resp.code}")
                    return@use null
                }
                val json = JSONObject(resp.body?.string() ?: "{}")
                Esp32Status(
                    freeSpace = json.optLong("freeSpace", 0L),
                    currentMedia = json.optString("currentMedia", "none"),
                    uptime = json.optLong("uptime", 0L),
                ).also { Log.d(TAG, "GET /status -> $it") }
            }
        }.getOrElse { e ->
            Log.w(TAG, "GET /status başarısız: ${e.message}")
            null
        }
    }

    /**
     * POST /upload — multipart, alan adı "image". ESP32 uzantıya göre format
     * algılar (.jpg/.gif/.mjpg), o yüzden dosya adı uzantısı önemli.
     */
    suspend fun upload(baseUrl: String, bytes: ByteArray, fileName: String): String =
        withContext(Dispatchers.IO) {
            val mime = when {
                fileName.endsWith(".gif") -> MIME_GIF
                fileName.endsWith(".mjpg") || fileName.endsWith(".mjpeg") -> MIME_MJPEG
                else -> MIME_JPEG
            }
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("image", fileName, bytes.toRequestBody(mime))
                .build()
            val req = Request.Builder().url("$baseUrl/upload").post(body).build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty().trim()
                Log.d(TAG, "POST /upload HTTP ${resp.code} -> $text")
                if (!resp.isSuccessful) {
                    throw IOException("Upload başarısız (HTTP ${resp.code}): $text")
                }
                text
            }
        }

    /** GET / — sunucunun ayakta olup olmadığını kontrol et. */
    suspend fun ping(baseUrl: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val req = Request.Builder().url("$baseUrl/").get().build()
            client.newCall(req).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }
}
