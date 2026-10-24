package com.openstream.downloader.storage

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

object MediaSaver {

    suspend fun saveMedia(
        context: Context,
        sourceFile: File,
        desiredFilename: String,
        mimeType: String,
        customTreeUriString: String? = null
    ): Result<Uri> = withContext(Dispatchers.IO) {
        try {
            // 1. Try custom SAF location if configured
            if (!customTreeUriString.isNullOrBlank()) {
                try {
                    val treeUri = Uri.parse(customTreeUriString)
                    val docId = DocumentsContract.getTreeDocumentId(treeUri)
                    val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                    val createdDocUri = DocumentsContract.createDocument(
                        context.contentResolver,
                        parentDocUri,
                        mimeType,
                        desiredFilename
                    )

                    if (createdDocUri != null) {
                        context.contentResolver.openOutputStream(createdDocUri)?.use { outStream ->
                            FileInputStream(sourceFile).use { inStream ->
                                inStream.copyTo(outStream)
                            }
                        }
                        sourceFile.delete()
                        return@withContext Result.success(createdDocUri)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Fallback to standard MediaStore below
                }
            }

            // 2. Default MediaStore.Downloads (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, desiredFilename)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/OpenStream4K")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collectionUri = MediaStore.Downloads.EXTERNAL_CONTENT_URI
                val itemUri = resolver.insert(collectionUri, contentValues)
                    ?: return@withContext Result.failure(Exception("Failed to create MediaStore entry"))

                resolver.openOutputStream(itemUri)?.use { outStream ->
                    FileInputStream(sourceFile).use { inStream ->
                        inStream.copyTo(outStream)
                    }
                }

                contentValues.clear()
                contentValues.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, contentValues, null, null)

                sourceFile.delete()
                Result.success(itemUri)
            } else {
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "OpenStream4K")
                targetDir.mkdirs()
                val targetFile = File(targetDir, desiredFilename)

                FileInputStream(sourceFile).use { inStream ->
                    FileOutputStream(targetFile).use { outStream ->
                        inStream.copyTo(outStream)
                    }
                }

                sourceFile.delete()
                MediaScannerConnection.scanFile(context, arrayOf(targetFile.absolutePath), arrayOf(mimeType), null)
                Result.success(Uri.fromFile(targetFile))
            }
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
