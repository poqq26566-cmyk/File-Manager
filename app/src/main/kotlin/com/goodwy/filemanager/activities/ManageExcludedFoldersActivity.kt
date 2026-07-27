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
import com.goodwy.filemanager.adapters.ManageExcludedFoldersAdapter
import com.goodwy.filemanager.databinding.ActivityExcludedFoldersBinding
import com.goodwy.filemanager.extensions.config

class ManageExcludedFoldersActivity : SimpleActivity(), RefreshRecyclerViewListener {
    private val binding by viewBinding(ActivityExcludedFoldersBinding::inflate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupOptionsMenu()
        updateExcludedFolders()
        binding.apply {
            setupEdgeToEdge(padBottomSystem = listOf(manageExcludedFoldersList))
            setupMaterialScrollListener(binding.manageExcludedFoldersList, binding.manageExcludedFoldersAppbar)
        }
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.manageExcludedFoldersAppbar, NavigationIcon.Arrow)
    }

    private fun setupOptionsMenu() {
        binding.manageExcludedFoldersToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.add_excluded_folder -> addExcludedFolder()
                else -> return@setOnMenuItemClickListener false
            }
            return@setOnMenuItemClickListener true
        }
    }

    private fun updateExcludedFolders() {
        binding.apply {
            val folders = ArrayList<String>()
            config.excludedFolders.mapTo(folders) { it }
            manageExcludedFoldersPlaceholder.beVisibleIf(folders.isEmpty())
            manageExcludedFoldersPlaceholder.setTextColor(getProperTextColor())

            manageExcludedFoldersPlaceholder2.apply {
                paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
                beVisibleIf(folders.isEmpty())
                setTextColor(getProperPrimaryColor())
                setOnClickListener {
                    addExcludedFolder()
                }
            }

            ManageExcludedFoldersAdapter(this@ManageExcludedFoldersActivity, folders, this@ManageExcludedFoldersActivity, manageExcludedFoldersList) { }.apply {
                manageExcludedFoldersList.adapter = this
            }
        }
    }

    override fun refreshItems() {
        updateExcludedFolders()
    }

    private fun addExcludedFolder() {
        FilePickerDialog(this, pickFile = false, showHidden = config.shouldShowHidden(), canAddShowHiddenButton = true, useAccentColor = true) {
            config.addExcludedFolder(it)
            updateExcludedFolders()
        }
    }
}
