package com.goodwy.filemanager.dialogs

import android.content.Intent
import android.content.pm.PackageManager
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.models.RadioItem
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SettingsActivity
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.helpers.OpenAppCategory

// Returns every installed, user-launchable app as (packageName, label) pairs, sorted by label.
// Excludes this app itself (it wouldn't make sense to route a file back to our own file
// manager as its own "default app"). We deliberately don't filter by mime-type support here —
// some apps declare their intent-filters in ways a strict mime-type query misses, so showing
// the full app list lets the user pick freely instead of silently hiding valid options.
private fun SettingsActivity.getCandidateApps(category: OpenAppCategory): List<Pair<String, String>> {
    val pm = packageManager
    val launcherIntent = Intent(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
    return try {
        pm.queryIntentActivities(launcherIntent, PackageManager.MATCH_DEFAULT_ONLY)
            .asSequence()
            .map { it.activityInfo.packageName }
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

// Opens the app-picker for one category (e.g. tapping the "PDF 文档" row): lists every
// installed app that can view that category, lets the user pick one (or "ask every time" to
// clear the preference), and saves the choice — scoped to this app only.
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
