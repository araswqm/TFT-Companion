package com.araswqm.tftcompanion.media

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.araswqm.tftcompanion.MainActivity
import com.araswqm.tftcompanion.R

/**
 * Otomatik modda "şu an çalan" medyayı izleyen ön plan hizmeti.
 * Sisteme medya izleme yapıldığını bildiren kalıcı bir bildirim gösterir
 * (foregroundServiceType=mediaPlayback). MediaSessionWatcher'ı çalıştırır;
 * çıkan medya bilgisi NowPlayingBus üzerinden ViewModel'e gider.
 */
class MediaWatchService : Service() {

    companion object {
        private const val TAG = "MediaWatchService"
        private const val CHANNEL_ID = "media_watch"
        private const val NOTIFICATION_ID = 1
    }

    private var watcher: MediaSessionWatcher? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundCompat()
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy - hizmet durduruluyor")
        watcher?.stop()
        watcher = null
        super.onDestroy()
    }

    private fun startWatching() {
        if (watcher != null) return
        Log.d(TAG, "MediaSessionWatcher başlatılıyor")
        val w = MediaSessionWatcher(this)
        watcher = w
        w.start()
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.watch_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = getString(R.string.watch_notification_text) }
        nm.createNotificationChannel(channel)
    }

    private fun startForegroundCompat() {
        val pending = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.watch_notification_title))
            .setContentText(getString(R.string.watch_notification_text))
            .setContentIntent(pending)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
