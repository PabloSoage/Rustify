// sharedUi/src/commonMain/kotlin/com/varuna/rustify/bridge/DownloadModels.kt
package com.varuna.rustify.bridge

// A download as the screens show it, moved out of DownloadManager.kt unchanged.

enum class DownloadStatus {
    QUEUED, RESOLVING, DOWNLOADING, COMPLETE, ERROR
}

data class DownloadTask(
    val id: String,
    val title: String,
    val artist: String,
    val status: DownloadStatus,
    val progress: Int = 0,
    val errorMessage: String? = null
)
