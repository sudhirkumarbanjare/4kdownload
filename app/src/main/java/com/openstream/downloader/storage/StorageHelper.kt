package com.openstream.downloader.storage

import android.content.Context
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.text.format.Formatter
import androidx.core.content.ContextCompat
import java.io.File

data class StorageDriveInfo(
    val id: String,
    val description: String,       // e.g. "Internal Storage" or "SD Card (75D7-DC5F)"
    val isRemovable: Boolean,      // true for physical SD card
    val freeBytes: Long,
    val totalBytes: Long,
    val formattedFree: String,
    val formattedTotal: String,
    val percentUsed: Float,
    val path: String
)

object StorageHelper {

    fun getStorageDrives(context: Context): List<StorageDriveInfo> {
        val drives = mutableListOf<StorageDriveInfo>()
        val seenPaths = mutableSetOf<String>()

        try {
            val storageManager = context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager
            if (storageManager != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val volumes = storageManager.storageVolumes
                for (volume in volumes) {
                    val isRemovable = volume.isRemovable
                    val desc = volume.getDescription(context) ?: if (isRemovable) "SD Card" else "Internal Storage"

                    val dir: File? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        volume.directory
                    } else {
                        // Reflection fallback for API 24-29
                        try {
                            val getPathMethod = volume.javaClass.getMethod("getPathFile")
                            getPathMethod.invoke(volume) as? File
                        } catch (e: Exception) {
                            null
                        }
                    }

                    if (dir != null && dir.exists() && seenPaths.add(dir.absolutePath)) {
                        try {
                            val stat = StatFs(dir.absolutePath)
                            val free = stat.availableBlocksLong * stat.blockSizeLong
                            val total = stat.blockCountLong * stat.blockSizeLong
                            val percentUsed = if (total > 0) ((total - free).toFloat() / total.toFloat()) else 0f

                            drives.add(
                                StorageDriveInfo(
                                    id = dir.name,
                                    description = desc,
                                    isRemovable = isRemovable,
                                    freeBytes = free,
                                    totalBytes = total,
                                    formattedFree = Formatter.formatFileSize(context, free),
                                    formattedTotal = Formatter.formatFileSize(context, total),
                                    percentUsed = percentUsed.coerceIn(0f, 1f),
                                    path = dir.absolutePath
                                )
                            )
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Fallback / supplement: check getExternalFilesDirs
        if (drives.isEmpty()) {
            val dirs = ContextCompat.getExternalFilesDirs(context, null)
            dirs.filterNotNull().forEachIndexed { index, file ->
                val isRemovable = index > 0 || Environment.isExternalStorageRemovable(file)
                val path = file.absolutePath
                if (seenPaths.add(path)) {
                    try {
                        val stat = StatFs(file.path)
                        val free = stat.availableBlocksLong * stat.blockSizeLong
                        val total = stat.blockCountLong * stat.blockSizeLong
                        val percentUsed = if (total > 0) ((total - free).toFloat() / total.toFloat()) else 0f
                        val desc = if (isRemovable) "SD Card" else "Internal Storage"

                        drives.add(
                            StorageDriveInfo(
                                id = "drive_$index",
                                description = desc,
                                isRemovable = isRemovable,
                                freeBytes = free,
                                totalBytes = total,
                                formattedFree = Formatter.formatFileSize(context, free),
                                formattedTotal = Formatter.formatFileSize(context, total),
                                percentUsed = percentUsed.coerceIn(0f, 1f),
                                path = path
                            )
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }

        // Primary internal fallback if still empty
        if (drives.none { !it.isRemovable }) {
            try {
                val stat = StatFs(Environment.getExternalStorageDirectory().path)
                val free = stat.availableBlocksLong * stat.blockSizeLong
                val total = stat.blockCountLong * stat.blockSizeLong
                val percentUsed = if (total > 0) ((total - free).toFloat() / total.toFloat()) else 0f
                drives.add(
                    0,
                    StorageDriveInfo(
                        id = "primary_internal",
                        description = "Internal Storage",
                        isRemovable = false,
                        freeBytes = free,
                        totalBytes = total,
                        formattedFree = Formatter.formatFileSize(context, free),
                        formattedTotal = Formatter.formatFileSize(context, total),
                        percentUsed = percentUsed.coerceIn(0f, 1f),
                        path = Environment.getExternalStorageDirectory().path
                    )
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return drives
    }

    fun hasEnoughSpace(context: Context, requiredBytes: Long): Boolean {
        if (requiredBytes <= 0) return true
        val cacheAvailable = context.cacheDir.usableSpace
        val margin = 50L * 1024L * 1024L // 50MB safety margin
        return cacheAvailable >= (requiredBytes + margin)
    }

    fun getStorageSummary(context: Context): Pair<StorageDriveInfo?, StorageDriveInfo?> {
        val drives = getStorageDrives(context)
        val internal = drives.firstOrNull { !it.isRemovable } ?: drives.firstOrNull()
        val sdCard = drives.firstOrNull { it.isRemovable }
        return Pair(internal, sdCard)
    }
}
