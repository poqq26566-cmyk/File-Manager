package com.goodwy.filemanager.helpers

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import com.goodwy.filemanager.R

object BatteryOptimizationHelper {

    /**
     * Requests the standard Android "ignore battery optimizations" whitelist for this app,
     * via the system's own confirmation dialog. No-op (does nothing) if already whitelisted.
     */
    fun requestIgnoreBatteryOptimizations(activity: Activity) {
        val powerManager = activity.getSystemService(PowerManager::class.java) ?: return
        if (powerManager.isIgnoringBatteryOptimizations(activity.packageName)) {
            return
        }

        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            openAppSettings(activity)
        }
    }

    /**
     * There is no standard Android API for "autostart" permission — every manufacturer that
     * restricts it (to save battery on top of stock Android) ships its own settings screen for
     * it. This tries each known one in turn and falls back to the app's own settings page
     * (where the user can usually find an equivalent option manually) if none of them exist
     * on this device/ROM.
     */
    fun openAutoStartSettings(activity: Activity) {
        val candidates = listOf(
            // Xiaomi / MIUI
            Intent().setComponent(
                ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
            ),
            // Huawei / EMUI
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
            ),
            // Oppo / ColorOS
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
            ),
            Intent().setComponent(
                ComponentName("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
            ),
            // Vivo / OriginOS
            Intent().setComponent(
                ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
            ),
            Intent().setComponent(
                ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
            ),
            // Meizu / Flyme
            Intent().setComponent(
                ComponentName("com.meizu.safe", "com.meizu.safe.permission.SmartBGActivity")
            ),
            // Samsung
            Intent().setComponent(
                ComponentName("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
            ),
            // OnePlus
            Intent().setComponent(
                ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
            )
        )

        for (intent in candidates) {
            try {
                activity.startActivity(intent)
                return
            } catch (e: Exception) {
                // component doesn't exist on this ROM/version — try the next one
            }
        }

        Toast.makeText(activity, R.string.autostart_not_found, Toast.LENGTH_LONG).show()
        openAppSettings(activity)
    }

    private fun openAppSettings(activity: Activity) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${activity.packageName}")
            }
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            // Give up quietly — there is genuinely nowhere left to send the user.
        }
    }
}
