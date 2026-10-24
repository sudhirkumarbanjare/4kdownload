package com.openstream.downloader.engine

import com.openstream.downloader.model.DownloadProgress
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Ultra-high-speed multi-segment parallel downloader.
 * Features:
 * - OkHttp tuned Dispatcher: maxRequests=64, maxRequestsPerHost=32 (eliminates host bottlenecks)
 * - Dynamic 8-way parallel chunking for large files (>60MB) and 4-way for smaller streams
 * - Java NIO FileChannel positional writes: zero-lock concurrent I/O with direct ByteBuffer
 * - 256 KB memory buffers reducing kernel system call overhead by 4x
 */
object ParallelChunkedDownloader {

    private val client = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 32
        })
        .connectionPool(ConnectionPool(32, 5, TimeUnit.MINUTES))
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private const val BUFFER_SIZE = 256 * 1024 // 256 KB high-throughput buffer

    private fun calculateChunkCount(totalLength: Long): Int {
        return when {
            totalLength >= 60 * 1024 * 1024L -> 8 // 8 parallel connections for large 1080p/4K streams
            totalLength >= 15 * 1024 * 1024L -> 6 // 6 parallel connections for medium streams
            else -> 4                            // 4 parallel connections for audio/small files
        }
    }

    suspend fun downloadToFile(
        url: String,
        destinationFile: File,
        stepLabel: String,
        onProgress: (DownloadProgress) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            destinationFile.parentFile?.mkdirs()

            // 1. Probe total size and Range support
            val probeRequest = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
                .header("Range", "bytes=0-0")
                .build()

            var totalLength = -1L
            var supportsRange = false

            client.newCall(probeRequest).execute().use { probeResponse ->
                if (probeResponse.code == 206) {
                    supportsRange = true
                    val cr = probeResponse.header("Content-Range")
                    if (cr != null && cr.contains("/")) {
                        val totalPart = cr.substringAfterLast("/")
                        totalLength = totalPart.toLongOrNull() ?: -1L
                    }
                } else if (probeResponse.isSuccessful) {
                    totalLength = probeResponse.body?.contentLength() ?: -1L
                }
            }

            // If file already exists and is complete
            if (totalLength > 0 && destinationFile.exists() && destinationFile.length() == totalLength) {
                onProgress(
                    DownloadProgress(
                        downloadedBytes = totalLength,
                        totalBytes = totalLength,
                        speedBytesPerSec = 0L,
                        percentage = 100,
                        currentStep = "$stepLabel (Completed)"
                    )
                )
                return@withContext Result.success(destinationFile)
            }

            // Fallback for non-range streams or tiny files (<4MB)
            if (!supportsRange || totalLength < 4 * 1024 * 1024L) {
                return@withContext ChunkedDownloader.downloadToFile(url, destinationFile, stepLabel, onProgress)
            }

            // 2. Multi-Segment Parallel Download with NIO FileChannel
            val numChunks = calculateChunkCount(totalLength)
            val chunkSize = totalLength / numChunks
            val globalDownloaded = AtomicLong(0L)
            var lastUpdateTime = System.currentTimeMillis()
            var bytesSinceLastUpdate = 0L
            var currentSpeed = 0L

            val randomAccessFile = RandomAccessFile(destinationFile, "rw")
            if (randomAccessFile.length() < totalLength) {
                randomAccessFile.setLength(totalLength)
            }
            val fileChannel = randomAccessFile.channel

            try {
                coroutineScope {
                    val workers = (0 until numChunks).map { chunkIndex ->
                        val startByte = chunkIndex * chunkSize
                        val endByte = if (chunkIndex == numChunks - 1) totalLength - 1 else (startByte + chunkSize - 1)

                        async(Dispatchers.IO) {
                            downloadSegment(
                                url = url,
                                fileChannel = fileChannel,
                                startByte = startByte,
                                endByte = endByte,
                                onBytesRead = { bytesRead ->
                                    val totalRead = globalDownloaded.addAndGet(bytesRead.toLong())
                                    synchronized(globalDownloaded) {
                                        bytesSinceLastUpdate += bytesRead
                                        val now = System.currentTimeMillis()
                                        val elapsed = now - lastUpdateTime
                                        if (elapsed >= 350) {
                                            currentSpeed = (bytesSinceLastUpdate * 1000) / elapsed
                                            val pct = ((totalRead * 100) / totalLength).toInt().coerceIn(0, 100)
                                            onProgress(
                                                DownloadProgress(
                                                    downloadedBytes = totalRead,
                                                    totalBytes = totalLength,
                                                    speedBytesPerSec = currentSpeed,
                                                    percentage = pct,
                                                    currentStep = "$stepLabel (Turbo ${numChunks}-Stream)"
                                                )
                                            )
                                            lastUpdateTime = now
                                            bytesSinceLastUpdate = 0L
                                        }
                                    }
                                }
                            )
                        }
                    }
                    workers.awaitAll()
                }
                fileChannel.force(false)
            } finally {
                try { fileChannel.close() } catch (_: Exception) {}
                try { randomAccessFile.close() } catch (_: Exception) {}
            }

            onProgress(
                DownloadProgress(
                    downloadedBytes = totalLength,
                    totalBytes = totalLength,
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
            t.printStackTrace()
            ChunkedDownloader.downloadToFile(url, destinationFile, stepLabel, onProgress)
        }
    }

    private fun downloadSegment(
        url: String,
        fileChannel: FileChannel,
        startByte: Long,
        endByte: Long,
        onBytesRead: (Int) -> Unit
    ) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Android; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0")
            .header("Range", "bytes=$startByte-$endByte")
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful && response.code != 206) {
            response.close()
            throw Exception("HTTP ${response.code} downloading range $startByte-$endByte")
        }

        val body = response.body ?: throw Exception("Empty segment body")
        val inputStream: InputStream = body.byteStream()
        val buffer = ByteArray(BUFFER_SIZE)
        val byteBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)

        try {
            var currentPos = startByte
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                byteBuffer.clear()
                byteBuffer.put(buffer, 0, bytesRead)
                byteBuffer.flip()
                while (byteBuffer.hasRemaining()) {
                    val written = fileChannel.write(byteBuffer, currentPos)
                    currentPos += written
                }
                onBytesRead(bytesRead)
            }
        } finally {
            try { inputStream.close() } catch (_: Exception) {}
            try { response.close() } catch (_: Exception) {}
        }
    }
}
