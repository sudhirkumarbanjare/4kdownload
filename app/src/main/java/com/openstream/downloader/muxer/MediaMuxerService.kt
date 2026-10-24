package com.openstream.downloader.muxer

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer

object MediaMuxerService {

    suspend fun mergeAudioAndVideo(
        videoFile: File,
        audioFile: File,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        val videoExtractor = MediaExtractor()
        val audioExtractor = MediaExtractor()
        var muxer: MediaMuxer? = null

        try {
            if (outputFile.exists()) outputFile.delete()
            outputFile.parentFile?.mkdirs()

            videoExtractor.setDataSource(videoFile.absolutePath)
            audioExtractor.setDataSource(audioFile.absolutePath)

            // 1. Select video track
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }

            // 2. Select audio track
            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                return@withContext Result.failure(Exception("No video track found in ${videoFile.name}"))
            }

            if (audioTrackIndex == -1 || audioFormat == null) {
                return@withContext Result.failure(Exception("No audio track found in ${audioFile.name}"))
            }

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            val isWebm = outputFile.name.endsWith(".webm", ignoreCase = true)
            val outputFormat = if (isWebm) {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)

            muxer.start()

            // Calculate max buffer size from tracks or default to 4 MB for 4K / high-fps keyframes
            var maxInputSize = 4 * 1024 * 1024
            if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val declared = videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (declared > maxInputSize) maxInputSize = declared
            }
            if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                val declared = audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
                if (declared > maxInputSize) maxInputSize = declared
            }

            val buffer = ByteBuffer.allocateDirect(maxInputSize)
            val bufferInfo = MediaCodec.BufferInfo()

            // 3. Interleaved Muxing by Presentation Timestamp (PTS)
            var videoDone = false
            var audioDone = false

            while (!videoDone || !audioDone) {
                val videoTime = if (!videoDone) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (!audioDone) audioExtractor.sampleTime else Long.MAX_VALUE

                if (videoTime == -1L) {
                    videoDone = true
                    continue
                }
                if (audioTime == -1L) {
                    audioDone = true
                    continue
                }

                // Write whichever sample has the earlier presentation time
                if (!videoDone && (audioDone || videoTime <= audioTime)) {
                    buffer.clear()
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        videoDone = true
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    buffer.clear()
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        audioDone = true
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        audioExtractor.advance()
                    }
                }
            }

            try {
                muxer.stop()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            try {
                muxer.release()
            } catch (_: Exception) {}
            muxer = null

            // Delete temp piece files upon success
            videoFile.delete()
            audioFile.delete()

            Result.success(outputFile)
        } catch (t: Throwable) {
            try {
                muxer?.release()
            } catch (_: Exception) {}
            outputFile.delete()
            Result.failure(t)
        } finally {
            try { videoExtractor.release() } catch (_: Exception) {}
            try { audioExtractor.release() } catch (_: Exception) {}
        }
    }
}
