package com.videoextractor.app.model

data class ExtractedVideo(
    val title: String,
    val directUrl: String,
    val pageUrl: String,
    val platform: String,
    val engine: String,
    val ext: String = if (directUrl.lowercase().contains(".m3u8")) "m3u8" else "mp4",
    val thumbnailUrl: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val filesizeBytes: Long? = null,
) {
    val isHls: Boolean
        get() = ext.equals("m3u8", ignoreCase = true) || directUrl.lowercase().contains(".m3u8")
}

sealed interface ExtractResult {
    data class Success(val video: ExtractedVideo) : ExtractResult
    data class NeedsAuth(
        val platform: String,
        val pageUrl: String,
        val message: String,
    ) : ExtractResult
    data class NoVideo(val message: String) : ExtractResult
    data class Blocked(val message: String) : ExtractResult
    data class Failure(
        val message: String,
        val pageUrl: String? = null,
        val platform: String? = null,
    ) : ExtractResult
}

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Downloading(val progress: Float, val bytesRead: Long, val totalBytes: Long) : DownloadState
    data class Saved(val displayName: String, val uriString: String) : DownloadState
    data class Failed(val error: String) : DownloadState
}
