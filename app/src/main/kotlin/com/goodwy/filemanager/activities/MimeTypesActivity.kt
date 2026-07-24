package com.goodwy.filemanager.activities

import android.app.SearchManager
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.view.Menu
import android.view.MenuItem
import android.content.pm.PackageManager
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuItemCompat
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.tabs.TabLayout
import com.goodwy.commons.dialogs.RadioGroupDialog
import com.goodwy.commons.extensions.*
import com.goodwy.commons.helpers.*
import com.goodwy.commons.models.FileDirItem
import com.goodwy.commons.models.RadioItem
import com.goodwy.commons.views.MyGridLayoutManager
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.filemanager.R
import com.goodwy.filemanager.adapters.ItemsAdapter
import com.goodwy.filemanager.databinding.ActivityMimetypesBinding
import com.goodwy.filemanager.dialogs.ChangeSortingDialog
import com.goodwy.filemanager.dialogs.ChangeViewTypeDialog
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.extensions.isPathInHiddenFolder
import com.goodwy.filemanager.extensions.tryOpenPathIntent
import com.goodwy.filemanager.helpers.*
import com.goodwy.filemanager.interfaces.ItemOperationsListener
import com.goodwy.filemanager.models.ListItem
import java.util.Locale

class MimeTypesActivity : SimpleActivity(), ItemOperationsListener {
    companion object {
        // Cache the last loaded list per volume+mimetype so re-opening a category shows something
        // instantly instead of a blank screen, while a fresh query silently refreshes it underneath.
        private val itemsCache = mutableMapOf<String, ArrayList<ListItem>>()
    }

    private val binding by viewBinding(ActivityMimetypesBinding::inflate)
    private var isSearchOpen = false
    private var currentMimeType = ""
    private var lastSearchedText = ""
    private var searchMenuItem: MenuItem? = null
    private var zoomListener: MyRecyclerView.MyZoomListener? = null
    private var storedItems = ArrayList<ListItem>()
    private var currentViewType = VIEW_TYPE_LIST
    private var currentVolume = if (isQPlus()) PRIMARY_VOLUME_NAME else PRIMARY_VOLUME_NAME_OLD
    private var allInstallItems = ArrayList<ListItem>()
    private var installTabIndex = 0 // 0 = not installed, 1 = installed
    @Volatile private var isFetching = false

    private val cacheKey get() = "$currentVolume:$currentMimeType"

    override fun onCreate(savedInstanceState: Bundle?) {
        useChangeAutoTheme = false
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        refreshMenuItems()
        binding.apply {
            setupEdgeToEdge(padBottomImeAndSystem = listOf(mimetypesList))
            setupMaterialScrollListener(binding.mimetypesList, binding.mimetypesAppbar)
        }

        currentMimeType = intent.getStringExtra(SHOW_MIMETYPE) ?: return
        currentVolume = intent.getStringExtra(VOLUME_NAME) ?: currentVolume
        binding.mimetypesToolbar.title = getString(
            when (currentMimeType) {
                IMAGES -> R.string.images
                VIDEOS -> R.string.videos
                AUDIO -> R.string.audio
                DOCUMENTS -> R.string.documents
                ARCHIVES -> R.string.archives
                INSTALL_PACKAGES -> R.string.install_packages
                OTHERS -> R.string.others
                else -> {
                    toast(R.string.unknown_error_occurred)
                    finish()
                    return
                }
            }
        )

        // Show the cached list from last time immediately, but do the sort/adapter setup off the
        // main thread first — sorting hundreds of items can be slow if the comparator touches the
        // filesystem per item (size/date), and doing that synchronously in onCreate blocks input
        // (including Back) until it finishes.
        itemsCache[cacheKey]?.let { cached ->
            ensureBackgroundThread {
                val sorted = ArrayList(cached)
                FileDirItem.sorting = config.getFolderSorting(currentMimeType)
                sorted.sort()
                runOnUiThread {
                    addItems(sorted)
                }
            }
        }

        ensureBackgroundThread {
            reFetchItems()
        }

        setupInstallTabs()

        binding.apply {
            mimetypesFastscroller.updateColors(getProperPrimaryColor())
            mimetypesPlaceholder.setTextColor(getProperTextColor())
            mimetypesPlaceholder2.setTextColor(getProperTextColor())
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.mimetypesAppbar, NavigationIcon.Arrow, searchMenuItem = searchMenuItem)

        if (currentMimeType.isNotEmpty() && storedItems.isEmpty()) {
            ensureBackgroundThread {
                reFetchItems()
            }
        }
    }

    private fun refreshMenuItems() {
        val currentViewType = config.getFolderViewType(currentMimeType)

        binding.mimetypesToolbar.menu.apply {
            findItem(R.id.toggle_filename).isVisible = currentViewType == VIEW_TYPE_GRID

            findItem(R.id.temporarily_show_hidden).isVisible = !config.shouldShowHidden()
            findItem(R.id.stop_showing_hidden).isVisible = config.temporarilyShowHidden

            findItem(R.id.column_count).isVisible = currentViewType == VIEW_TYPE_GRID
        }
    }

    private fun setupOptionsMenu() {
        setupSearch(binding.mimetypesToolbar.menu)
        binding.mimetypesToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.sort -> showSortingDialog()
                R.id.toggle_filename -> toggleFilenameVisibility()
                R.id.change_view_type -> changeViewType()
                R.id.temporarily_show_hidden -> tryToggleTemporarilyShowHidden()
                R.id.stop_showing_hidden -> tryToggleTemporarilyShowHidden()
                R.id.column_count -> changeColumnCount()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    override fun refreshFragment() {
        reFetchItems()
    }

    override fun deleteFiles(files: ArrayList<FileDirItem>) {
        deleteFiles(files, false) {
            if (!it) {
                runOnUiThread {
                    toast(R.string.unknown_error_occurred)
                }
            }
        }
    }

    override fun selectedPaths(paths: ArrayList<String>) {}

    fun searchQueryChanged(text: String) {
        val normalizedText = text.normalizeString()
        val searchNormalizedText = normalizedText.trim()
        lastSearchedText = searchNormalizedText
        when {
            searchNormalizedText.isEmpty() -> {
                binding.apply {
                    mimetypesFastscroller.beVisible()
                    getRecyclerAdapter()?.updateItems(storedItems)
                    mimetypesPlaceholder.beGoneIf(storedItems.isNotEmpty())
                    mimetypesPlaceholder2.beGone()
                }
            }

            searchNormalizedText.length == 1 -> {
                binding.apply {
                    mimetypesFastscroller.beGone()
                    mimetypesPlaceholder.beVisible()
                    mimetypesPlaceholder2.beVisible()
                }
            }

            else -> {
                ensureBackgroundThread {
                    if (lastSearchedText != searchNormalizedText) {
                        return@ensureBackgroundThread
                    }

                    val listItems = storedItems.filter {
                        it.name.normalizeString().contains(searchNormalizedText, true)
                    } as ArrayList<ListItem>

                    runOnUiThread {
                        getRecyclerAdapter()?.updateItems(listItems, text)
                        binding.apply {
                            mimetypesFastscroller.beVisibleIf(listItems.isNotEmpty())
                            mimetypesPlaceholder.beVisibleIf(listItems.isEmpty())
                            mimetypesPlaceholder2.beGone()
                        }
                    }
                }
            }
        }
    }

    override fun setupDateTimeFormat() {}

    override fun setupFontSize() {}

    override fun toggleFilenameVisibility() {
        config.displayFilenames = !config.displayFilenames
        getRecyclerAdapter()?.updateDisplayFilenamesInGrid()
    }

    private fun changeColumnCount() {
        val items = ArrayList<RadioItem>()
        for (i in 1..MAX_COLUMN_COUNT) {
            items.add(RadioItem(i, resources.getQuantityString(R.plurals.column_counts, i, i)))
        }

        val currentColumnCount = config.fileColumnCnt
        RadioGroupDialog(this, items, config.fileColumnCnt) {
            val newColumnCount = it as Int
            if (currentColumnCount != newColumnCount) {
                config.fileColumnCnt = newColumnCount
                columnCountChanged()
            }
        }
    }

    fun increaseColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            config.fileColumnCnt += 1
            columnCountChanged()
        }
    }

    fun reduceColumnCount() {
        if (currentViewType == VIEW_TYPE_GRID) {
            config.fileColumnCnt -= 1
            columnCountChanged()
        }
    }

    override fun columnCountChanged() {
        (binding.mimetypesList.layoutManager as MyGridLayoutManager).spanCount = config.fileColumnCnt
        refreshMenuItems()
        getRecyclerAdapter()?.apply {
            notifyItemRangeChanged(0, listItems.size)
        }
    }

    override fun finishActMode() {}

    private fun setupSearch(menu: Menu) {
        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(R.id.search)
        (searchMenuItem!!.actionView as SearchView).apply {
            setSearchableInfo(searchManager.getSearchableInfo(componentName))
            isSubmitButtonEnabled = false
            queryHint = getString(R.string.search)
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(query: String) = false

                override fun onQueryTextChange(newText: String): Boolean {
                    if (isSearchOpen) {
                        searchQueryChanged(newText)
                    }
                    return true
                }
            })
        }

        MenuItemCompat.setOnActionExpandListener(searchMenuItem, object : MenuItemCompat.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem?): Boolean {
                isSearchOpen = true
                searchOpened()
                return true
            }

            override fun onMenuItemActionCollapse(item: MenuItem?): Boolean {
                isSearchOpen = false
                searchClosed()
                return true
            }
        })
    }

    fun searchOpened() {
        isSearchOpen = true
        lastSearchedText = ""
    }

    fun searchClosed() {
        isSearchOpen = false
        lastSearchedText = ""
        searchQueryChanged("")
    }

    private fun buildMimeTypeSelection(): Pair<String, Array<String>> {
        val col = MediaStore.Files.FileColumns.MIME_TYPE
        val selection = StringBuilder()
        val args = ArrayList<String>()

        fun likePrefix(prefix: String) {
            if (selection.isNotEmpty()) selection.append(" OR ")
            selection.append("$col LIKE ?")
            args.add("$prefix/%")
        }

        fun inList(values: List<String>) {
            if (values.isEmpty()) return
            if (selection.isNotEmpty()) selection.append(" OR ")
            selection.append("$col IN (${values.joinToString(",") { "?" }})")
            args.addAll(values)
        }

        fun notLikePrefix(prefix: String) {
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append("$col NOT LIKE ?")
            args.add("$prefix/%")
        }

        fun notInList(values: List<String>) {
            if (values.isEmpty()) return
            if (selection.isNotEmpty()) selection.append(" AND ")
            selection.append("$col NOT IN (${values.joinToString(",") { "?" }})")
            args.addAll(values)
        }

        when (currentMimeType) {
            IMAGES -> likePrefix("image")
            VIDEOS -> likePrefix("video")
            AUDIO -> {
                likePrefix("audio")
                inList(extraAudioMimeTypes)
            }

            DOCUMENTS -> {
                likePrefix("text")
                inList(extraDocumentMimeTypes)
            }

            ARCHIVES -> inList(archiveMimeTypes)
            INSTALL_PACKAGES -> inList(installPackageMimeTypes)
            OTHERS -> {
                notLikePrefix("image")
                notLikePrefix("video")
                notLikePrefix("audio")
                notLikePrefix("text")
                notInList(extraAudioMimeTypes + extraDocumentMimeTypes + archiveMimeTypes + installPackageMimeTypes)
            }
        }

        // MIME_TYPE can be NULL for some rows (e.g. folders); none of our categories want those.
        val fullSelection = "$col IS NOT NULL" + if (selection.isNotEmpty()) " AND (${selection})" else ""
        return Pair(fullSelection, args.toTypedArray())
    }

    private fun getProperFileDirItems(callback: (ArrayList<FileDirItem>) -> Unit) {
        val fileDirItems = ArrayList<FileDirItem>()
        val showHidden = config.shouldShowHidden()
        val uri = MediaStore.Files.getContentUri(currentVolume)
        val projection = arrayOf(
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATA,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.DATE_MODIFIED
        )

        // Filter with a WHERE clause so the query only returns rows for this category, instead of
        // pulling every single file on the device (which used to be the case, and got very slow
        // on storage with lots of files since the whole table was scanned and transferred just to
        // throw most rows away in Kotlin afterwards).
        val (selection, selectionArgs) = buildMimeTypeSelection()

        try {
            val cursor = contentResolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                while (it.moveToNext()) {
                    // Bail out as soon as the screen is gone (e.g. user pressed back) instead of
                    // wastefully finishing the scan and throwing the result away — on a loaded
                    // device this extra work was competing with the UI thread for CPU time and
                    // made navigating back feel like a freeze.
                    if (isDestroyed || isFinishing) {
                        break
                    }

                    try {
                        val fullMimetype = it.getStringValue(MediaStore.Files.FileColumns.MIME_TYPE)?.lowercase(Locale.getDefault()) ?: continue
                        val name = it.getStringValue(MediaStore.Files.FileColumns.DISPLAY_NAME)
                        val path = it.getStringValue(MediaStore.Files.FileColumns.DATA)

                        val isHiddenFile = name.startsWith(".")
                        if (!showHidden && (isHiddenFile || path.isPathInHiddenFolder())) {
                            continue
                        }

                        val size = it.getLongValue(MediaStore.Files.FileColumns.SIZE)
                        if (size == 0L) {
                            continue
                        }

                        val lastModified = it.getLongValue(MediaStore.Files.FileColumns.DATE_MODIFIED) * 1000
                        fileDirItems.add(FileDirItem(path, name, false, 0, size, lastModified))
                    } catch (e: Exception) {
                    }
                }
            }
        } catch (e: Exception) {
            showErrorToast(e)
        }

        callback(fileDirItems)
    }

    private fun addItems(items: ArrayList<ListItem>) {
        FileDirItem.sorting = config.getFolderSorting(currentMimeType)
        items.sort()

        if (isDestroyed || isFinishing) {
            return
        }

        storedItems = items
        ItemsAdapter(
            this as SimpleActivity,
            storedItems,
            this,
            binding.mimetypesList,
            false,
            null
        ) {
            tryOpenPathIntent((it as ListItem).path, false)
        }.apply {
            setupZoomListener(zoomListener)
            binding.mimetypesList.adapter = this
        }

        if (areSystemAnimationsEnabled) {
            binding.mimetypesList.scheduleLayoutAnimation()
        }

        binding.mimetypesProgressBar.beGone()
        binding.mimetypesPlaceholder.beVisibleIf(items.isEmpty())
    }

    private fun getRecyclerAdapter() = binding.mimetypesList.adapter as? ItemsAdapter

    private fun showSortingDialog() {
        ChangeSortingDialog(this, currentMimeType) {
            recreateList()
        }
    }

    private fun changeViewType() {
        ChangeViewTypeDialog(this, currentMimeType, true) {
            recreateList()
            setupLayoutManager()
            refreshMenuItems()
        }
    }

    private fun reFetchItems() {
        if (isFetching) {
            return
        }
        isFetching = true

        runOnUiThread {
            if (storedItems.isEmpty()) {
                binding.mimetypesProgressBar.beVisible()
                binding.mimetypesPlaceholder.beGone()
            }
        }

        // Safety net: if a category genuinely has a huge number of files and the query is just
        // taking a while, let the user know it's still working instead of looking frozen.
        val stillLoadingHandler = android.os.Handler(android.os.Looper.getMainLooper())
        val stillLoadingRunnable = Runnable {
            if (isFetching && !isDestroyed && !isFinishing) {
                toast(R.string.still_loading_files)
            }
        }
        stillLoadingHandler.postDelayed(stillLoadingRunnable, 10000L)

        getProperFileDirItems { fileDirItems ->
            stillLoadingHandler.removeCallbacks(stillLoadingRunnable)
            val listItems = getListItemsFromFileDirItems(fileDirItems)

            if (currentMimeType == INSTALL_PACKAGES) {
                allInstallItems = listItems
                val filtered = filterInstallItems(listItems)
                FileDirItem.sorting = config.getFolderSorting(currentMimeType)
                filtered.sort()
                runOnUiThread {
                    addItems(filtered)
                    if (currentViewType != config.getFolderViewType(currentMimeType)) {
                        setupLayoutManager()
                    }
                    isFetching = false
                }
            } else {
                itemsCache[cacheKey] = listItems
                FileDirItem.sorting = config.getFolderSorting(currentMimeType)
                listItems.sort()
                runOnUiThread {
                    addItems(listItems)
                    if (currentViewType != config.getFolderViewType(currentMimeType)) {
                        setupLayoutManager()
                    }
                    isFetching = false
                }
            }
        }
    }

    private fun filterInstallItems(items: ArrayList<ListItem>): ArrayList<ListItem> {
        val wantInstalled = installTabIndex == 1
        return items.filter { isPackageInstalled(it.path) == wantInstalled } as ArrayList<ListItem>
    }

    private fun isPackageInstalled(path: String): Boolean {
        return try {
            val archiveInfo = packageManager.getPackageArchiveInfo(path, 0) ?: return false
            packageManager.getPackageInfo(archiveInfo.packageName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        } catch (e: Exception) {
            false
        }
    }

    private fun setupInstallTabs() {
        binding.installPackagesTabs.beVisibleIf(currentMimeType == INSTALL_PACKAGES)
        if (currentMimeType != INSTALL_PACKAGES) {
            return
        }

        binding.installPackagesTabs.apply {
            setBackgroundColor(getProperBackgroundColor())
            if (tabCount == 0) {
                addTab(newTab().setText(R.string.not_installed))
                addTab(newTab().setText(R.string.installed))
                setTabTextColors(getProperTextColor(), getProperPrimaryColor())
                setSelectedTabIndicatorColor(getProperPrimaryColor())

                addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                    override fun onTabSelected(tab: TabLayout.Tab) {
                        installTabIndex = tab.position
                        ensureBackgroundThread {
                            val filtered = filterInstallItems(allInstallItems)
                            runOnUiThread {
                                addItems(filtered)
                            }
                        }
                    }

                    override fun onTabUnselected(tab: TabLayout.Tab) {}
                    override fun onTabReselected(tab: TabLayout.Tab) {}
                })
            }
        }
    }

    private fun recreateList() {
        val listItems = getRecyclerAdapter()?.listItems
        if (listItems != null) {
            addItems(listItems as ArrayList<ListItem>)
        }
    }

    private fun setupLayoutManager() {
        if (config.getFolderViewType(currentMimeType) == VIEW_TYPE_GRID) {
            currentViewType = VIEW_TYPE_GRID
            setupGridLayoutManager()
        } else {
            currentViewType = VIEW_TYPE_LIST
            setupListLayoutManager()
        }

        binding.mimetypesList.adapter = null
        initZoomListener()
        addItems(storedItems)
    }

    private fun setupGridLayoutManager() {
        val layoutManager = binding.mimetypesList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = config.fileColumnCnt

        layoutManager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return if (getRecyclerAdapter()?.isASectionTitle(position) == true) {
                    layoutManager.spanCount
                } else {
                    1
                }
            }
        }
    }

    private fun setupListLayoutManager() {
        val layoutManager = binding.mimetypesList.layoutManager as MyGridLayoutManager
        layoutManager.spanCount = 1
        zoomListener = null
    }

    private fun initZoomListener() {
        if (config.getFolderViewType(currentMimeType) == VIEW_TYPE_GRID) {
            val layoutManager = binding.mimetypesList.layoutManager as MyGridLayoutManager
            zoomListener = object : MyRecyclerView.MyZoomListener {
                override fun zoomIn() {
                    if (layoutManager.spanCount > 1) {
                        reduceColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }

                override fun zoomOut() {
                    if (layoutManager.spanCount < MAX_COLUMN_COUNT) {
                        increaseColumnCount()
                        getRecyclerAdapter()?.finishActMode()
                    }
                }
            }
        } else {
            zoomListener = null
        }
    }

    private fun tryToggleTemporarilyShowHidden() {
        if (config.temporarilyShowHidden) {
            toggleTemporarilyShowHidden(false)
        } else {
            handleHiddenFolderPasswordProtection {
                toggleTemporarilyShowHidden(true)
            }
        }
    }

    private fun toggleTemporarilyShowHidden(show: Boolean) {
        config.temporarilyShowHidden = show
        ensureBackgroundThread {
            reFetchItems()
        }
    }
}
