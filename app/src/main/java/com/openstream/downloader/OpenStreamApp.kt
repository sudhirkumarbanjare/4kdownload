package com.openstream.downloader

import android.app.Application
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

import com.openstream.downloader.engine.DownloadQueueManager
import com.openstream.downloader.network.NetworkManager
import com.openstream.downloader.storage.HistoryRepository
import com.openstream.downloader.storage.SettingsRepository
import android.util.Log

class OpenStreamApp : Application() {

    companion object {
        lateinit var instance: OpenStreamApp
            private set
    }

    lateinit var historyRepository: HistoryRepository
        private set
    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var queueManager: DownloadQueueManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this

        NetworkManager.init(this)
        historyRepository = HistoryRepository(this)
        settingsRepository = SettingsRepository(this)
        queueManager = DownloadQueueManager(this, historyRepository, settingsRepository)
        
        // Initialize NewPipeExtractor with an OkHttp-based Downloader
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        NewPipe.init(object : Downloader() {
            private val browserUserAgent =
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

            @Throws(IOException::class)
            override fun execute(request: Request): Response {
                val httpMethod = request.httpMethod()
                val url = request.url()
                val headers = request.headers()
                val dataToSend = request.dataToSend()

                val requestBuilder = okhttp3.Request.Builder()
                    .url(url)

                var hasUserAgent = false
                headers.forEach { (key, values) ->
                    if (key.equals("User-Agent", ignoreCase = true)) {
                        hasUserAgent = true
                    }
                    values.forEach { value ->
                        requestBuilder.addHeader(key, value)
                    }
                }
                if (!hasUserAgent) {
                    requestBuilder.addHeader("User-Agent", browserUserAgent)
                }

                val body = dataToSend?.toRequestBody(null)
                when (httpMethod) {
                    "GET" -> requestBuilder.get()
                    "POST" -> requestBuilder.post(body ?: "".toRequestBody(null))
                    "HEAD" -> requestBuilder.head()
                    else -> requestBuilder.method(httpMethod, body)
                }

                val call = client.newCall(requestBuilder.build())
                val okResponse = call.execute()

                var responseBody = okResponse.body?.string() ?: ""
                var finalCode = okResponse.code
                var finalMessage = okResponse.message
                var finalHeaders = okResponse.headers.toMultimap()
                var finalUrl = okResponse.request.url.toString()

                // Check if YouTube returned bot rate-limit / LOGIN_REQUIRED on current network
                val isBotChallenge = url.contains("youtube", ignoreCase = true) && (
                    responseBody.contains("Sign in to confirm", ignoreCase = true) ||
                    (responseBody.contains("LOGIN_REQUIRED") && responseBody.contains("bot", ignoreCase = true))
                )

                if (isBotChallenge) {
                    val cellularClient = NetworkManager.getCellularOkHttpClient(client)
                    if (cellularClient != null) {
                        try {
                            val cellularCall = cellularClient.newCall(requestBuilder.build())
                            val cellularResp = cellularCall.execute()
                            val cellularBody = cellularResp.body?.string() ?: ""
                            if (cellularResp.isSuccessful && !cellularBody.contains("LOGIN_REQUIRED")) {
                                Log.d("OpenStreamApp", "Auto-bypassed YouTube bot challenge via Cellular failover!")
                                finalCode = cellularResp.code
                                finalMessage = cellularResp.message
                                finalHeaders = cellularResp.headers.toMultimap()
                                responseBody = cellularBody
                                finalUrl = cellularResp.request.url.toString()
                            }
                        } catch (e: Exception) {
                            Log.w("OpenStreamApp", "Cellular failover request failed: ${e.message}")
                        }
                    }
                }

                return Response(
                    finalCode,
                    finalMessage,
                    finalHeaders,
                    responseBody,
                    finalUrl
                )
            }
        })
    }
}
