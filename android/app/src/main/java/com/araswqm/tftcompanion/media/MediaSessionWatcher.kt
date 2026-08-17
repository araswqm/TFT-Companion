package com.araswqm.tftcompanion.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaController
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Sistem çapında "şu an çalan" medyayı izler.
 *
 * Birincil kaynak: MediaSessionManager.getActiveSessions() + MediaController.Callback.
 * Uygulamalar metadata'yı güncelledikçe onMetadataChanged tetiklenir; en son aktif
 * STATE_PLAYING oturumu seçilir. Albüm kapağı METADATA_KEY_ALBUM_ART (Bitmap) veya
 * METADATA_KEY_ALBUM_ART_URI (content/file/http URI) üzerinden çekilir.
 */
class MediaSessionWatcher(context: Context) {

    companion object {
        private const val TAG = "MediaSessionWatcher"
    }

    private val appContext = context.applicationContext
    private val msm =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val attached = mutableMapOf<MediaController, ControllerCallback>()

    private var sessionsListener: MediaSessionManager.OnActiveSessionsChangedListener? = null

    @Volatile
    private var running = false

    fun start() {
        Log.d(TAG, "start() - medya izleme başlıyor")
        running = true
        sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener {
            Log.d(TAG, "Aktif oturum listesi değişti, yeniden değerlendiriliyor")
            if (running) refresh()
        }
        msm.addOnActiveSessionsChangedListener(sessionsListener!!, null)
        refresh()
    }

    fun stop() {
        Log.d(TAG, "stop() - medya izleme durduruluyor")
        running = false
        sessionsListener?.let { msm.removeOnActiveSessionsChangedListener(it) }
        sessionsListener = null
        detachAll()
    }

    private fun refresh() {
        detachAll()
        val sessions = msm.getActiveSessions(null)
        if (sessions.isEmpty()) {
            Log.d(TAG, "Hiç aktif medya oturumu yok")
            return
        }
        Log.d(TAG, "${sessions.size} aktif oturum bulundu")

        // Öncelik: STATE_PLAYING olan, son aktif olan (listenin sonundaki) oturum.
        val playing = sessions.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val chosen = (playing.ifEmpty { sessions }).lastOrNull() ?: return
        Log.d(TAG, "Seçilen oturum: ${chosen.packageName} (state=${chosen.playbackState?.state})")
        attach(chosen)
    }

    private fun attach(controller: MediaController) {
        if (attached.containsKey(controller)) return
        val callback = ControllerCallback(controller) { md, state, changedPlayback ->
            emit(controller, md, state, changedPlayback)
        }
        controller.registerCallback(callback, mainHandler)
        attached[controller] = callback
        // Hemen mevcut metadata ile ilk güncellemeyi yap
        emit(controller, controller.metadata, controller.playbackState?.state, false)
    }

    private fun detachAll() {
        attached.forEach { (controller, cb) ->
            runCatching { controller.unregisterCallback(cb) }
        }
        attached.clear()
    }

    private fun emit(
        controller: MediaController,
        md: MediaMetadata?,
        state: Int?,
        playbackChanged: Boolean,
    ) {
        if (!running) return
        if (state != null && state != PlaybackState.STATE_PLAYING) {
            Log.d(TAG, "Oturum ${controller.packageName} artık PLAYING değil (state=$state), atlanıyor")
            return
        }
        if (md == null) {
            Log.d(TAG, "Oturum metadata içermiyor (henüz yüklenmedi)")
            return
        }
        val title = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "Bilinmeyen"
        val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        Log.d(TAG, "Metadata: '$title' - $artist (playbackChanged=$playbackChanged)")

        // Albüm kapağı IO'da yüklenir (content/http URI çözümü dahil)
        scope.launch {
            val art = resolveAlbumArt(md)
            NowPlayingBus.emit(
                NowPlaying(title = title, artist = artist, albumArt = art, source = controller.packageName)
            )
        }
    }

    // --- Albüm kapağı çözümleme ---

    private fun resolveAlbumArt(md: MediaMetadata): Bitmap? {
        // 1) Doğrudan Bitmap (bazı uygulamalar ekler)
        md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { b ->
            Log.d(TAG, "Albüm kapağı METADATA_KEY_ALBUM_ART Bitmap'inden alındı (${b.width}x${b.height})")
            return b
        }
        // 2) URI üzerinden
        val uriStr = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uriStr.isNullOrBlank()) {
            Log.d(TAG, "Albüm kapağı metadata'da yok")
            return null
        }
        return loadArt(uriStr)
    }

    private fun loadArt(uriStr: String): Bitmap? {
        val uri = Uri.parse(uriStr)
        Log.d(TAG, "Albüm kapağı URI yükleniyor: ${uri.scheme}://...")
        return runCatching {
            val bmp = when (uri.scheme?.lowercase()) {
                "content", "file", "android.resource" -> {
                    appContext.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }
                "http", "https" -> {
                    fetchHttp(uri)
                }
                else -> null
            }
            if (bmp != null) Log.d(TAG, "Kapak yüklendi: ${bmp.width}x${bmp.height}") else Log.w(TAG, "Kapak yüklenemedi: $uriStr")
            bmp
        }.getOrElse { e ->
            Log.w(TAG, "Kapak yükleme hatası: ${e.message}")
            null
        }
    }

    private fun fetchHttp(uri: Uri): Bitmap? {
        val conn = (URL(uri.toString()).openConnection() as HttpURLConnection).apply {
            connectTimeout = 4000
            readTimeout = 4000
            setRequestProperty("User-Agent", "TftCompanion/1.0")
        }
        return try {
            conn.inputStream.use { BitmapFactory.decodeStream(it) }
        } finally {
            conn.disconnect()
        }
    }

    // MediaController.Callback - metadata/oynatma durumu değişince tetiklenir.
    private inner class ControllerCallback(
        private val controller: MediaController,
        private val onChange: (MediaMetadata?, Int?, Boolean) -> Unit,
    ) : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) {
            Log.d(TAG, "onMetadataChanged - ${controller.packageName}")
            onChange(metadata, controller.playbackState?.state, false)
        }

        override fun onPlaybackStateChanged(state: PlaybackState?) {
            Log.d(TAG, "onPlaybackStateChanged - ${controller.packageName} state=${state?.state}")
            if (state?.state == PlaybackState.STATE_PLAYING) {
                onChange(controller.metadata, state.state, true)
            } else {
                // Bu oturum durdu/durakladı; en iyi oturumu yeniden seç
                refresh()
            }
        }
    }
}
