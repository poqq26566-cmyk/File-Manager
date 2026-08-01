package com.goodwy.filemanager.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.FileObserver
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.goodwy.commons.extensions.getInternalStoragePath
import com.goodwy.filemanager.R
import java.io.File

/**
 * Foreground service that watches the Download folder with FileObserver and moves finished
 * downloads straight into type subfolders (Images / Documents / Videos / Audio / Archives /
 * Others) — no polling, no WorkManager delay, it reacts as soon as a file finishes writing.
 *
 * Toggle: Settings > File operations > "Monitor Download folder and auto-organize new files".
 * Restarted after reboot by BootReceiver if the toggle was left on.
 */
class FileMonitorService : Service() {

    companion object {
        private const val CHANNEL_ID = "file_monitor_channel"
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
    }

    private var observer: FileObserver? = null
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
        observer?.stopWatching()
        observer = null
        debounceHandler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun downloadDir(): File = File(getInternalStoragePath(), "Download")

    private fun startWatching() {
        val dir = downloadDir()
        if (!dir.isDirectory) {
            return
        }

        @Suppress("DEPRECATION")
        observer = object : FileObserver(dir.path, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                if (path.isNullOrEmpty()) {
                    return
                }
                // Debounce: CLOSE_WRITE can fire more than once for the same file (e.g. once
                // per buffered flush from some download managers).
                pendingMoves[path]?.let { debounceHandler.removeCallbacks(it) }
                val runnable = Runnable { organizeFile(File(dir, path)) }
                pendingMoves[path] = runnable
                debounceHandler.postDelayed(runnable, DEBOUNCE_MS)
            }
        }
        observer?.startWatching()
    }

    private fun organizeFile(file: File) {
        pendingMoves.remove(file.name)
        if (!file.isFile) {
            return
        }

        val extension = file.extension.lowercase()
        val targetFolderName = TYPE_FOLDERS[extension] ?: return // leave unrecognized types where they are

        val targetDir = File(file.parentFile, targetFolderName)
        if (!targetDir.exists() && !targetDir.mkdirs()) {
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

        file.renameTo(targetFile)
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
