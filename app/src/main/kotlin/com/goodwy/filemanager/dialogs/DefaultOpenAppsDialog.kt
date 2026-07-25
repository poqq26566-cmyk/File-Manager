package com.goodwy.filemanager.dialogs

import android.content.pm.PackageManager
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.commons.models.RadioItem
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SettingsActivity
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.helpers.OpenAppCategory

// In-memory cache of every installed app's (packageName, label), built once per process rather
// than re-walking every installed package on every tap. Enumerating + loading each app's label
// is what made the picker feel slow (and once even blocked the UI thread long enough to ANR
// before this was moved to a background thread) — caching means only the very first lookup in
// a session pays that cost; every row tap after that reads from memory and opens instantly.
// Cleared only by clearInstalledAppsCache() below, called when the set of installed apps could
// plausibly have changed (see prefetchInstalledAppsCache()'s call site in SettingsActivity).
private var installedAppsCache: List<Pair<String, String>>? = null

private fun SettingsActivity.loadAllInstalledApps(): List<Pair<String, String>> {
    val pm = packageManager
    return try {
        // MATCH_UNINSTALLED_PACKAGES/GET_META_DATA aren't needed here (we only read the label),
        // and skipping them avoids extra per-package work that isn't used for anything.
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

// Returns every installed app package as (packageName, label) pairs, sorted by label, using the
// in-memory cache above when it's already been built. Excludes this app itself (it wouldn't make
// sense to route a file back to our own file manager as its own "default app").
//
// Still potentially slow the very first time it's called in a session — always call this off the
// main thread (see showAppPickerForCategory / showManageOpenAppsFilterDialog below), never
// directly from a click listener, or it can block the UI thread long enough to trigger an ANR.
private fun SettingsActivity.getAllInstalledApps(): List<Pair<String, String>> {
    installedAppsCache?.let { return it }
    val apps = loadAllInstalledApps()
    installedAppsCache = apps
    return apps
}

// Kicks off building the installed-apps cache in the background as soon as Settings opens,
// instead of waiting for the user's first tap — by the time they actually open a category
// picker, the list is very likely already sitting in memory ready to go. Safe to call
// repeatedly (e.g. every onResume): it's a no-op once the cache is already populated.
fun SettingsActivity.prefetchInstalledAppsCache() {
    if (installedAppsCache != null) {
        return
    }

    ensureBackgroundThread {
        getAllInstalledApps()
    }
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
//
// Reads from the cache built by prefetchInstalledAppsCache() when possible (near-instant); still
// routed through a background thread as a safety net for the rare case the cache isn't ready yet,
// with the dialog itself shown back on the main thread once the list is available.
fun SettingsActivity.showAppPickerForCategory(category: OpenAppCategory, onSaved: () -> Unit) {
    ensureBackgroundThread {
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
// Same cache + background-thread treatment as showAppPickerForCategory above, for the same
// reasons.
fun SettingsActivity.showManageOpenAppsFilterDialog() {
    ensureBackgroundThread {
        val allApps = getAllInstalledApps()
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
                .show()
        }
    }
}
