package com.goodwy.filemanager.extensions

import android.content.Context

fun String.isZipFile() = endsWith(".zip", true)

fun String.isPathInHiddenFolder(): Boolean {
    val parts = split("/")
    for (i in 1 until parts.size - 1) {
        val part = parts[i]
        val isHidden = part.startsWith(".") && part != "." && part != ".." && part.isNotEmpty()
        if (isHidden) {
            return true
        }
    }
    return false
}

// Directory names whose entire contents are deliberately kept out of the storage categories.
// Encrypted-volume folders (DroidFS/gocryptfs and friends) sit in normal public storage, so
// MediaStore indexes them like anything else — but their contents are opaque ciphertext blobs
// with random names and no usable type, which only ever show up as noise in the counts and in
// the browsable lists.
private val excludedRootFolders = listOf("vault")

fun String.isPathInExcludedFolder(): Boolean {
    val parts = split("/")
    for (i in 1 until parts.size - 1) {
        if (excludedRootFolders.contains(parts[i].lowercase())) {
            return true
        }
    }
    return false
}

// User-managed version of the check above (Settings > 安全性 > 过滤文件夹): folders the user has
// explicitly picked are excluded the same way "vault" is, but only while the toggle is on. A path
// counts as excluded if it IS one of the picked folders, or lives anywhere underneath one.
fun String.isPathInUserExcludedFolder(context: Context): Boolean {
    val config = context.config
    if (!config.filterFoldersEnabled) {
        return false
    }

    val excluded = config.excludedFolders
    if (excluded.isEmpty()) {
        return false
    }

    return excluded.any { folder ->
        this == folder || this.startsWith("$folder/")
    }
}

fun String.isPathInAnyExcludedFolder(context: Context): Boolean = isPathInExcludedFolder() || isPathInUserExcludedFolder(context)
