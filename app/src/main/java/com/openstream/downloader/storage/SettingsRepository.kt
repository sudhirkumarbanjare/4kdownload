package com.openstream.downloader.storage

import android.content.Context
import android.net.Uri
import com.openstream.downloader.model.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs = context.getSharedPreferences("openstream_prefs", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(loadSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private fun loadSettings(): AppSettings {
        val locationName = prefs.getString(KEY_LOCATION_NAME, "Download/OpenStream4K") ?: "Download/OpenStream4K"
        val treeUri = prefs.getString(KEY_TREE_URI, null)
        val maxConcurrent = prefs.getInt(KEY_MAX_CONCURRENT, 3)
        return AppSettings(
            downloadLocationName = locationName,
            customTreeUri = treeUri,
            maxConcurrentDownloads = maxConcurrent
        )
    }

    fun setDownloadLocation(name: String, treeUri: Uri?) {
        prefs.edit().apply {
            putString(KEY_LOCATION_NAME, name)
            putString(KEY_TREE_URI, treeUri?.toString())
            apply()
        }
        _settings.value = _settings.value.copy(
            downloadLocationName = name,
            customTreeUri = treeUri?.toString()
        )
    }

    fun setMaxConcurrentDownloads(limit: Int) {
        val clamped = limit.coerceIn(1, 5)
        prefs.edit().putInt(KEY_MAX_CONCURRENT, clamped).apply()
        _settings.value = _settings.value.copy(maxConcurrentDownloads = clamped)
    }

    fun resetToDefaultLocation() {
        prefs.edit().apply {
            putString(KEY_LOCATION_NAME, "Download/OpenStream4K")
            remove(KEY_TREE_URI)
            apply()
        }
        _settings.value = _settings.value.copy(
            downloadLocationName = "Download/OpenStream4K",
            customTreeUri = null
        )
    }

    companion object {
        private const val KEY_LOCATION_NAME = "key_download_location_name"
        private const val KEY_TREE_URI = "key_custom_tree_uri"
        private const val KEY_MAX_CONCURRENT = "key_max_concurrent"
    }
}
