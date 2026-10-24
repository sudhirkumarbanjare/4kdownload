package com.openstream.downloader.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openstream.downloader.engine.DownloadQueueManager
import com.openstream.downloader.extractor.StreamExtractor
import com.openstream.downloader.model.AppSettings
import com.openstream.downloader.model.DownloadHistoryRecord
import com.openstream.downloader.model.DownloadTask
import com.openstream.downloader.model.FormatType
import com.openstream.downloader.model.StreamFormat
import com.openstream.downloader.model.VideoMetadata
import com.openstream.downloader.storage.HistoryRepository
import com.openstream.downloader.storage.SettingsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

import com.openstream.downloader.OpenStreamApp

class DownloaderViewModel(application: Application) : AndroidViewModel(application) {

    val historyRepository = OpenStreamApp.instance.historyRepository
    val settingsRepository = OpenStreamApp.instance.settingsRepository
    val queueManager = OpenStreamApp.instance.queueManager

    val activeTasks: StateFlow<List<DownloadTask>> = queueManager.tasks
    val historyList: StateFlow<List<DownloadHistoryRecord>> = historyRepository.history
    val settings: StateFlow<AppSettings> = settingsRepository.settings

    private val _isAnalyzing = MutableStateFlow(false)
    val isAnalyzing: StateFlow<Boolean> = _isAnalyzing.asStateFlow()

    private val _analysisResult = MutableStateFlow<VideoMetadata?>(null)
    val analysisResult: StateFlow<VideoMetadata?> = _analysisResult.asStateFlow()

    private val _selectedFormat = MutableStateFlow<StreamFormat?>(null)
    val selectedFormat: StateFlow<StreamFormat?> = _selectedFormat.asStateFlow()

    private val _analysisError = MutableStateFlow<String?>(null)
    val analysisError: StateFlow<String?> = _analysisError.asStateFlow()

    fun clearAnalysisError() {
        _analysisError.value = null
    }

    fun analyzeUrl(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return

        _isAnalyzing.value = true
        _analysisError.value = null
        _analysisResult.value = null

        viewModelScope.launch {
            val result = StreamExtractor.extract(trimmed)
            _isAnalyzing.value = false
            result.onSuccess { metadata ->
                _analysisResult.value = metadata
                // Select 1080p or best adaptive or first available
                val defaultFormat = metadata.availableFormats.firstOrNull { it.qualityLabel.contains("1080", ignoreCase = true) }
                    ?: metadata.availableFormats.firstOrNull { it.formatType == FormatType.ADAPTIVE }
                    ?: metadata.availableFormats.firstOrNull()
                _selectedFormat.value = defaultFormat
            }.onFailure { err ->
                val msg = err.message.orEmpty()
                _analysisError.value = when {
                    msg.contains("not a bot", ignoreCase = true) || err.javaClass.simpleName.contains("Bot", ignoreCase = true) ->
                        "YouTube IP Rate Limit: YouTube temporarily blocked anonymous access on your current IP (\"Sign in to confirm you're not a bot\").\n\n💡 Fix: Toggle Airplane Mode ON for 5 seconds and OFF, or switch between Wi-Fi and Mobile Data to get a fresh IP."
                    msg.contains("AgeRestricted", ignoreCase = true) ->
                        "This video is age-restricted and cannot be accessed anonymously."
                    msg.contains("Private", ignoreCase = true) ->
                        "This video is private or unavailable."
                    msg.contains("Geo", ignoreCase = true) ->
                        "This video is not available in your region/country."
                    else -> err.message ?: "Failed to extract video streams. Please check the URL."
                }
            }
        }
    }

    fun selectFormat(format: StreamFormat) {
        _selectedFormat.value = format
    }

    fun addToQueue() {
        val metadata = _analysisResult.value ?: return
        val format = _selectedFormat.value ?: return

        val task = DownloadTask(
            url = metadata.originalUrl,
            title = metadata.title,
            uploader = metadata.uploader,
            thumbnailUrl = metadata.thumbnailUrl,
            selectedFormat = format
        )
        queueManager.enqueue(task)

        // Close analysis sheet after queueing
        dismissAnalysis()
    }

    fun queueBatchUrls(urls: List<String>) {
        val validUrls = urls.map { it.trim() }.filter { it.isNotBlank() }
        if (validUrls.isEmpty()) return

        viewModelScope.launch {
            validUrls.forEach { url ->
                val result = StreamExtractor.extract(url)
                result.onSuccess { metadata ->
                    val defaultFormat = metadata.availableFormats.firstOrNull { it.qualityLabel.contains("1080", ignoreCase = true) }
                        ?: metadata.availableFormats.firstOrNull { it.formatType == FormatType.ADAPTIVE }
                        ?: metadata.availableFormats.firstOrNull()

                    if (defaultFormat != null) {
                        queueManager.enqueue(
                            DownloadTask(
                                url = metadata.originalUrl,
                                title = metadata.title,
                                uploader = metadata.uploader,
                                thumbnailUrl = metadata.thumbnailUrl,
                                selectedFormat = defaultFormat
                            )
                        )
                    }
                }
            }
        }
    }

    fun dismissAnalysis() {
        _analysisResult.value = null
        _selectedFormat.value = null
        _analysisError.value = null
        _isAnalyzing.value = false
    }

    fun pauseTask(taskId: String) {
        queueManager.pause(taskId)
    }

    fun resumeTask(taskId: String) {
        queueManager.resume(taskId)
    }

    fun cancelTask(taskId: String) {
        queueManager.cancel(taskId)
    }

    fun retryTask(taskId: String) {
        queueManager.retry(taskId)
    }

    fun removeTask(taskId: String) {
        queueManager.remove(taskId)
    }

    fun clearCompletedTasks() {
        queueManager.clearCompleted()
    }

    fun deleteHistoryRecord(id: String, deleteFile: Boolean) {
        viewModelScope.launch {
            historyRepository.deleteRecord(id, deleteFile)
        }
    }

    fun clearAllHistory(deleteFiles: Boolean = false) {
        viewModelScope.launch {
            historyRepository.clearHistory(deleteFiles)
        }
    }

    fun updateDownloadLocation(name: String, treeUri: Uri?) {
        settingsRepository.setDownloadLocation(name, treeUri)
    }

    fun resetDefaultLocation() {
        settingsRepository.resetToDefaultLocation()
    }

    fun setMaxConcurrent(limit: Int) {
        settingsRepository.setMaxConcurrentDownloads(limit)
    }
}
