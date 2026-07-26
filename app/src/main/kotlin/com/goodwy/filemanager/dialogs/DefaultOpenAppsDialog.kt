package com.goodwy.filemanager.dialogs

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.Filter
import android.widget.Filterable
import android.widget.LinearLayout
import android.widget.ListView
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.getAlertDialogBuilder
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.models.RadioItem
import com.goodwy.filemanager.R
import com.goodwy.filemanager.activities.SettingsActivity
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.helpers.OpenAppCategory

// In-memory cache of every installed app's (packageName, label), built once per process rather
// than re-walking every installed package on every tap. Cleared only when the process restarts.
private var installedAppsCache: List<Pair<String, String>>? = null

// Runs the given block on a real background thread with THREAD_PRIORITY_BACKGROUND. Deliberately
// NOT using the app's usual ensureBackgroundThread() helper here: that spawns a thread at the
// caller's (i.e. the main thread's) priority, which is fine for quick work but was letting this
// specific job — walking every installed package and loading each one's label, which is
// meaningfully heavier — compete with the UI thread for CPU on loaded devices and starve input
// dispatch long enough to trigger an ANR, even though the work itself was technically off the
// main thread. Explicitly backgrounding the thread's priority lets the scheduler correctly favor
// the UI thread over this.
private fun runLowPriorityInBackground(block: () -> Unit) {
    Thread {
        Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        block()
    }.start()
}

private fun SettingsActivity.loadAllInstalledApps(): List<Pair<String, String>> {
    val pm = packageManager
    return try {
        pm.getInstalledApplications(0)
            .asSequence()
            .map { it.packageName }
            .filter { it != packageName }
            // Only keep packages that actually have a launchable entry point (an icon you'd
            // see in an app drawer / "open with" list). This is what filters out the hundreds
            // of invisible system-internal packages (resource overlays, network stack services,
            // permission controller overlays, etc.) that getInstalledApplications() otherwise
            // returns right alongside real apps — none of those are ever something the user
            // would pick to open a file with.
            .filter { pm.getLaunchIntentForPackage(it) != null }
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
// in-memory cache above once it's been built. Excludes this app itself (it wouldn't make sense
// to route a file back to our own file manager as its own "default app").
//
// Must only be called from runLowPriorityInBackground() above, never directly from a click
// listener — see the comment there for why.
private fun SettingsActivity.getAllInstalledApps(): List<Pair<String, String>> {
    installedAppsCache?.let { return it }
    val apps = loadAllInstalledApps()
    installedAppsCache = apps
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
    toast(com.goodwy.commons.R.string.loading)
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

// One row in the searchable checklist below: a package, its label, and whether it's currently
// checked. `checked` is mutated in place as the user taps rows, independent of list position,
// so toggling state survives the list being filtered (and re-filtered) by the search box.
private class FilterableAppEntry(val pkg: String, val label: String, var checked: Boolean)

// Backs the search box + checklist in showManageOpenAppsFilterDialog(). Filtering narrows which
// entries are shown (by label, case-insensitive substring match) without touching the checked
// state of any entry — including ones currently scrolled out of view by the filter.
private class FilterableAppAdapter(
    context: Context,
    private val allEntries: List<FilterableAppEntry>
) : BaseAdapter(), Filterable {

    var filteredEntries: List<FilterableAppEntry> = allEntries
        private set

    private val inflater = LayoutInflater.from(context)

    override fun getCount() = filteredEntries.size
    override fun getItem(position: Int): FilterableAppEntry = filteredEntries[position]
    override fun getItemId(position: Int) = position.toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = (convertView as? CheckedTextView)
            ?: (inflater.inflate(android.R.layout.simple_list_item_multiple_choice, parent, false) as CheckedTextView)
        val entry = filteredEntries[position]
        view.text = entry.label
        view.isChecked = entry.checked
        return view
    }

    override fun getFilter(): Filter = object : Filter() {
        override fun performFiltering(constraint: CharSequence?): FilterResults {
            val query = constraint?.toString()?.trim()?.lowercase().orEmpty()
            val matches = if (query.isEmpty()) {
                allEntries
            } else {
                allEntries.filter { it.label.lowercase().contains(query) }
            }
            return FilterResults().apply {
                values = matches
                count = matches.size
            }
        }

        @Suppress("UNCHECKED_CAST")
        override fun publishResults(constraint: CharSequence?, results: FilterResults?) {
            filteredEntries = (results?.values as? List<FilterableAppEntry>) ?: allEntries
            notifyDataSetChanged()
        }
    }
}

// Lets the user check/uncheck which installed apps should even be offered as candidates in the
// category pickers above — narrows a long "every installed app" list down to just the handful
// they actually use, so finding the right one later is quick. Checking nothing at all is treated
// the same as "no filter set up" (i.e. shows everything again), rather than an empty picker.
//
// Includes a search box above the list — with potentially hundreds of installed apps, scrolling
// to find one by hand isn't practical.
fun SettingsActivity.showManageOpenAppsFilterDialog() {
    toast(com.goodwy.commons.R.string.loading)
    runLowPriorityInBackground {
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
            val entries = allApps.map { (pkg, label) -> FilterableAppEntry(pkg, label, currentFilter.contains(pkg)) }
            val adapter = FilterableAppAdapter(this, entries)

            val density = resources.displayMetrics.density
            val horizontalPadding = (20 * density).toInt()
            val verticalPadding = (12 * density).toInt()

            val searchBox = EditText(this).apply {
                hint = getString(com.goodwy.commons.R.string.search)
                isSingleLine = true
                setPadding(horizontalPadding, verticalPadding, horizontalPadding, verticalPadding)
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                        adapter.filter.filter(s)
                    }

                    override fun afterTextChanged(s: Editable?) {}
                })
            }

            val listView = ListView(this).apply {
                this.adapter = adapter
                choiceMode = ListView.CHOICE_MODE_MULTIPLE
                setOnItemClickListener { _, view, position, _ ->
                    val entry = adapter.filteredEntries[position]
                    entry.checked = !entry.checked
                    (view as? CheckedTextView)?.isChecked = entry.checked
                }
            }

            val container = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                addView(searchBox)
                addView(
                    listView,
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (360 * density).toInt())
                )
            }

            getAlertDialogBuilder()
                .setTitle(R.string.filter_open_apps)
                .setView(container)
                .setPositiveButton(com.goodwy.commons.R.string.ok) { _, _ ->
                    val newFilter = entries.filter { it.checked }.map { it.pkg }.toHashSet()
                    config.defaultOpenAppsFilter = newFilter
                }
                .setNegativeButton(com.goodwy.commons.R.string.cancel, null)
                .show()
        }
    }
}
