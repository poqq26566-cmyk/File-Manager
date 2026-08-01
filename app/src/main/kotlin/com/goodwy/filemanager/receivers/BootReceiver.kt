package com.goodwy.filemanager.receivers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.services.FileMonitorService

/**
 * Restarts FileMonitorService after a reboot, but only if the user had actually left the
 * "Monitor Download folder" toggle on — a fresh install or a user who turned it off should
 * not suddenly get a background service running after their next reboot.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED && context.config.fileMonitorEnabled) {
            ContextCompat.startForegroundService(context, Intent(context, FileMonitorService::class.java))
        }
    }
}
