package com.goodwy.filemanager.activities

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
import com.bumptech.glide.request.RequestOptions
import com.bumptech.glide.signature.ObjectKey
import com.goodwy.commons.dialogs.ConfirmationDialog
import com.goodwy.commons.extensions.beGone
import com.goodwy.commons.extensions.beVisible
import com.goodwy.commons.extensions.beVisibleIf
import com.goodwy.commons.extensions.formatDate
import com.goodwy.commons.extensions.formatSize
import com.goodwy.commons.extensions.getFilePlaceholderDrawables
import com.goodwy.commons.extensions.getProperTextColor
import com.goodwy.commons.extensions.getTimeFormat
import com.goodwy.commons.extensions.toast
import com.goodwy.commons.extensions.viewBinding
import com.goodwy.commons.helpers.NavigationIcon
import com.goodwy.commons.helpers.ensureBackgroundThread
import com.goodwy.filemanager.R
import com.goodwy.filemanager.databinding.ActivityTrashBinding
import com.goodwy.filemanager.databinding.ItemTrashBinding
import com.goodwy.filemanager.extensions.config
import com.goodwy.filemanager.helpers.TrashEntry
import com.goodwy.filemanager.helpers.TrashManager
import java.io.File
import java.util.Locale

class TrashActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityTrashBinding::inflate)
    private var entries = listOf<TrashEntry>()
    private val fileDrawables by lazy { getFilePlaceholderDrawables(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        useChangeAutoTheme = false
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        binding.trashList.layoutManager = LinearLayoutManager(this)
        binding.trashToolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.empty_trash -> {
                    confirmEmptyTrash()
                    true
                }
                R.id.restore_all -> {
                    restoreAll()
                    true
                }
                else -> false
            }
        }

        loadEntries()
    }

    override fun onResume() {
        super.onResume()
        setupTopAppBar(binding.trashAppbar, NavigationIcon.Arrow)
    }

    private fun loadEntries() {
        ensureBackgroundThread {
            val fresh = TrashManager.getEntries(this).sortedByDescending { it.deletedAt }
            runOnUiThread {
                entries = fresh
                binding.trashList.adapter = TrashAdapter()
                binding.trashPlaceholder.beVisibleIf(fresh.isEmpty())
                binding.trashList.beVisibleIf(fresh.isNotEmpty())
            }
        }
    }

    private fun confirmEmptyTrash() {
        if (entries.isEmpty()) {
            return
        }
        ConfirmationDialog(this, "", R.string.confirm_empty_trash, R.string.ok, 0) {
            ensureBackgroundThread {
                TrashManager.emptyTrash(this)
                runOnUiThread {
                    toast(R.string.deleted_forever)
                    loadEntries()
                }
            }
        }
    }

    private fun restoreEntry(entry: TrashEntry) {
        ensureBackgroundThread {
            val success = TrashManager.restore(this, entry)
            runOnUiThread {
                toast(if (success) R.string.restore_successful else R.string.unknown_error_occurred)
                loadEntries()
            }
        }
    }

    private fun restoreAll() {
        if (entries.isEmpty()) {
            return
        }
        ensureBackgroundThread {
            val snapshot = entries
            var allSucceeded = true
            snapshot.forEach { entry ->
                if (!TrashManager.restore(this, entry)) {
                    allSucceeded = false
                }
            }
            runOnUiThread {
                toast(if (allSucceeded) R.string.restore_all_successful else R.string.unknown_error_occurred)
                loadEntries()
            }
        }
    }

    private fun deleteEntryForever(entry: TrashEntry) {
        ConfirmationDialog(this, "", R.string.confirm_delete_forever, R.string.ok, 0) {
            ensureBackgroundThread {
                TrashManager.deleteForever(this, entry)
                runOnUiThread {
                    toast(R.string.deleted_forever)
                    loadEntries()
                }
            }
        }
    }

    private inner class TrashAdapter : RecyclerView.Adapter<TrashAdapter.TrashViewHolder>() {
        inner class TrashViewHolder(val itemBinding: ItemTrashBinding) : RecyclerView.ViewHolder(itemBinding.root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): TrashViewHolder {
            val itemBinding = ItemTrashBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            return TrashViewHolder(itemBinding)
        }

        override fun getItemCount() = entries.size

        override fun onBindViewHolder(holder: TrashViewHolder, position: Int) {
            val entry = entries[position]
            val name = File(entry.originalPath).name
            val iconColor = getProperTextColor()
            holder.itemBinding.apply {
                trashItemName.text = name
                trashItemRestore.setColorFilter(iconColor)
                trashItemDelete.setColorFilter(iconColor)
                val dateText = entry.deletedAt.formatDate(this@TrashActivity, config.dateFormat, getTimeFormat())
                trashItemSubtitle.text = "${entry.size.formatSize()} · $dateText"

                Glide.with(this@TrashActivity).clear(trashItemIcon)
                trashItemIcon.clearColorFilter()

                if (entry.isDirectory) {
                    // 文件夹本身没有缩略图，用固定图标即可
                    trashItemIcon.setImageResource(R.drawable.ic_folder_vector)
                    trashItemIcon.setColorFilter(iconColor)
                } else {
                    // 缩略图必须从回收站里的真实物理文件加载，因为原路径上的文件已经不存在了
                    val trashFile = TrashManager.trashFile(this@TrashActivity, entry)
                    val fallback = fileDrawables.getOrElse(
                        key = name.substringAfterLast('.').lowercase(Locale.getDefault()),
                        defaultValue = { resources.getDrawable(R.drawable.ic_file_vector, theme) }
                    )

                    val options = RequestOptions()
                        .signature(ObjectKey(entry.trashFileName))
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .error(fallback)
                        .transform(CenterCrop(), RoundedCorners(20))

                    Glide.with(this@TrashActivity)
                        .load(trashFile)
                        .transition(DrawableTransitionOptions.withCrossFade())
                        .apply(options)
                        .into(trashItemIcon)
                }

                trashItemRestore.setOnClickListener { restoreEntry(entry) }
                trashItemDelete.setOnClickListener { deleteEntryForever(entry) }
            }
        }
    }
}
