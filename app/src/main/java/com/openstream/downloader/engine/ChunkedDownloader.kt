package com.openstream.downloader.engine

import com.openstream.downloader.model.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit

object ChunkedDownloader {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    suspend fun downloadToFile(
        url: String,
        destinationFile: File,
        stepLabel: String,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            destinationFile.parentFile?.mkdirs()

            val existingBytes = if (destinationFile.exists()) destinationFile.length() else 0L

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")

            if (existingBytes > 0L) {
                requestBuilder.header("Range", "bytes=$existingBytes-")
            }

            val request = requestBuilder.build()
            val response = client.newCall(request).execute()

            // 416 means Range Not Satisfiable (file is already fully downloaded)
            if (response.code == 416 && existingBytes > 0L) {
                response.close()
                onProgress(
                    DownloadProgress(
                        downloadedBytes = existingBytes,
                        totalBytes = existingBytes,
                        speedBytesPerSec = 0L,
                        percentage = 100,
                        currentStep = "$stepLabel (Complete)"
                    )
                )
                return@withContext Result.success(destinationFile)
            }

            if (!response.isSuccessful) {
                val code = response.code
                val msg = response.message
                response.close()
                return@withContext Result.failure(Exception("HTTP $code: $msg"))
            }

            val body = response.body ?: return@withContext Result.failure(Exception("Empty HTTP response body"))
            val isPartialContent = response.code == 206

            val totalBytes: Long
            var totalRead: Long

            val outputStream: FileOutputStream
            if (isPartialContent && existingBytes > 0L) {
                // Resume appending to file
                outputStream = FileOutputStream(destinationFile, true)
                totalBytes = existingBytes + body.contentLength()
                totalRead = existingBytes
            } else {
                // Fresh download or server doesn't support Range
                outputStream = FileOutputStream(destinationFile, false)
                totalBytes = body.contentLength()
                totalRead = 0L
            }

            val inputStream: InputStream = body.byteStream()
            val buffer = ByteArray(64 * 1024) // 64KB chunk buffer
            var bytesRead: Int

            var lastUpdateTime = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            try {
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    if (!isActive) {
                        // Flushed and closed on pause/cancel - partial bytes preserved on disk!
                        outputStream.flush()
                        outputStream.close()
                        inputStream.close()
                        response.close()
                        throw CancellationException("Download paused")
                    }

                    outputStream.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    bytesSinceLastUpdate += bytesRead

                    val now = System.currentTimeMillis()
                    val elapsed = now - lastUpdateTime
                    if (elapsed >= 500) { // Update speed & progress every 500ms
                        currentSpeed = (bytesSinceLastUpdate * 1000) / elapsed
                        val pct = if (totalBytes > 0) ((totalRead * 100) / totalBytes).toInt().coerceIn(0, 100) else 0

                        onProgress(
                            DownloadProgress(
                                downloadedBytes = totalRead,
                                totalBytes = totalBytes,
                                speedBytesPerSec = currentSpeed,
                                percentage = pct,
                                currentStep = stepLabel
                            )
                        )
                        lastUpdateTime = now
                        bytesSinceLastUpdate = 0L
                    }
                }

                outputStream.flush()
            } finally {
                outputStream.close()
                inputStream.close()
                response.close()
            }

            onProgress(
                DownloadProgress(
                    downloadedBytes = totalRead,
                    totalBytes = totalRead,
                    speedBytesPerSec = 0L,
                    percentage = 100,
                    currentStep = "$stepLabel Finished"
                )
            )

            Result.success(destinationFile)
        } catch (ce: CancellationException) {
            throw ce
        } catch (ste: SocketTimeoutException) {
            Result.failure(Exception("Connection timed out. Tap retry to resume."))
        } catch (uhe: UnknownHostException) {
            Result.failure(Exception("No internet connection. Please check your network."))
        } catch (t: Throwable) {
            Result.failure(t)
        }
    }
}
