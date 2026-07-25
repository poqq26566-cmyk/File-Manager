package com.goodwy.filemanager.extensions

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
