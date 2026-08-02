package com.goodwy.filemanager.helpers

import android.content.Context
import android.content.res.Configuration
import androidx.core.content.edit
import com.goodwy.commons.extensions.getInternalStoragePath
import com.goodwy.commons.helpers.BaseConfig
import java.io.File
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
        private const val ENTRY_SEPARATOR = "\u0001"
    }

    // One-shot flag: has the first-run default sort order (see SplashActivity) already
    // been applied? Prevents it from overwriting the user's own sort choice on later launches.
    var hasSetInitialSortOrder: Boolean
        get() = prefs.getBoolean(HAS_SET_INITIAL_SORT_ORDER, false)
        set(value) = prefs.edit().putBoolean(HAS_SET_INITIAL_SORT_ORDER, value).apply()

    // Settings > File operations > "Monitor Download folder and auto-organize new files".
    // Also read by BootReceiver to decide whether to restart FileMonitorService after reboot.
    var fileMonitorEnabled: Boolean
        get() = prefs.getBoolean(FILE_MONITOR_ENABLED, false)
        set(value) = prefs.edit().putBoolean(FILE_MONITOR_ENABLED, value).apply()

    // Folders FileMonitorService watches. Defaults to just Download until the user has ever
    // touched this setting (prefs not containing the key at all is what signals "never
    // customized" — distinct from the user deliberately clearing every folder to an empty set).
    var monitoredFolders: MutableSet<String>
        get() {
            if (!prefs.contains(MONITORED_FOLDERS)) {
                return hashSetOf(File(getInternalStoragePath(), "Download").path)
            }
            return prefs.getStringSet(MONITORED_FOLDERS, HashSet()) ?: HashSet()
        }
        set(value) = prefs.edit().putStringSet(MONITORED_FOLDERS, value).apply()

    fun addMonitoredFolder(path: String) {
        val current = HashSet<String>(monitoredFolders)
        current.add(path.trimEnd('/'))
        monitoredFolders = current
    }

    fun removeMonitoredFolder(path: String) {
        val current = HashSet<String>(monitoredFolders)
        current.remove(path)
        monitoredFolders = current
    }

    var showHidden: Boolean
        get() = prefs.getBoolean(SHOW_HIDDEN, false)
        set(show) = prefs.edit().putBoolean(SHOW_HIDDEN, show).apply()

    var temporarilyShowHidden: Boolean
        get() = prefs.getBoolean(TEMPORARILY_SHOW_HIDDEN, false)
        set(temporarilyShowHidden) = prefs.edit().putBoolean(TEMPORARILY_SHOW_HIDDEN, temporarilyShowHidden).apply()

    fun shouldShowHidden() = showHidden || temporarilyShowHidden

    var pressBackTwice: Boolean
        get() = prefs.getBoolean(PRESS_BACK_TWICE, true)
        set(pressBackTwice) = prefs.edit().putBoolean(PRESS_BACK_TWICE, pressBackTwice).apply()

    var homeFolder: String
        get(): String {
            var path = prefs.getString(HOME_FOLDER, "")!!
            if (path.isEmpty() || !File(path).isDirectory) {
                path = getInternalStoragePath()
                homeFolder = path
            }
            return path
        }
        set(homeFolder) = prefs.edit().putString(HOME_FOLDER, homeFolder).apply()

    // Last-known storage category sizes (images/videos/apps/total/etc, keyed by volume+category),
    // so the Storage tab can show real numbers immediately on open instead of "…" while the fresh
    // scan runs in the background.
    fun getCachedCategorySize(volumeName: String, category: String): Long =
        prefs.getLong("cached_size_${volumeName}_$category", -1L)

    fun saveCachedCategorySize(volumeName: String, category: String, size: Long) {
        prefs.edit().putLong("cached_size_${volumeName}_$category", size).apply()
    }

    // Same idea as the size cache above, but for the file count shown next to each category
    // label (e.g. "Documents (1643)") so that number is also available immediately on open.
    fun getCachedCategoryCount(volumeName: String, category: String): Int =
        prefs.getInt("cached_count_${volumeName}_$category", -1)

    fun saveCachedCategoryCount(volumeName: String, category: String, count: Int) {
        prefs.edit().putInt("cached_count_${volumeName}_$category", count).apply()
    }

    // Recycle bin metadata (see TrashManager). Routed through Config since TrashManager can't
    // reliably reach the inherited 'prefs' property directly from outside this class.
    fun getTrashMetadataRaw(): String? = prefs.getString("trash_metadata", null)

    fun saveTrashMetadataRaw(json: String) {
        prefs.edit().putString("trash_metadata", json).apply()
    }

    // "过滤文件夹" (Settings > Security): when enabled, any folder the user has added to
    // excludedFolders is skipped everywhere file lists and storage-category counts are built,
    // the same way the hardcoded "vault" folder already is (see String.kt).
    var filterFoldersEnabled: Boolean
        get() = prefs.getBoolean(FILTER_FOLDERS_ENABLED, true)
        set(filterFoldersEnabled) = prefs.edit().putBoolean(FILTER_FOLDERS_ENABLED, filterFoldersEnabled).apply()

    var excludedFolders: MutableSet<String>
        get() = prefs.getStringSet(EXCLUDED_FOLDERS, HashSet()) ?: HashSet()
        set(excludedFolders) = prefs.edit().putStringSet(EXCLUDED_FOLDERS, excludedFolders).apply()

    fun addExcludedFolder(path: String) {
        val current = HashSet<String>(excludedFolders)
        current.add(path.trimEnd('/'))
        excludedFolders = current
    }

    fun removeExcludedFolder(path: String) {
        val current = HashSet<String>(excludedFolders)
        current.remove(path)
        excludedFolders = current
    }

    fun addFavorite(path: String) {
        val currFavorites = HashSet<String>(favorites)
        currFavorites.add(path)
        favorites = currFavorites
    }

    fun moveFavorite(oldPath: String, newPath: String) {
        if (!favorites.contains(oldPath)) {
            return
        }

        val currFavorites = HashSet<String>(favorites)
        currFavorites.remove(oldPath)
        currFavorites.add(newPath)
        favorites = currFavorites
    }

    fun removeFavorite(path: String) {
        if (!favorites.contains(path)) {
            return
        }

        val currFavorites = HashSet<String>(favorites)
        currFavorites.remove(path)
        favorites = currFavorites
    }

    var isRootAvailable: Boolean
        get() = prefs.getBoolean(IS_ROOT_AVAILABLE, false)
        set(isRootAvailable) = prefs.edit().putBoolean(IS_ROOT_AVAILABLE, isRootAvailable).apply()

    // Timestamp of the last time we probed for root / validated favorites. Both checks touch the
    // filesystem and don't need to run on literally every cold launch, only occasionally.
    var lastRootCheckTS: Long
        get() = prefs.getLong(LAST_ROOT_CHECK_TS, 0L)
        set(lastRootCheckTS) = prefs.edit().putLong(LAST_ROOT_CHECK_TS, lastRootCheckTS).apply()

    var lastFavoritesCheckTS: Long
        get() = prefs.getLong(LAST_FAVORITES_CHECK_TS, 0L)
        set(lastFavoritesCheckTS) = prefs.edit().putLong(LAST_FAVORITES_CHECK_TS, lastFavoritesCheckTS).apply()

    var enableRootAccess: Boolean
        get() = prefs.getBoolean(ENABLE_ROOT_ACCESS, false)
        set(enableRootAccess) = prefs.edit().putBoolean(ENABLE_ROOT_ACCESS, enableRootAccess).apply()

    var editorTextZoom: Float
        get() = prefs.getFloat(EDITOR_TEXT_ZOOM, 1.2f)
        set(editorTextZoom) = prefs.edit().putFloat(EDITOR_TEXT_ZOOM, editorTextZoom).apply()

    fun saveFolderViewType(path: String, value: Int) {
        if (path.isEmpty()) {
            viewType = value
        } else {
            prefs.edit().putInt(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault()), value).apply()
        }
    }

    fun getFolderViewType(path: String) = prefs.getInt(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault()), viewType)

    fun removeFolderViewType(path: String) {
        prefs.edit().remove(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault())).apply()
    }

    fun hasCustomViewType(path: String) = prefs.contains(VIEW_TYPE_PREFIX + path.lowercase(Locale.getDefault()))

    var fileColumnCnt: Int
        get() = prefs.getInt(getFileColumnsField(), getDefaultFileColumnCount())
        set(fileColumnCnt) = prefs.edit().putInt(getFileColumnsField(), fileColumnCnt).apply()

    private fun getFileColumnsField(): String {
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (isPortrait) {
            FILE_COLUMN_CNT
        } else {
            FILE_LANDSCAPE_COLUMN_CNT
        }
    }

    private fun getDefaultFileColumnCount(): Int {
        val isPortrait = context.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        return if (isPortrait) 3 else 5
    }

    var displayFilenames: Boolean
        get() = prefs.getBoolean(DISPLAY_FILE_NAMES, true)
        set(displayFilenames) = prefs.edit().putBoolean(DISPLAY_FILE_NAMES, displayFilenames).apply()

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit().putInt(SHOW_TABS, showTabs).apply()

    var wasStorageAnalysisTabAdded: Boolean
        get() = prefs.getBoolean(WAS_STORAGE_ANALYSIS_TAB_ADDED, false)
        set(wasStorageAnalysisTabAdded) = prefs.edit().putBoolean(WAS_STORAGE_ANALYSIS_TAB_ADDED, wasStorageAnalysisTabAdded).apply()

    //Goodwy
    var showFolderIcon: Boolean
        get() = prefs.getBoolean(SHOW_FOLDER_ICON, true)
        set(showFolderIcon) = prefs.edit().putBoolean(SHOW_FOLDER_ICON, showFolderIcon).apply()

    var checkAppOpsService: Boolean
        get() = prefs.getBoolean(CHECK_APP_OPS_SERVICE, true)
        set(checkAppOpsService) = prefs.edit().putBoolean(CHECK_APP_OPS_SERVICE, checkAppOpsService).apply()

    var showHomeButton: Boolean
        get() = prefs.getBoolean(SHOW_HOME_BUTTON, true)
        set(showHomeButton) = prefs.edit().putBoolean(SHOW_HOME_BUTTON, showHomeButton).apply()

    var lastFolder: String
        get(): String {
            var path = prefs.getString(LAST_FOLDER, "")!!
            if (path.isEmpty() || !File(path).isDirectory) {
                path = getInternalStoragePath()
                lastFolder = path
            }
            return path
        }
        set(lastFolder) = prefs.edit().putString(LAST_FOLDER, lastFolder).apply()

    var defaultFolder: Int
        get() = prefs.getInt(DEFAULT_FOLDER, FOLDER_LAST_USED)
        set(defaultFolder) = prefs.edit().putInt(DEFAULT_FOLDER, defaultFolder).apply()

    private var showExpandedDetails: Boolean
        get() = prefs.getBoolean(SHOW_EXPANDED_DETAILS, false)
        set(showExpandedDetails) = prefs.edit().putBoolean(SHOW_EXPANDED_DETAILS, showExpandedDetails).apply()

    fun saveExpandedDetails(volumeName: String, value: Boolean) {
        prefs.edit().putBoolean(SHOW_EXPANDED_DETAILS_PREFIX + volumeName.lowercase(Locale.getDefault()), value).apply()
    }

    fun getExpandedDetails(volumeName: String) = prefs.getBoolean(SHOW_EXPANDED_DETAILS_PREFIX + volumeName.lowercase(Locale.getDefault()), showExpandedDetails)

    var showOnlyFilename: Boolean
        get() = prefs.getBoolean(SHOW_ONLY_FILENAME, false)
        set(showOnlyFilename) = prefs.edit().putBoolean(SHOW_ONLY_FILENAME, showOnlyFilename).apply()

    var queryLimitRecent: Int
        get() = prefs.getInt(QUERY_LIMIT_RECENT, QUERY_LIMIT_MEDIUM_VALUE)
        set(queryLimitRecent) = prefs.edit { putInt(QUERY_LIMIT_RECENT, queryLimitRecent) }

    //Swipe
    var swipeRightAction: Int
        get() = prefs.getInt(SWIPE_RIGHT_ACTION, SWIPE_ACTION_COPY)
        set(swipeRightAction) = prefs.edit().putInt(SWIPE_RIGHT_ACTION, swipeRightAction).apply()

    var swipeLeftAction: Int
        get() = prefs.getInt(SWIPE_LEFT_ACTION, SWIPE_ACTION_DELETE)
        set(swipeLeftAction) = prefs.edit().putInt(SWIPE_LEFT_ACTION, swipeLeftAction).apply()

    var swipeVibration: Boolean
        get() = prefs.getBoolean(SWIPE_VIBRATION, true)
        set(swipeVibration) = prefs.edit().putBoolean(SWIPE_VIBRATION, swipeVibration).apply()

    var swipeRipple: Boolean
        get() = prefs.getBoolean(SWIPE_RIPPLE, false)
        set(swipeRipple) = prefs.edit().putBoolean(SWIPE_RIPPLE, swipeRipple).apply()

    // App-scoped "default app" per file category (text/image/audio/video/pdf/word/excel/ppt).
    // Stored as just a package name; an empty string means "not set, ask every time". This is
    // entirely separate from Android's system-wide default-app settings.
    fun getDefaultOpenApp(category: String): String =
        prefs.getString(PREFIX_DEFAULT_OPEN_APP + category, "") ?: ""

    fun setDefaultOpenApp(category: String, packageName: String) {
        prefs.edit().putString(PREFIX_DEFAULT_OPEN_APP + category, packageName).apply()
    }

    // Optional whitelist of packages to show in the "default open app" picker — lets the user
    // narrow a long installed-apps list down to just the handful they actually care about. An
    // empty set means "no filter set up yet", in which case every installed app is shown.
    var defaultOpenAppsFilter: Set<String>
        get() = prefs.getStringSet(DEFAULT_OPEN_APPS_FILTER, emptySet()) ?: emptySet()
        set(value) = prefs.edit().putStringSet(DEFAULT_OPEN_APPS_FILTER, value).apply()

    // Persisted cache of every installed app's (packageName, label), built by enumerating every
    // installed package — a genuinely slow operation on a phone with a lot of apps, made slower
    // still by deliberately running it at low thread priority to avoid ANRs (see
    // DefaultOpenAppsDialog.kt). Persisting it means that cost is only paid once ever (across
    // app restarts too), instead of on every cold app launch. Stored as "pkg\u0001label" entries
    // (an ASCII control character as separator, since app labels can contain almost anything a
    // pipe/comma/etc. delimiter might collide with, but never that character).
    var cachedInstalledApps: List<Pair<String, String>>
        get() {
            val raw = prefs.getStringSet(CACHED_INSTALLED_APPS, null) ?: return emptyList()
            return raw.mapNotNull { entry ->
                val parts = entry.split(ENTRY_SEPARATOR, limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else null
            }
        }
        set(value) {
            val raw = value.map { (pkg, label) -> "$pkg$ENTRY_SEPARATOR$label" }.toHashSet()
            prefs.edit().putStringSet(CACHED_INSTALLED_APPS, raw).apply()
        }
}
