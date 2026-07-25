package com.goodwy.filemanager.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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

        // How long to wait between retries when a stop is requested before the service has
        // confirmed startForeground() actually ran. Kept short: in the common case the flag is
        // already true by the time this fires and it stops on the very first check.
        private const val STOP_RETRY_MS = 60L
        private const val MAX_STOP_RETRIES = 15 // ~900ms worst case, well under the ANR/crash window

        private val activeScanCount = AtomicInteger(0)
        private val stopHandler = Handler(Looper.getMainLooper())
        private var pendingStop: Runnable? = null

        // Set true by the running instance right after startForeground() succeeds, false once it's
        // gone. Some scans (an empty trash folder, a cache scan with few apps) finish fast enough
        // that a stop request can arrive before the freshly-started service even reaches
        // onCreate() on the main thread — stopping at that point is a race Android 12+ punishes
        // with a hard crash ("did not then call startForeground()"), so a stop only actually
        // happens once this is confirmed true.
        @Volatile
        private var isForegroundActive = false

        // Call right before starting a scan. Safe to call concurrently from multiple scans — only
        // the transition from 0 to 1 actually starts the service.
        @Synchronized
        fun begin(context: Context) {
            pendingStop?.let { stopHandler.removeCallbacks(it) }
            pendingStop = null
            if (activeScanCount.getAndIncrement() == 0) {
                val intent = Intent(context.applicationContext, StorageScanService::class.java)
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        // Call once that scan is done, on every exit path. Once every scan that called begin() has
        // called end(), the service is stopped as soon as it's confirmed to have actually started.
        @Synchronized
        fun end(context: Context) {
            val remaining = activeScanCount.updateAndGet { (it - 1).coerceAtLeast(0) }
            if (remaining == 0) {
                val appContext = context.applicationContext
                scheduleStop(appContext, MAX_STOP_RETRIES)
            }
        }

        private fun scheduleStop(appContext: Context, retriesLeft: Int) {
            val stopRunnable = Runnable {
                when {
                    activeScanCount.get() != 0 -> {
                        // a new scan started in the meantime, begin() already cancelled us — no-op
                    }
                    isForegroundActive || retriesLeft <= 0 -> {
                        appContext.stopService(Intent(appContext, StorageScanService::class.java))
                    }
                    else -> scheduleStop(appContext, retriesLeft - 1)
                }
            }
            pendingStop = stopRunnable
            stopHandler.postDelayed(stopRunnable, STOP_RETRY_MS)
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
        isForegroundActive = true
    }

    override fun onDestroy() {
        isForegroundActive = false
        super.onDestroy()
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
