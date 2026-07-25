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

// Returns every installed app package as (packageName, label) pairs, sorted by label. Excludes
// this app itself (it wouldn't make sense to route a file back to our own file manager as its
// own "default app"). This intentionally does NOT filter by mime-type support or by whether the
// app has a launcher icon — some apps (e.g. certain media players/plugins) don't register in
// ways those narrower queries pick up, so we enumerate every installed application directly.
//
// This walks every installed package and loads each one's label individually, which can take
// a noticeable moment on a phone with a lot of apps installed — always call this off the main
// thread (see showAppPickerForCategory / showManageOpenAppsFilterDialog below), never directly
// from a click listener, or it can block the UI thread long enough to trigger an ANR.
private fun SettingsActivity.getAllInstalledApps(): List<Pair<String, String>> {
    val pm = packageManager
    return try {
        @Suppress("DEPRECATION")
        pm.getInstalledApplications(PackageManager.GET_META_DATA)
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
// Building that candidate list walks every installed app, which can take a moment — done on a
// background thread so tapping the row doesn't freeze the UI, with the dialog itself shown back
// on the main thread once the list is ready.
fun SettingsActivity.showAppPickerForCategory(category: OpenAppCategory, onSaved: () -> Unit) {
    ensureBackgroundThread {
        val candidates = getCandidateApps(category)
        runOnUiThread {
            if (isDestroyed || isFinishing) {
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
// Same background-thread treatment as showAppPickerForCategory above, for the same reason.
fun SettingsActivity.showManageOpenAppsFilterDialog() {
    ensureBackgroundThread {
        val allApps = getAllInstalledApps()
        runOnUiThread {
            if (isDestroyed || isFinishing) {
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
