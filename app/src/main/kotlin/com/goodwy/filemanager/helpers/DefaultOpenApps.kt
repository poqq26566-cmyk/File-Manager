package com.goodwy.filemanager.dialogs

import android.content.pm.PackageManager
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.toast
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
// uninstalled since).
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
    val candidates = getCandidateApps(category)
    if (candidates.isEmpty()) {
        toast(com.goodwy.commons.R.string.no_app_found)
        return
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

// Lets the user check/uncheck which installed apps should even be offered as candidates in the
// category pickers above — narrows a long "every installed app" list down to just the handful
// they actually use, so finding the right one later is quick. Checking nothing at all is treated
// the same as "no filter set up" (i.e. shows everything again), rather than an empty picker.
fun SettingsActivity.showManageOpenAppsFilterDialog() {
    val allApps = getAllInstalledApps()
    if (allApps.isEmpty()) {
        toast(com.goodwy.commons.R.string.no_app_found)
        return
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
