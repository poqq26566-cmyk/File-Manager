package com.goodwy.filemanager.helpers

import com.goodwy.filemanager.R

// One entry per row shown in Settings > "默认打开方式" (Default open apps).
// `queryMimeType` is what we use to ask the system which installed apps can handle this
// category, when building the app-picker list for that row.
data class OpenAppCategory(val key: String, val labelRes: Int, val queryMimeType: String)

fun defaultOpenAppCategories(): List<OpenAppCategory> = listOf(
    OpenAppCategory(OPEN_CATEGORY_TEXT, R.string.category_text, "text/plain"),
    OpenAppCategory(OPEN_CATEGORY_IMAGE, R.string.category_image, "image/*"),
    OpenAppCategory(OPEN_CATEGORY_AUDIO, R.string.category_audio, "audio/*"),
    OpenAppCategory(OPEN_CATEGORY_VIDEO, R.string.category_video, "video/*"),
    OpenAppCategory(OPEN_CATEGORY_PDF, R.string.category_pdf, "application/pdf"),
    OpenAppCategory(OPEN_CATEGORY_WORD, R.string.category_word, "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    OpenAppCategory(OPEN_CATEGORY_EXCEL, R.string.category_excel, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
    OpenAppCategory(OPEN_CATEGORY_PPT, R.string.category_ppt, "application/vnd.openxmlformats-officedocument.presentationml.presentation"),
)

// Maps an actual file's mime type (as detected from its extension) to one of the categories
// above, so we know which stored per-app-scoped preference — if any — applies when the user
// opens that specific file from within this file manager.
fun mimeTypeToOpenCategory(mimeType: String): String? {
    return when {
        mimeType.startsWith("text/") || mimeType == "application/json" -> OPEN_CATEGORY_TEXT
        mimeType.startsWith("image/") -> OPEN_CATEGORY_IMAGE
        mimeType.startsWith("audio/") -> OPEN_CATEGORY_AUDIO
        mimeType.startsWith("video/") -> OPEN_CATEGORY_VIDEO
        mimeType == "application/pdf" -> OPEN_CATEGORY_PDF
        mimeType == "application/msword" ||
            mimeType == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> OPEN_CATEGORY_WORD
        mimeType == "application/vnd.ms-excel" ||
            mimeType == "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> OPEN_CATEGORY_EXCEL
        mimeType == "application/vnd.ms-powerpoint" ||
            mimeType == "application/vnd.openxmlformats-officedocument.presentationml.presentation" -> OPEN_CATEGORY_PPT
        else -> null
    }
}
