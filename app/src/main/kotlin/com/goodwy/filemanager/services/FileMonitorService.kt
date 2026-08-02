package com.goodwy.filemanager.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.goodwy.filemanager.R
import com.goodwy.filemanager.extensions.config
import java.io.File

/**
 * Foreground service that watches every folder in config.monitoredFolders with FileObserver and
 * moves finished downloads/copies straight into type subfolders (Images / Documents / Videos /
 * Audio / Archives / Apps) — no polling, no WorkManager delay, it reacts as soon as a file
 * finishes writing.
 *
 * Toggle: Settings > File operations > "Monitor Download folder and auto-organize new files".
 * Watched folders: Settings > File operations > "Monitored folders" (defaults to just Download).
 * Restarted after reboot by BootReceiver if the toggle was left on.
 */
class FileMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "file_monitor_channel"
        private const val ERROR_CHANNEL_ID = "file_monitor_error_channel"
        private const val NOTIFICATION_ID = 7302

        // A freshly-created file fires CREATE immediately but may still be being written to
        // (e.g. a browser download in progress) — CLOSE_WRITE/MOVED_TO is the point it's safe
        // to move. This is debounced slightly in case the same file fires more than once.
        private const val WATCH_MASK = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO
        private const val DEBOUNCE_MS = 800L

        private val TYPE_FOLDERS = mapOf(
            "jpg" to "Images", "jpeg" to "Images", "png" to "Images", "gif" to "Images",
            "webp" to "Images", "bmp" to "Images", "heic" to "Images",
            "pdf" to "Documents", "doc" to "Documents", "docx" to "Documents",
            "xls" to "Documents", "xlsx" to "Documents", "ppt" to "Documents",
            "pptx" to "Documents", "txt" to "Documents",
            "mp4" to "Videos", "mkv" to "Videos", "avi" to "Videos", "mov" to "Videos", "webm" to "Videos",
            "mp3" to "Audio", "wav" to "Audio", "flac" to "Audio", "m4a" to "Audio", "ogg" to "Audio",
            "zip" to "Archives", "rar" to "Archives", "7z" to "Archives", "tar" to "Archives", "gz" to "Archives",
            "apk" to "Apps"
        )

        // The list of watched folders is only read once, in onCreate(). Call this after adding
        // or removing a monitored folder while the service is already running, so the change
        // takes effect immediately instead of on the next app/service restart.
        fun restartIfRunning(context: Context) {
            val appContext = context.applicationContext
            appContext.stopService(Intent(appContext, FileMonitorService::class.java))
            ContextCompat.startForegroundService(appContext, Intent(appContext, FileMonitorService::class.java))
        }
    }

    private val observers = mutableListOf<FileObserver>()
    private val debounceHandler = Handler(Looper.getMainLooper())
    private val pendingMoves = HashMap<String, Runnable>()

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
        startWatching()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Sticky: unlike the storage-scan service this is meant to keep running for as long
        // as the toggle is on, so ask the system to restart it if it gets killed.
        return START_STICKY
    }

    override fun onDestroy() {
        observers.forEach { it.stopWatching() }
        observers.clear()
        debounceHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun startWatching() {
        // If nothing is configured (or every configured folder has since been deleted), there's
        // nothing useful to do — stop rather than sit around as a resident no-op service.
        val dirs = config.monitoredFolders.map { File(it) }.filter { it.isDirectory }
        if (dirs.isEmpty()) {
            stopSelf()
            return
        }

        dirs.forEach { dir -> observers.add(watchDir(dir)) }
    }

    @Suppress("DEPRECATION")
    private fun watchDir(dir: File): FileObserver {
        val observer = object : FileObserver(dir.path, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrEmpty()) {
                    return
                }
                val key = "${dir.path}/$path"
                // Debounce: CLOSE_WRITE can fire more than once for the same file (e.g. once
                // per buffered flush from some download managers).
                pendingMoves[key]?.let { debounceHandler.removeCallbacks(it) }
                val runnable = Runnable { organizeFile(File(dir, path)) }
                pendingMoves[key] = runnable
                debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
            }
        }
        observer.startWatching()
        return observer
    }

    private fun organizeFile(file: File) {
        pendingMoves.remove("${file.parent}/${file.name}")
        if (!file.isFile) {
            return
        }

        val extension = file.extension.lowercase()
        val targetFolderName = TYPE_FOLDERS[extension] ?: return // leave unrecognized types where they are

        val targetDir = File(file.parentFile, targetFolderName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
            notifyOrganizeFailed(file.name, getString(R.string.file_monitor_error_target_dir))
            return
        }

        var targetFile = File(targetDir, file.name)
        if (targetFile.exists()) {
            // Avoid clobbering an existing file with the same name: FileName (1).ext, (2), ...
            val baseName = file.nameWithoutExtension
            val ext = if (extension.isEmpty()) "" else ".$extension"
            var counter = 1
            while (targetFile.exists()) {
                targetFile = File(targetDir, "$baseName ($counter)$ext")
                counter++
            }
        }

        // renameTo() is just the raw rename() syscall under the hood, which only works within
        // a single filesystem/partition — it fails silently (returns false, no exception) for
        // anything crossing a partition boundary, e.g. a monitored folder on an SD card whose
        // target subfolder ends up elsewhere. Fall back to copy+delete, which works across
        // partitions, before giving up and telling the user why nothing happened.
        val moved = file.renameTo(targetFile) || copyThenDelete(file, targetFile)
        if (!moved) {
            notifyOrganizeFailed(file.name, getString(R.string.file_monitor_error_move_failed))
        }
    }

    private fun copyThenDelete(source: File, target: File): Boolean {
        return try {
            source.copyTo(target, overwrite = false)
            source.delete()
            true
        } catch (e: Exception) {
            target.delete() // clean up a partial copy so it doesn't look like it half-succeeded
            false
        }
    }

    private fun notifyOrganizeFailed(fileName: String, reason: String) {
        val notification = NotificationCompat.Builder(this, ERROR_CHANNEL_ID)
            .setContentTitle(getString(R.string.file_monitor_error_title, fileName))
            .setContentText(reason)
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason))
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        val manager = getSystemService(NotificationManager::class.java)
        // Distinct ID per filename so failures for different files don't overwrite each other,
        // but the same file failing twice in a row just updates one notification instead of
        // spamming the tray.
        manager.notify(NOTIFICATION_ID + fileName.hashCode(), notification)
    }

    private fun createNotificationChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.file_monitor_notification_channel),
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
            if (manager.getNotificationChannel(ERROR_CHANNEL_ID) == null) {
                val errorChannel = NotificationChannel(
                    ERROR_CHANNEL_ID,
                    getString(R.string.file_monitor_error_channel),
                    NotificationManager.IMPORTANCE_DEFAULT
                )
                manager.createNotificationChannel(errorChannel)
            }
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.file_monitor_notification_title))
            .setSmallIcon(R.drawable.ic_storage_vector)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
