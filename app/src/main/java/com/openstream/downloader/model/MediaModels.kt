package com.openstream.downloader.model

import java.util.UUID

enum class FormatType {
    MUXED,      // Video with integrated audio (e.g. 720p/360p)
    ADAPTIVE,   // Separate high-res video (1080p, 1440p, 4K, 8K) requiring muxing
    AUDIO_ONLY  // MP3 / M4A audio stream
}

data class StreamFormat(
    val id: String,
    val label: String,                // Full display label
    val qualityLabel: String,         // "4K", "1080p", "720p", "Audio"
    val resolution: String,           // "3840x2160", "1920x1080"
    val fps: Int = 30,                // 60, 50, 30
    val container: String = "MP4",    // "MP4", "WebM", "M4A"
    val formatType: FormatType,
    val videoUrl: String?,
    val audioUrl: String?,
    val videoCodec: String? = null,
    val audioCodec: String? = null,
    val estimatedSizeBytes: Long = -1L,
    val formattedSize: String = ""
) {
    val isVideo: Boolean
        get() = formatType != FormatType.AUDIO_ONLY

    val isHighFps: Boolean
        get() = fps >= 50
}

data class VideoMetadata(
    val originalUrl: String,
    val title: String,
    val uploader: String,
    val durationSeconds: Long,
    val thumbnailUrl: String,
    val availableFormats: List<StreamFormat>
)

data class DownloadProgress(
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val speedBytesPerSec: Long = 0L,
    val percentage: Int = 0,
    val currentStep: String = "" // "Downloading Video", "Downloading Audio", "Merging...", "Saved"
)

enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    MERGING,
    COMPLETED,
    PAUSED,
    FAILED,
    CANCELLED
}

data class DownloadTask(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val uploader: String = "",
    val thumbnailUrl: String = "",
    val selectedFormat: StreamFormat,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: DownloadProgress = DownloadProgress(),
    val savedUri: String? = null,
    val localFilePath: String? = null,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

data class DownloadHistoryRecord(
    val id: String,
    val title: String,
    val uploader: String = "",
    val thumbnailUrl: String = "",
    val qualityLabel: String,
    val fps: Int = 30,
    val container: String = "MP4",
    val formattedSize: String = "",
    val fileUri: String = "",
    val filePath: String = "",
    val mimeType: String = "video/mp4",
    val completedAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val downloadLocationName: String = "Download/OpenStream4K",
    val customTreeUri: String? = null,
    val maxConcurrentDownloads: Int = 3
)
