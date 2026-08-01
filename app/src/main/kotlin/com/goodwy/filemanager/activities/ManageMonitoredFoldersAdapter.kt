package com.goodwy.filemanager.adapters

import android.view.*
import androidx.appcompat.widget.PopupMenu
import com.goodwy.commons.activities.BaseSimpleActivity
import com.goodwy.commons.adapters.MyRecyclerViewAdapter
import com.goodwy.commons.extensions.getPopupMenuTheme
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.setupViewBackground
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.goodwy.commons.views.MyRecyclerView
import com.goodwy.filemanager.R
import com.goodwy.filemanager.databinding.ItemManageFavoriteBinding
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.services.FileMonitorService

class ManageMonitoredFoldersAdapter(
    activity: BaseSimpleActivity, var folders: ArrayList<String>, val listener: RefreshRecyclerViewListener?,
    recyclerView: MyRecyclerView, itemClick: (Any) -> Unit
) : MyRecyclerViewAdapter(activity, recyclerView, itemClick) {

    private val config = activity.config

    init {
        setupDragListener(true)
    }

    override fun getActionMenuId() = R.menu.cab_remove_only

    override fun actionItemPressed(id: Int) {
        when (id) {
            R.id.cab_remove -> removeSelection()
        }
    }

    override fun getSelectableItemCount() = folders.size

    override fun getIsItemSelectable(position: Int) = true

    override fun getItemSelectionKey(position: Int) = folders.getOrNull(position)?.hashCode()

    override fun getItemKeyPosition(key: Int) = folders.indexOfFirst { it.hashCode() == key }

    override fun onActionModeCreated() {}

    override fun onActionModeDestroyed() {}

    override fun prepareActionMode(menu: Menu) {}

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        return createViewHolder(ItemManageFavoriteBinding.inflate(layoutInflater, parent, false).root)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val folder = folders[position]
        holder.bindView(folder, true, true) { itemView, layoutPosition ->
            setupView(itemView, folder, selectedKeys.contains(folder.hashCode()))
        }
        bindViewHolder(holder)
    }

    override fun getItemCount() = folders.size

    private fun setupView(view: View, folder: String, isSelected: Boolean) {
        ItemManageFavoriteBinding.bind(view).apply {
            root.setupViewBackground(activity)
            manageFavoriteTitle.apply {
                text = folder
                setTextColor(activity.getProperTextColor())
            }

            manageFavoriteHolder.isSelected = isSelected

            overflowMenuIcon.drawable.apply {
                mutate()
                setTint(activity.getProperTextColor())
            }

            overflowMenuIcon.setOnClickListener {
                showPopupMenu(overflowMenuAnchor, folder)
            }
        }
    }

    private fun showPopupMenu(view: View, folder: String) {
        finishActMode()
        val theme = activity.getPopupMenuTheme()
        val contextTheme = ContextThemeWrapper(activity, theme)

        PopupMenu(contextTheme, view, Gravity.END).apply {
            inflate(getActionMenuId())
            setOnMenuItemClickListener { item ->
                val eventTypeId = folder.hashCode()
                when (item.itemId) {
                    R.id.cab_remove -> {
                        executeItemMenuOperation(eventTypeId) {
                            removeSelection()
                        }
                    }
                }
                true
            }
            show()
        }
    }

    private fun executeItemMenuOperation(eventTypeId: Int, callback: () -> Unit) {
        selectedKeys.clear()
        selectedKeys.add(eventTypeId)
        callback()
    }

    private fun removeSelection() {
        val removeFolders = ArrayList<String>(selectedKeys.size)
        val positions = ArrayList<Int>()
        selectedKeys.forEach { key ->
            val position = folders.indexOfFirst { it.hashCode() == key }
            if (position != -1) {
                positions.add(position)

                val folder = getItemWithKey(key)
                if (folder != null) {
                    removeFolders.add(folder)
                    config.removeMonitoredFolder(folder)
                }
            }
        }

        positions.sortDescending()
        removeSelectedItems(positions)

        folders.removeAll(removeFolders.toSet())
        // The service watches whatever config.monitoredFolders is *at the moment it starts*,
        // so an edit while it's already running needs a restart to pick the change up.
        if (config.fileMonitorEnabled) {
            FileMonitorService.restartIfRunning(activity)
        }
        if (folders.isEmpty()) {
            listener?.refreshItems()
        }
    }

    private fun getItemWithKey(key: Int): String? = folders.firstOrNull { it.hashCode() == key }
}
