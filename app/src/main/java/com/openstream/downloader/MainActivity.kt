package com.openstream.downloader

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.openstream.downloader.ui.DownloaderScreen
import com.openstream.downloader.ui.DownloaderViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DownloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sharedUrl = extractUrlFromIntent(intent)

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                DownloaderScreen(
                    viewModel = viewModel,
                    initialSharedUrl = sharedUrl
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        extractUrlFromIntent(intent)?.let { url ->
            viewModel.analyzeUrl(url)
        }
    }

    private fun extractUrlFromIntent(intent: Intent?): String? {
        if (intent?.action == Intent.ACTION_SEND && intent.type == "text/plain") {
            return intent.getStringExtra(Intent.EXTRA_TEXT)?.let { text ->
                // Extract first HTTP/HTTPS URL from shared text
                val regex = "(https?://\\S+)".toRegex()
                regex.find(text)?.value ?: text.trim()
            }
        }
        return null
    }
}
