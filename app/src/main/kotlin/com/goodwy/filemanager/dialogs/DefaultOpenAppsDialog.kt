package com.goodwy.filemanager.dialogs

import android.content.pm.PackageManager
import android.os.Process
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.models.RadioItem
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SettingsActivity
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.helpers.OpenAppCategory

// In-memory cache of every installed app's (packageName, label) for this process — checked
// before falling back to the persisted cache in Config, which in turn is checked before doing
// the actual (slow) enumeration. See getAllInstalledApps() below.
private var installedAppsCache: List<Pair<String, String>>? = null

// Runs the given block on a real background thread with THREAD_PRIORITY_BACKGROUND. Deliberately
// NOT using the app's usual ensureBackgroundThread() helper here: that spawns a thread at the
// caller's (i.e. the main thread's) priority, which is fine for quick work but was letting this
// specific job — walking every installed package and loading each one's label, which is
// meaningfully heavier — compete with the UI thread for CPU on loaded devices and starve input
// dispatch long enough to trigger an ANR, even though the work itself was technically off the
// main thread. Explicitly backgrounding the thread's priority lets the scheduler correctly favor
// the UI thread over this — the tradeoff is that this makes the enumeration itself slower, which
// is exactly why the result is now also persisted (see loadAllInstalledApps()/getAllInstalledApps()
// below): that slow walk only has to happen once ever, not on every cold app launch.
private fun runLowPriorityInBackground(block: () -> Unit) {
    Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        block()
    }.start()
}

// The actual (slow) enumeration: walks every installed package and loads each one's label.
private fun SettingsActivity.enumerateInstalledApps(): List<Pair<String, String>> {
    val pm = packageManager
    return try {
        pm.getInstalledApplications(0)
            .asSequence()
            .map { it.packageName }
            .filter { it != packageName }
            .distinct()
            .mapNotNull { pkg ->
                try {
                    val label = pm.getApplicationInfo(pkg, 0).loadLabel(pm).toString()
                    pkg to label
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.second.lowercase() }
            .toList()
    } catch (e: Exception) {
        emptyList()
    }
}

// Returns every installed app package as (packageName, label) pairs, sorted by label. Excludes
// this app itself (it wouldn't make sense to route a file back to our own file manager as its
// own "default app").
//
// Three layers, cheapest first: (1) in-memory cache for this process, (2) a persisted cache in
// Config that survives app restarts, (3) only if neither exists yet, the actual slow enumeration
// — which is then written back to both caches so it's not repeated. Call refreshInstalledApps()
// to force a fresh enumeration (e.g. after installing a new app that should show up).
//
// Must only be called from runLowPriorityInBackground() above, never directly from a click
// listener — even the cached paths still do some I/O (reading SharedPreferences), and the very
// first call on a given device still has to pay the full enumeration cost.
private fun SettingsActivity.getAllInstalledApps(): List<Pair<String, String>> {
    installedAppsCache?.let { return it }

    val persisted = config.cachedInstalledApps
    if (persisted.isNotEmpty()) {
        installedAppsCache = persisted
        return persisted
    }

    val apps = enumerateInstalledApps()
    installedAppsCache = apps
    config.cachedInstalledApps = apps
    return apps
}

// Forces a fresh enumeration, bypassing both cache layers, and updates them with the new result.
// Use this when the cached list might be stale — most notably right after the user installs an
// app they now want to appear as a candidate.
private fun SettingsActivity.refreshInstalledApps(): List<Pair<String, String>> {
    val apps = enumerateInstalledApps()
    installedAppsCache = apps
    config.cachedInstalledApps = apps
    return apps
}

// Apps actually offered in a category's picker: the full installed-apps list, narrowed down to
// the user's saved filter if they've set one up (see showManageOpenAppsFilterDialog below). An
// empty filter means "not set up yet", so everything is shown until the user narrows it down.
private fun SettingsActivity.getCandidateApps(category: OpenAppCategory): List<Pair<String, String>> {
    val allApps = getAllInstalledApps()
    val filter = config.defaultOpenAppsFilter
    return if (filter.isEmpty()) allApps else allApps.filter { filter.contains(it.first) }
}

// Returns the display name to show for a category's row in Settings — either the currently
// chosen app's label, or "not set" if none is chosen (or the previously chosen app got
// uninstalled since). Only reads the one already-stored package's label, not the full app list,
// so this is cheap enough to call directly on the main thread.
fun SettingsActivity.getDefaultOpenAppLabel(category: OpenAppCategory): String {
    val pkg = config.getDefaultOpenApp(category.key)
    if (pkg.isEmpty()) {
        return getString(R.string.default_open_app_not_set)
    }

    return try {
        packageManager.getApplicationInfo(pkg, 0).loadLabel(packageManager).toString()
    } catch (e: Exception) {
        getString(R.string.default_open_app_not_set)
    }
}

// Opens the app-picker for one category (e.g. tapping the "PDF 文档" row): lists the candidate
// apps (see getCandidateApps above), lets the user pick one (or "ask every time" to clear the
// preference), and saves the choice — scoped to this app only.
fun SettingsActivity.showAppPickerForCategory(category: OpenAppCategory, onSaved: () -> Unit) {
    runLowPriorityInBackground {
        val candidates = getCandidateApps(category)
        runOnUiThread {
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
                return@runOnUiThread
            }

            if (candidates.isEmpty()) {
                toast(com.goodwy.commons.R.string.no_app_found)
                return@runOnUiThread
            }

            val currentPkg = config.getDefaultOpenApp(category.key)

            val items = ArrayList<RadioItem>()
            items.add(RadioItem(0, getString(R.string.default_open_app_ask_everytime), ""))
            candidates.forEachIndexed { index, (pkg, label) ->
                items.add(RadioItem(index + 1, label, pkg))
            }

            val checkedId = items.indexOfFirst { it.value == currentPkg }.let { if (it == -1) 0 else it }

            RadioGroupDialog(this, items, checkedId, R.string.default_open_apps) { newValue ->
                config.setDefaultOpenApp(category.key, newValue as String)
                onSaved()
            }
        }
    }
}

// Lets the user check/uncheck which installed apps should even be offered as candidates in the
// category pickers above — narrows a long "every installed app" list down to just the handful
// they actually use, so finding the right one later is quick. Checking nothing at all is treated
// the same as "no filter set up" (i.e. shows everything again), rather than an empty picker.
//
// Has a "Refresh" button (neutral button) to force re-scanning installed apps — useful right
// after installing something new that isn't showing up yet, since the list is otherwise served
// from cache (see getAllInstalledApps() above) rather than re-scanned every time.
fun SettingsActivity.showManageOpenAppsFilterDialog() {
    runLowPriorityInBackground {
        showManageOpenAppsFilterDialogInternal(getAllInstalledApps())
    }
}

private fun SettingsActivity.showManageOpenAppsFilterDialogInternal(allApps: List<Pair<String, String>>) {
    runOnUiThread {
        if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            return@runOnUiThread
        }

        if (allApps.isEmpty()) {
            toast(com.goodwy.commons.R.string.no_app_found)
            return@runOnUiThread
        }

        val currentFilter = config.defaultOpenAppsFilter
        val checkedItems = BooleanArray(allApps.size) { currentFilter.contains(allApps[it].first) }
        val labels = allApps.map { it.second }.toTypedArray()

        getAlertDialogBuilder()
            .setTitle(R.string.filter_open_apps)
            .setMultiChoiceItems(labels, checkedItems) { _, which, isChecked ->
                checkedItems[which] = isChecked
            }
            .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                val newFilter = allApps.filterIndexed { index, _ -> checkedItems[index] }
                    .map { it.first }
                    .toHashSet()
                config.defaultOpenAppsFilter = newFilter
            }
            .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
            .setNeutralButton(R.string.refresh) { _, _ ->
                toast(com.goodwy.commons.R.string.loading)
                runLowPriorityInBackground {
                    showManageOpenAppsFilterDialogInternal(refreshInstalledApps())
                }
            }
            .show()
    }
}
