package com.goodwy.filemanager.helpers

import android.content.Context
import com.goodwy.commons.models.FileDirItem
import com.goodwy.filemanager.extensions.config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TrashEntry(
    val trashFileName: String,
    val originalPath: String,
    val deletedAt: Long,
    val size: Long,
    val isDirectory: Boolean
)

/**
 * A simple recycle bin: deleted files/folders get copied into an app-private trash directory
 * (external-files-dir, so no extra permissions are needed) before the original is actually
 * deleted through the normal delete flow. Metadata (original path, delete time, size) is kept as
 * a small JSON blob in Config so entries survive app restarts.
 */
object TrashManager {

    fun trashDir(context: Context): File {
        val dir = File(context.getExternalFilesDir(null), "trash")
        if (!dir.exists()) {
            dir.mkdirs()
        }
        return dir
    }

    fun moveToTrash(context: Context, item: FileDirItem): Boolean {
        return try {
            val source = File(item.path)
            if (!source.exists()) {
                return false
            }

            val trashFileName = "${UUID.randomUUID()}_${source.name}"
            val destination = File(trashDir(context), trashFileName)

            if (source.isDirectory) {
                source.copyRecursively(destination, overwrite = true)
            } else {
                source.copyTo(destination, overwrite = true)
            }

            val entry = TrashEntry(
                trashFileName = trashFileName,
                originalPath = item.path,
                deletedAt = System.currentTimeMillis(),
                size = getSizeRecursively(destination),
                isDirectory = source.isDirectory
            )
            addEntry(context, entry)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun restore(context: Context, entry: TrashEntry): Boolean {
        return try {
            val trashFile = File(trashDir(context), entry.trashFileName)
            if (!trashFile.exists()) {
                removeEntry(context, entry.trashFileName)
                return false
            }

            var target = File(entry.originalPath)
            if (target.exists()) {
                // Don't clobber a file that already exists at the original path — restore next to
                // it with a distinguishing suffix instead.
                val parent = target.parentFile
                val baseName = target.nameWithoutExtension
                val ext = target.extension
                var index = 1
                while (target.exists()) {
                    val newName = if (ext.isNotEmpty()) "$baseName (restored $index).$ext" else "$baseName (restored $index)"
                    target = File(parent, newName)
                    index++
                }
            } else {
                target.parentFile?.mkdirs()
            }

            if (trashFile.isDirectory) {
                trashFile.copyRecursively(target, overwrite = true)
                trashFile.deleteRecursively()
            } else {
                trashFile.copyTo(target, overwrite = true)
                trashFile.delete()
            }

            removeEntry(context, entry.trashFileName)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun deleteForever(context: Context, entry: TrashEntry): Boolean {
        return try {
            val trashFile = File(trashDir(context), entry.trashFileName)
            if (trashFile.isDirectory) {
                trashFile.deleteRecursively()
            } else {
                trashFile.delete()
            }
            removeEntry(context, entry.trashFileName)
            true
        } catch (e: Exception) {
            false
        }
    }

    fun emptyTrash(context: Context) {
        try {
            trashDir(context).listFiles()?.forEach {
                if (it.isDirectory) it.deleteRecursively() else it.delete()
            }
        } catch (e: Exception) {
        }
        context.config.saveTrashMetadataRaw("[]")
    }

    fun getEntries(context: Context): List<TrashEntry> {
        val raw = context.config.getTrashMetadataRaw() ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            (0 until array.length()).map { i ->
                val obj = array.getJSONObject(i)
                TrashEntry(
                    trashFileName = obj.getString("trashFileName"),
                    originalPath = obj.getString("originalPath"),
                    deletedAt = obj.getLong("deletedAt"),
                    size = obj.getLong("size"),
                    isDirectory = obj.optBoolean("isDirectory", false)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getTotalSize(context: Context): Long = getEntries(context).sumOf { it.size }

    private fun addEntry(context: Context, entry: TrashEntry) {
        val entries = getEntries(context).toMutableList()
        entries.add(entry)
        saveEntries(context, entries)
    }

    private fun removeEntry(context: Context, trashFileName: String) {
        val entries = getEntries(context).filterNot { it.trashFileName == trashFileName }
        saveEntries(context, entries)
    }

    private fun saveEntries(context: Context, entries: List<TrashEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject()
            obj.put("trashFileName", entry.trashFileName)
            obj.put("originalPath", entry.originalPath)
            obj.put("deletedAt", entry.deletedAt)
            obj.put("size", entry.size)
            obj.put("isDirectory", entry.isDirectory)
            array.put(obj)
        }
        context.config.saveTrashMetadataRaw(array.toString())
    }

    private fun getSizeRecursively(file: File): Long {
        return if (file.isDirectory) {
            file.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        } else {
            file.length()
        }
    }
}
