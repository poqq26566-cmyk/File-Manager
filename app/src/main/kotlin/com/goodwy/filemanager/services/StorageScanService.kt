package com.goodwy.filemanager.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goodwy.filemanager.R
import java.util.concurrent.atomic.AtomicInteger

// A lightweight foreground service whose only job is to keep the process alive — and show the user
// something is happening — while the Storage tab's background scans (trash / cache / apps / the
// per-category MediaStore counts) are running. It does not do any scanning itself: StorageFragment
// calls begin()/end() around each scan it kicks off, several of which can be in flight at once, so
// the service starts on the first one and stops once the last one reports back.
class StorageScanService : Service() {

    companion object {
        private const val CHANNEL_ID = "storage_scan_channel"
        private const val NOTIFICATION_ID = 7301
        private val activeScanCount = AtomicInteger(0)

        // Call right before starting a scan. Safe to call concurrently from multiple scans — only
        // the transition from 0 to 1 actually starts the service.
        fun begin(context: Context) {
            if (activeScanCount.getAndIncrement() == 0) {
                val intent = Intent(context.applicationContext, StorageScanService::class.java)
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        // Call once that scan is done, on every exit path. Stops the service once every scan that
        // called begin() has called end().
        fun end(context: Context) {
            val remaining = activeScanCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (remaining == 0) {
                context.applicationContext.stopService(Intent(context.applicationContext, StorageScanService::class.java))
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannelIfNeeded()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Nothing to do per start command — begin()/end() already track how many scans are
        // running, and the notification is already up from onCreate. Not sticky: if the process
        // gets killed there's no scan state worth resurrecting, the fragment just rescans on
        // next open.
        return START_NOT_STICKY
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.storage_scan_notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.storage_scan_notification_title))
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
