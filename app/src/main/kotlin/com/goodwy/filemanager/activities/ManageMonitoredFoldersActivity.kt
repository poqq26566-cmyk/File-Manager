package com.goodwy.filemanager.activities

import android.graphics.Paint
import android.os.Bundle
import com.goodwy.commons.dialogs.FilePickerDialog
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.getProperPrimaryColor
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.interfaces.RefreshRecyclerViewListener
import com.goodwy.filemanager.R
import com.goodwy.filemanager.adapters.ManageMonitoredFoldersAdapter
import com.goodwy.filemanager.databinding.ActivityMonitoredFoldersBinding
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.services.FileMonitorService

class ManageMonitoredFoldersActivity : SimpleActivity(), RefreshRecyclerViewListener {
    private val binding by viewBinding(ActivityMonitoredFoldersBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        updateMonitoredFolders()
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(manageMonitoredFoldersList))
            setupMaterialScrollListener(binding.manageMonitoredFoldersList, binding.manageMonitoredFoldersAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageMonitoredFoldersAppbar, NavigationIcon.Arrow)
    }

    private fun setupOptionsMenu() {
        binding.manageMonitoredFoldersToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_monitored_folder -> addMonitoredFolder()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateMonitoredFolders() {
        binding.apply {
            val folders = ArrayList<String>()
            config.monitoredFolders.mapTo(folders) { it }
            manageMonitoredFoldersPlaceholder.beVisibleIf(folders.isEmpty())
            manageMonitoredFoldersPlaceholder.setTextColor(getProperTextColor())

            manageMonitoredFoldersPlaceholder2.apply {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                beVisibleIf(folders.isEmpty())
                setTextColor(getProperPrimaryColor())
                setOnClickListener {
                    addMonitoredFolder()
                }
            }

            ManageMonitoredFoldersAdapter(this@ManageMonitoredFoldersActivity, folders, this@ManageMonitoredFoldersActivity, manageMonitoredFoldersList) { }.apply {
                manageMonitoredFoldersList.adapter = this
            }
        }
    }

    override fun refreshItems() {
        updateMonitoredFolders()
    }

    private fun addMonitoredFolder() {
        FilePickerDialog(this, pickFile = false, showHidden = config.shouldShowHidden(), canAddShowHiddenButton = true, useAccentColor = true) {
            config.addMonitoredFolder(it)
            updateMonitoredFolders()
            // Pick up the newly-added folder immediately if the service is already running,
            // instead of waiting for the next app restart.
            if (config.fileMonitorEnabled) {
                FileMonitorService.restartIfRunning(this)
            }
        }
    }
}
