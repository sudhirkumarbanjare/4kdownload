package com.openstream.downloader.engine

import android.content.Context
import com.openstream.downloader.model.DownloadHistoryRecord
import com.openstream.downloader.model.DownloadProgress
import com.openstream.downloader.model.DownloadStatus
import com.openstream.downloader.model.DownloadTask
import com.openstream.downloader.model.FormatType
import com.openstream.downloader.muxer.MediaMuxerService
import com.openstream.downloader.service.DownloadService
import com.openstream.downloader.storage.HistoryRepository
import com.openstream.downloader.storage.MediaSaver
import com.openstream.downloader.storage.SettingsRepository
import com.openstream.downloader.storage.StorageHelper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class DownloadQueueManager(
    private val context: Context,
    private val historyRepository: HistoryRepository,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    private val _tasks = MutableStateFlow<List<DownloadTask>>(emptyList())
    val tasks: StateFlow<List<DownloadTask>> = _tasks.asStateFlow()

    private var concurrencySemaphore = Semaphore(settingsRepository.settings.value.maxConcurrentDownloads)

    init {
        scope.launch {
            settingsRepository.settings.collect { s ->
                concurrencySemaphore = Semaphore(s.maxConcurrentDownloads)
            }
        }
    }

    fun enqueue(task: DownloadTask) {
        val currentList = _tasks.value.toMutableList()
        currentList.removeAll { it.id == task.id }
        currentList.add(0, task)
        _tasks.value = currentList

        startTaskJob(task.id)
    }

    private fun startTaskJob(taskId: String) {
        DownloadService.start(context)
        val job = scope.launch {
            concurrencySemaphore.withPermit {
                executeDownload(taskId)
            }
        }
        activeJobs[taskId] = job
    }

    private suspend fun executeDownload(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        if (task.status == DownloadStatus.CANCELLED || task.status == DownloadStatus.PAUSED) return

        val format = task.selectedFormat

        // Pre-flight disk space check
        if (!StorageHelper.hasEnoughSpace(context, format.estimatedSizeBytes)) {
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = "Insufficient free disk space on device."
                )
            }
            return
        }

        updateTask(taskId) { it.copy(status = DownloadStatus.DOWNLOADING) }

        val cacheDir = context.cacheDir
        val sanitizedTitle = task.title
            .replace(Regex("[\\\\/:*?\"<>|\\r\\n\\t]"), "_")
            .trim()
            .ifBlank { "Video_${System.currentTimeMillis()}" }
            .take(100)

        val isMp3 = format.container.contains("mp3", ignoreCase = true)
        val isWebm = format.container.contains("webm", ignoreCase = true)
        val isOpus = format.container.contains("opus", ignoreCase = true)
        val videoExt = if (isWebm) "webm" else "mp4"
        val audioExt = when {
            isMp3 -> "mp3"
            isOpus || isWebm -> "opus"
            else -> "m4a"
        }
        val videoMime = if (isWebm) "video/webm" else "video/mp4"
        val audioMime = when {
            isMp3 -> "audio/mpeg"
            isOpus -> "audio/ogg"
            else -> "audio/mp4"
        }

        try {
            when (format.formatType) {
                FormatType.AUDIO_ONLY -> {
                    val audioFile = File(cacheDir, "audio_${taskId}.$audioExt")
                    val result = ParallelChunkedDownloader.downloadToFile(
                        url = format.audioUrl!!,
                        destinationFile = audioFile,
                        stepLabel = "Downloading Audio (${format.qualityLabel})",
                        onProgress = { p -> updateTaskProgress(taskId, p) }
                    ).getOrThrow()

                    val filename = "$sanitizedTitle.$audioExt"
                    val treeUri = settingsRepository.settings.value.customTreeUri
                    val uri = MediaSaver.saveMedia(context, result, filename, audioMime, treeUri).getOrThrow()

                    onTaskFinished(task, filename, uri.toString(), audioMime)
                }

                FormatType.MUXED -> {
                    val videoFile = File(cacheDir, "video_${taskId}.$videoExt")
                    val result = ParallelChunkedDownloader.downloadToFile(
                        url = format.videoUrl!!,
                        destinationFile = videoFile,
                        stepLabel = "Downloading Video (${format.label})",
                        onProgress = { p -> updateTaskProgress(taskId, p) }
                    ).getOrThrow()

                    val filename = "$sanitizedTitle.$videoExt"
                    val treeUri = settingsRepository.settings.value.customTreeUri
                    val uri = MediaSaver.saveMedia(context, result, filename, videoMime, treeUri).getOrThrow()

                    onTaskFinished(task, filename, uri.toString(), videoMime)
                }

                FormatType.ADAPTIVE -> {
                    val tempVideo = File(cacheDir, "vid_${taskId}.$videoExt")
                    val tempAudio = File(cacheDir, "aud_${taskId}.$audioExt")
                    val videoDoneMarker = File(cacheDir, "vid_${taskId}.done")

                    // High-speed concurrent dual-stream download (Video + Audio in parallel)
                    coroutineScope {
                        var videoPct = 0
                        var audioPct = 0
                        var videoSpeed = 0L
                        var audioSpeed = 0L

                        fun reportDualProgress() {
                            val combinedPct = ((videoPct * 0.85) + (audioPct * 0.15)).toInt().coerceIn(0, 99)
                            val combinedSpeed = videoSpeed + audioSpeed
                            updateTaskProgress(
                                taskId,
                                DownloadProgress(
                                    percentage = combinedPct,
                                    speedBytesPerSec = combinedSpeed,
                                    currentStep = "Downloading 4K Video & Audio (Turbo)"
                                )
                            )
                        }

                        val videoJob = launch {
                            if (!videoDoneMarker.exists()) {
                                ParallelChunkedDownloader.downloadToFile(
                                    url = format.videoUrl!!,
                                    destinationFile = tempVideo,
                                    stepLabel = "Downloading Video (${format.label})",
                                    onProgress = { p ->
                                        videoPct = p.percentage
                                        videoSpeed = p.speedBytesPerSec
                                        reportDualProgress()
                                    }
                                ).getOrThrow()
                                videoDoneMarker.createNewFile()
                            } else {
                                videoPct = 100
                            }
                        }

                        val audioJob = launch {
                            ParallelChunkedDownloader.downloadToFile(
                                url = format.audioUrl!!,
                                destinationFile = tempAudio,
                                stepLabel = "Downloading Audio",
                                onProgress = { p ->
                                    audioPct = p.percentage
                                    audioSpeed = p.speedBytesPerSec
                                    reportDualProgress()
                                }
                            ).getOrThrow()
                        }

                        videoJob.join()
                        audioJob.join()
                    }

                    // 3. Lossless Remuxing
                    updateTask(taskId) {
                        it.copy(
                            status = DownloadStatus.MERGING,
                            progress = DownloadProgress(
                                percentage = 99,
                                currentStep = "Merging ${format.qualityLabel} Audio & Video..."
                            )
                        )
                    }

                    val mergedFile = File(cacheDir, "merged_${taskId}.$videoExt")
                    MediaMuxerService.mergeAudioAndVideo(tempVideo, tempAudio, mergedFile).getOrThrow()

                    // 4. Save to destination
                    val filename = "$sanitizedTitle.$videoExt"
                    val treeUri = settingsRepository.settings.value.customTreeUri
                    val uri = MediaSaver.saveMedia(context, mergedFile, filename, videoMime, treeUri).getOrThrow()

                    // Clean temp pieces after successful merge
                    tempVideo.delete()
                    tempAudio.delete()
                    videoDoneMarker.delete()

                    onTaskFinished(task, filename, uri.toString(), videoMime)
                }
            }
        } catch (ce: CancellationException) {
            val current = _tasks.value.find { it.id == taskId }
            if (current?.status == DownloadStatus.PAUSED) {
                // Kept as paused; partial file remains on disk for resume!
            } else {
                updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }
                cleanupTempFiles(taskId)
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            updateTask(taskId) {
                it.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.message ?: "Download failed"
                )
            }
        } finally {
            activeJobs.remove(taskId)
        }
    }

    private suspend fun onTaskFinished(task: DownloadTask, filename: String, uriString: String, mimeType: String) {
        updateTask(task.id) {
            it.copy(
                status = DownloadStatus.COMPLETED,
                savedUri = uriString,
                progress = DownloadProgress(percentage = 100, currentStep = "Completed")
            )
        }

        val record = DownloadHistoryRecord(
            id = task.id,
            title = task.title,
            uploader = task.uploader,
            thumbnailUrl = task.thumbnailUrl,
            qualityLabel = task.selectedFormat.qualityLabel,
            fps = task.selectedFormat.fps,
            container = task.selectedFormat.container,
            formattedSize = task.selectedFormat.formattedSize,
            fileUri = uriString,
            filePath = filename,
            mimeType = mimeType,
            completedAt = System.currentTimeMillis()
        )
        historyRepository.addRecord(record)
    }

    fun pause(taskId: String) {
        updateTask(taskId) {
            it.copy(
                status = DownloadStatus.PAUSED,
                progress = it.progress.copy(speedBytesPerSec = 0L, currentStep = "Paused")
            )
        }
        val job = activeJobs[taskId]
        job?.cancel(CancellationException("Download paused by user"))
        activeJobs.remove(taskId)
    }

    fun resume(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        updateTask(taskId) {
            it.copy(
                status = DownloadStatus.QUEUED,
                errorMessage = null,
                progress = it.progress.copy(currentStep = "Resuming...")
            )
        }
        startTaskJob(taskId)
    }

    fun cancel(taskId: String) {
        val job = activeJobs[taskId]
        job?.cancel()
        activeJobs.remove(taskId)
        updateTask(taskId) { it.copy(status = DownloadStatus.CANCELLED) }
        cleanupTempFiles(taskId)
    }

    fun retry(taskId: String) {
        val task = _tasks.value.find { it.id == taskId } ?: return
        updateTask(taskId) {
            it.copy(
                status = DownloadStatus.QUEUED,
                errorMessage = null,
                progress = DownloadProgress()
            )
        }
        startTaskJob(taskId)
    }

    fun remove(taskId: String) {
        cancel(taskId)
        _tasks.value = _tasks.value.filter { it.id != taskId }
        cleanupTempFiles(taskId)
    }

    fun clearCompleted() {
        _tasks.value = _tasks.value.filter {
            it.status != DownloadStatus.COMPLETED &&
            it.status != DownloadStatus.FAILED &&
            it.status != DownloadStatus.CANCELLED
        }
    }

    private fun cleanupTempFiles(taskId: String) {
        try {
            val cacheDir = context.cacheDir
            cacheDir.listFiles()?.forEach { file ->
                if (file.name.contains(taskId)) {
                    file.delete()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateTaskProgress(taskId: String, progress: DownloadProgress) {
        updateTask(taskId) {
            it.copy(
                progress = progress
            )
        }
    }

    private fun updateTask(taskId: String, transform: (DownloadTask) -> DownloadTask) {
        _tasks.value = _tasks.value.map {
            if (it.id == taskId) transform(it) else it
        }
    }
}
