package com.openstream.downloader.extractor

import android.content.Context
import android.net.ConnectivityManager
import android.util.Log
import com.openstream.downloader.OpenStreamApp
import com.openstream.downloader.model.FormatType
import com.openstream.downloader.model.StreamFormat
import com.openstream.downloader.model.VideoMetadata
import com.openstream.downloader.network.NetworkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.UUID

object StreamExtractor {

    suspend fun extract(url: String): Result<VideoMetadata> = withContext(Dispatchers.IO) {
        try {
            val service = ServiceList.YouTube
            var extractor = service.getStreamExtractor(url)
            try {
                extractor.fetchPage()
            } catch (e: Exception) {
                val isBot = e.message?.contains("not a bot", ignoreCase = true) == true ||
                        e.javaClass.simpleName.contains("Bot", ignoreCase = true)
                if (isBot) {
                    val cellular = NetworkManager.getCellularNetwork()
                    if (cellular != null) {
                        Log.i("StreamExtractor", "YouTube bot challenge caught. Auto-failing over to Cellular network...")
                        val cm = OpenStreamApp.instance.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                        cm?.bindProcessToNetwork(cellular)
                        try {
                            extractor = service.getStreamExtractor(url)
                            extractor.fetchPage()
                            Log.i("StreamExtractor", "Successfully extracted via Cellular failover!")
                        } finally {
                            cm?.bindProcessToNetwork(null)
                        }
                    } else {
                        throw e
                    }
                } else {
                    throw e
                }
            }

            val title = extractor.name ?: "Video_${System.currentTimeMillis()}"
            val uploader = extractor.uploaderName ?: "Unknown"
            val duration = try { extractor.length } catch (_: Throwable) { 0L }
            val thumbnails = extractor.thumbnails
            val thumbnailUrl = thumbnails?.maxByOrNull { it.width * it.height }?.url ?: ""

            val videoOnlyStreams: List<VideoStream> = extractor.videoOnlyStreams ?: emptyList()
            val audioStreams: List<AudioStream> = extractor.audioStreams ?: emptyList()
            val muxedStreams: List<VideoStream> = extractor.videoStreams ?: emptyList()

            // Audio streams partitioned by container
            val bestM4aAudio = audioStreams.filter { it.format?.name?.contains("M4A", ignoreCase = true) == true }.maxByOrNull { it.averageBitrate }
            val bestWebmAudio = audioStreams.filter { it.format?.name?.contains("OPUS", ignoreCase = true) == true || it.format?.name?.contains("WEBM", ignoreCase = true) == true }.maxByOrNull { it.averageBitrate }
            val bestAudio = audioStreams.maxByOrNull { it.averageBitrate }
            val formats = mutableListOf<StreamFormat>()

            // 1. Adaptive High-Res Formats (8K, 4K, 1440p, 1080p, 720p, etc.)
            videoOnlyStreams.sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.fps }
                    .thenBy { it.format?.name ?: "" }
            ).forEach { video ->
                val fpsVal = if (video.fps > 0) video.fps else 30
                val fpsSuffix = if (fpsVal >= 50) " ${fpsVal}fps" else ""
                val qualityLabel = when {
                    video.height >= 4320 -> "8K"
                    video.height >= 2160 -> "4K"
                    video.height >= 1440 -> "1440p 2K"
                    video.height >= 1080 -> "1080p FHD"
                    video.height >= 720 -> "720p HD"
                    video.height >= 480 -> "480p"
                    video.height >= 360 -> "360p"
                    else -> "${video.height}p"
                }

                val isWebm = video.format?.name?.contains("WEBM", ignoreCase = true) == true ||
                        video.format?.name?.contains("VP9", ignoreCase = true) == true
                val container = if (isWebm) "WebM" else "MP4"
                val pairedAudio = if (isWebm) (bestWebmAudio ?: bestAudio) else (bestM4aAudio ?: bestAudio)

                // Estimated file size calculation
                val videoBitrate = if (video.bitrate > 0) {
                    video.bitrate.toLong().let { if (it < 100_000) it * 1000L else it }
                } else {
                    estimateVideoBitrate(video.height, fpsVal)
                }
                val audioBitrate = ((bestAudio?.averageBitrate ?: 128).toLong()) * 1000L
                val estBytes = if (duration > 0) ((videoBitrate + audioBitrate) * duration) / 8L else -1L

                formats.add(
                    StreamFormat(
                        id = UUID.randomUUID().toString(),
                        label = "$qualityLabel$fpsSuffix ($container)",
                        qualityLabel = qualityLabel,
                        resolution = "${video.width}x${video.height}",
                        fps = fpsVal,
                        container = container,
                        formatType = FormatType.ADAPTIVE,
                        videoUrl = video.content,
                        audioUrl = pairedAudio?.content,
                        videoCodec = video.format?.name,
                        audioCodec = pairedAudio?.format?.name,
                        estimatedSizeBytes = estBytes,
                        formattedSize = formatBytes(estBytes)
                    )
                )
            }

            // 2. Muxed Formats (Pre-merged Video + Audio streams)
            muxedStreams.sortedWith(
                compareByDescending<VideoStream> { it.height }
                    .thenByDescending { it.fps }
            ).forEach { muxed ->
                val fpsVal = if (muxed.fps > 0) muxed.fps else 30
                val qualityLabel = when {
                    muxed.height >= 1080 -> "1080p"
                    muxed.height >= 720 -> "720p HD"
                    muxed.height >= 480 -> "480p"
                    muxed.height >= 360 -> "360p"
                    else -> "${muxed.height}p"
                }
                val container = if (muxed.format?.name?.contains("WEBM", ignoreCase = true) == true) "WebM" else "MP4"
                val bitrate = if (muxed.bitrate > 0) {
                    muxed.bitrate.toLong().let { if (it < 100_000) it * 1000L else it }
                } else {
                    estimateVideoBitrate(muxed.height, fpsVal)
                }
                val estBytes = if (duration > 0) (bitrate * duration) / 8L else -1L

                formats.add(
                    StreamFormat(
                        id = UUID.randomUUID().toString(),
                        label = "$qualityLabel Standard ($container)",
                        qualityLabel = qualityLabel,
                        resolution = "${muxed.width}x${muxed.height}",
                        fps = fpsVal,
                        container = container,
                        formatType = FormatType.MUXED,
                        videoUrl = muxed.content,
                        audioUrl = null,
                        videoCodec = muxed.format?.name,
                        estimatedSizeBytes = estBytes,
                        formattedSize = formatBytes(estBytes)
                    )
                )
            }

            // 3. Audio-only formats
            // Studio Quality 320 kbps MP3 profile (sourced from highest bitrate master stream)
            if (bestAudio != null) {
                val est320Bytes = if (duration > 0) (320_000L * duration) / 8L else -1L
                formats.add(
                    StreamFormat(
                        id = UUID.randomUUID().toString(),
                        label = "MP3 320 kbps (High Quality Studio)",
                        qualityLabel = "320 kbps HQ",
                        resolution = "Audio Only",
                        fps = 0,
                        container = "MP3",
                        formatType = FormatType.AUDIO_ONLY,
                        videoUrl = null,
                        audioUrl = bestAudio.content,
                        audioCodec = "MP3 / ${bestAudio.format?.name ?: "HQ"}",
                        estimatedSizeBytes = est320Bytes,
                        formattedSize = formatBytes(est320Bytes)
                    )
                )
            }

            audioStreams.sortedByDescending { it.averageBitrate }.forEach { audio ->
                val bitrateKbps = if (audio.averageBitrate > 0) audio.averageBitrate else 128
                val container = when {
                    audio.format?.name?.contains("M4A", ignoreCase = true) == true -> "M4A"
                    audio.format?.name?.contains("OPUS", ignoreCase = true) == true -> "Opus"
                    else -> audio.format?.name ?: "Audio"
                }
                val estBytes = if (duration > 0) (bitrateKbps.toLong() * 1000L * duration) / 8L else -1L

                formats.add(
                    StreamFormat(
                        id = UUID.randomUUID().toString(),
                        label = "$container $bitrateKbps kbps",
                        qualityLabel = "$bitrateKbps kbps",
                        resolution = "Audio Only",
                        fps = 0,
                        container = container,
                        formatType = FormatType.AUDIO_ONLY,
                        videoUrl = null,
                        audioUrl = audio.content,
                        audioCodec = audio.format?.name,
                        estimatedSizeBytes = estBytes,
                        formattedSize = formatBytes(estBytes)
                    )
                )
            }

            val metadata = VideoMetadata(
                originalUrl = url,
                title = title,
                uploader = uploader,
                durationSeconds = duration,
                thumbnailUrl = thumbnailUrl,
                availableFormats = formats
            )

            Result.success(metadata)
        } catch (t: Throwable) {
            android.util.Log.e("StreamExtractor", "Extraction failed", t)
            Result.failure(t)
        }
    }

    private fun estimateVideoBitrate(height: Int, fps: Int): Long {
        val multiplier = if (fps >= 50) 1.5 else 1.0
        val base = when {
            height >= 4320 -> 45_000_000L
            height >= 2160 -> 20_000_000L
            height >= 1440 -> 12_000_000L
            height >= 1080 -> 5_000_000L
            height >= 720 -> 2_500_000L
            height >= 480 -> 1_200_000L
            height >= 360 -> 700_000L
            else -> 300_000L
        }
        return (base * multiplier).toLong()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0L) return ""
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(java.util.Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(java.util.Locale.US, "%.1f MB", mb)
            else -> String.format(java.util.Locale.US, "%.0f KB", kb)
        }
    }
}
