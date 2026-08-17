package com.araswqm.tftcompanion.media

import android.app.Notification
import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.net.Uri
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Yedek medya kaynağı: medya bildirimlerini dinler ve EXTRA_MEDIA_METADATA
 * içindeki bilgileri NowPlayingBus'a besler. MediaSessionWatcher'ın birincil
 * kaynağı yanında, bazı uygulamaların oturum bilgisini gizlemesine karşı
 * ikinci bir göz olarak çalışır.
 */
class MediaNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "MediaNotificationLsn"
        private const val EXTRA_MEDIA_SESSION = "android.media.session.extra.SESSION"
        private const val EXTRA_MEDIA_METADATA = "android.media.metadata"
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        // Binder iş parçacığında oluşan bir hata tüm uygulamayı çöktürebilir;
        // medya bildirimi işleme her zaman zararsız olmalı.
        try {
            if (!isMediaNotification(sbn.notification)) return

            val md = sbn.notification.extras.getBundle(EXTRA_MEDIA_METADATA) ?: return
            val title = md.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: sbn.notification.extras.getString(Notification.EXTRA_TITLE)
            if (title == null) return

            val artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM)
                ?: sbn.notification.extras.getString(Notification.EXTRA_TEXT) ?: ""
            val art = resolveArt(md, sbn.notification)

            Log.d(TAG, "Bildirim metadata: '$title' - $artist (${sbn.packageName})")
            NowPlayingBus.emit(
                NowPlaying(title = title, artist = artist, albumArt = art, source = sbn.packageName)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Bildirim işlenirken hata (kritik değil): ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        // Not gerekli; MediaSessionWatcher durum değişimlerini kendisi ele alır.
    }

    private fun isMediaNotification(n: Notification): Boolean {
        if (n.extras.containsKey(EXTRA_MEDIA_METADATA)) return true
        val clazz = n.extras.getString(Notification.EXTRA_TEMPLATE) ?: return false
        return clazz.contains("MediaStyle")
    }

    private fun resolveArt(md: Bundle, n: Notification): Bitmap? {
        // getParcelable yanlış tipte veride ClassCastException fırlatabilir.
        runCatching {
            md.getParcelable<Bitmap>(MediaMetadata.METADATA_KEY_ALBUM_ART)?.let { return it }
            md.getParcelable<Bitmap>(MediaMetadata.METADATA_KEY_DISPLAY_ICON)?.let { return it }
        }.onFailure { e ->
            Log.w(TAG, "Kapak Bitmap olarak okunamadı: ${e.message}")
        }
        val uri = md.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: md.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI)
        if (uri != null) {
            return loadUri(Uri.parse(uri))
        }

        // Çoğu müzik uygulaması kapağı metadata'ya DEĞİL bildirimin büyük
        // ikonuna (largeIcon) koyar (ör. Meld Music). getLargeIcon() -> Icon
        // -> Bitmap. Icon.loadDrawable bir Context ister; service bir Context'tir.
        val largeIcon: Bitmap? = try {
            n.getLargeIcon()?.loadDrawable(this)?.let { d ->
                val b = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
                val c = android.graphics.Canvas(b)
                d.setBounds(0, 0, b.width, b.height)
                d.draw(c)
                b
            }
        } catch (e: Exception) {
            Log.w(TAG, "largeIcon okunamadı: ${e.message}")
            null
        }
        if (largeIcon != null) {
            Log.d(TAG, "Kapak bildirim largeIcon'undan alındı (${largeIcon.width}x${largeIcon.height})")
            return largeIcon
        }

        // Son çare: bildirim küçük ikonu bile kullanılabilir
        n.smallIcon?.let { icon ->
            return runCatching {
                val pkg = packageManager.getResourcesForApplication(icon.resPackage)
                pkg.getDrawable(icon.resId)?.let { d ->
                    val b = Bitmap.createBitmap(d.intrinsicWidth, d.intrinsicHeight, Bitmap.Config.ARGB_8888)
                    // Drawable -> Bitmap (Canvas ile çizim)
                    val c = android.graphics.Canvas(b)
                    d.setBounds(0, 0, b.width, b.height)
                    d.draw(c)
                    b
                }
            }.getOrNull()
        }
        return null
    }

    private fun loadUri(uri: Uri): Bitmap? = runCatching {
        contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it) }
    }.getOrNull()
}
