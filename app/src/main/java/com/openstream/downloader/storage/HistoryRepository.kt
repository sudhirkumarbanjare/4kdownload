package com.openstream.downloader.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.openstream.downloader.model.DownloadHistoryRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class HistoryRepository(private val context: Context) {

    private val historyFile = File(context.filesDir, "download_history.json")
    private val mutex = Mutex()

    private val _history = MutableStateFlow<List<DownloadHistoryRecord>>(emptyList())
    val history: StateFlow<List<DownloadHistoryRecord>> = _history.asStateFlow()

    init {
        loadHistory()
    }

    fun loadHistory() {
        try {
            if (!historyFile.exists()) {
                _history.value = emptyList()
                return
            }
            val content = historyFile.readText()
            if (content.isBlank()) {
                _history.value = emptyList()
                return
            }
            val jsonArray = JSONArray(content)
            val list = mutableListOf<DownloadHistoryRecord>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(
                    DownloadHistoryRecord(
                        id = obj.optString("id", ""),
                        title = obj.optString("title", ""),
                        uploader = obj.optString("uploader", ""),
                        thumbnailUrl = obj.optString("thumbnailUrl", ""),
                        qualityLabel = obj.optString("qualityLabel", ""),
                        fps = obj.optInt("fps", 30),
                        container = obj.optString("container", "MP4"),
                        formattedSize = obj.optString("formattedSize", ""),
                        fileUri = obj.optString("fileUri", ""),
                        filePath = obj.optString("filePath", ""),
                        mimeType = obj.optString("mimeType", "video/mp4"),
                        completedAt = obj.optLong("completedAt", System.currentTimeMillis())
                    )
                )
            }
            _history.value = list.sortedByDescending { it.completedAt }
        } catch (e: Exception) {
            e.printStackTrace()
            _history.value = emptyList()
        }
    }

    suspend fun addRecord(record: DownloadHistoryRecord) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val updated = (_history.value.filter { it.id != record.id } + record)
                .sortedByDescending { it.completedAt }
            saveList(updated)
            _history.value = updated
        }
    }

    suspend fun deleteRecord(id: String, deletePhysicalFile: Boolean) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val toDelete = _history.value.find { it.id == id }
            if (deletePhysicalFile && toDelete != null) {
                deletePhysicalMediaFile(toDelete.fileUri, toDelete.filePath)
            }
            val updated = _history.value.filter { it.id != id }
            saveList(updated)
            _history.value = updated
        }
    }

    suspend fun clearHistory(deletePhysicalFiles: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (deletePhysicalFiles) {
                _history.value.forEach { record ->
                    deletePhysicalMediaFile(record.fileUri, record.filePath)
                }
            }
            saveList(emptyList())
            _history.value = emptyList()
        }
    }

    private fun deletePhysicalMediaFile(fileUriStr: String, fileNameOrPath: String) {
        Log.d("HistoryRepository", "deletePhysicalMediaFile: uri=$fileUriStr, path=$fileNameOrPath")

        // 1. Storage Access Framework (SAF) Document Deletion & ContentResolver
        if (fileUriStr.isNotBlank()) {
            val uri = try { Uri.parse(fileUriStr) } catch (e: Exception) { null }
            if (uri != null) {
                var safDeleted = false
                try {
                    if (DocumentsContract.isDocumentUri(context, uri)) {
                        safDeleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                        Log.d("HistoryRepository", "DocumentsContract.deleteDocument result: $safDeleted for $uri")
                    }
                } catch (e: Exception) {
                    Log.w("HistoryRepository", "DocumentsContract.deleteDocument failed: ${e.message}")
                }

                if (!safDeleted && uri.authority?.contains("documents") == true) {
                    try {
                        safDeleted = DocumentsContract.deleteDocument(context.contentResolver, uri)
                        Log.d("HistoryRepository", "Fallback DocumentsContract.deleteDocument result: $safDeleted for $uri")
                    } catch (e: Exception) {
                        Log.w("HistoryRepository", "Fallback DocumentsContract.deleteDocument failed: ${e.message}")
                    }
                }

                try {
                    val rows = context.contentResolver.delete(uri, null, null)
                    Log.d("HistoryRepository", "ContentResolver.delete rows: $rows for $uri")
                } catch (e: Exception) {
                    Log.w("HistoryRepository", "ContentResolver.delete failed: ${e.message}")
                }
            }
        }

        // 2. Direct File Deletion (Absolute paths, standard folders, SD Card drives)
        if (fileNameOrPath.isNotBlank()) {
            val baseName = File(fileNameOrPath).name
            val candidateFiles = mutableListOf<File>()

            val direct = File(fileNameOrPath)
            if (direct.isAbsolute) {
                candidateFiles.add(direct)
            }

            // Standard public directories on primary external storage
            val extDownloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val extMovies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
            val extMusic = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)

            candidateFiles.add(File(extDownloads, "OpenStream4K/$baseName"))
            candidateFiles.add(File(extDownloads, baseName))
            candidateFiles.add(File(extMovies, "OpenStream4K/$baseName"))
            candidateFiles.add(File(extMusic, "OpenStream4K/$baseName"))

            // Also check all detected storage volumes/drives (including SD cards)
            try {
                val drives = StorageHelper.getStorageDrives(context)
                for (drive in drives) {
                    val root = File(drive.path)
                    candidateFiles.add(File(root, "OpenStream4K/$baseName"))
                    candidateFiles.add(File(root, "Download/OpenStream4K/$baseName"))
                    candidateFiles.add(File(root, "Download/$baseName"))
                    candidateFiles.add(File(root, "Movies/OpenStream4K/$baseName"))
                    candidateFiles.add(File(root, "Music/OpenStream4K/$baseName"))

                    // Extract relative path if encoded inside the SAF URI
                    if (fileUriStr.contains(drive.id)) {
                        val decodedUri = Uri.decode(fileUriStr)
                        val separator = "${drive.id}:"
                        if (decodedUri.contains(separator)) {
                            val subpath = decodedUri.substringAfter(separator).trimStart('/')
                            candidateFiles.add(File(root, subpath))
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("HistoryRepository", "Storage drive scan error: ${e.message}")
            }

            for (file in candidateFiles) {
                try {
                    if (file.exists()) {
                        val ok = file.delete()
                        Log.d("HistoryRepository", "Direct File.delete(): ${file.absolutePath} -> $ok")
                    }
                } catch (e: Exception) {
                    Log.w("HistoryRepository", "Direct File.delete() error on ${file.absolutePath}: ${e.message}")
                }
            }

            // 3. MediaStore Query Cleanup by DISPLAY_NAME
            val collections = mutableListOf<Uri>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                collections.add(MediaStore.Downloads.EXTERNAL_CONTENT_URI)
            }
            collections.add(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            collections.add(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI)

            for (collection in collections) {
                try {
                    val count = context.contentResolver.delete(
                        collection,
                        "${MediaStore.MediaColumns.DISPLAY_NAME} = ?",
                        arrayOf(baseName)
                    )
                    if (count > 0) {
                        Log.d("HistoryRepository", "MediaStore query delete removed $count row(s) from $collection for $baseName")
                    }
                } catch (e: Exception) {
                    // Ignore query delete errors
                }
            }
        }
    }

    private fun saveList(list: List<DownloadHistoryRecord>) {
        val jsonArray = JSONArray()
        list.forEach { r ->
            val obj = JSONObject().apply {
                put("id", r.id)
                put("title", r.title)
                put("uploader", r.uploader)
                put("thumbnailUrl", r.thumbnailUrl)
                put("qualityLabel", r.qualityLabel)
                put("fps", r.fps)
                put("container", r.container)
                put("formattedSize", r.formattedSize)
                put("fileUri", r.fileUri)
                put("filePath", r.filePath)
                put("mimeType", r.mimeType)
                put("completedAt", r.completedAt)
            }
            jsonArray.put(obj)
        }
        historyFile.writeText(jsonArray.toString(2))
    }
}
