package com.openstream.downloader.ui

import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.text.format.Formatter
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.openstream.downloader.model.DownloadHistoryRecord
import com.openstream.downloader.model.DownloadStatus
import com.openstream.downloader.model.DownloadTask
import com.openstream.downloader.model.StreamFormat
import com.openstream.downloader.model.VideoMetadata
import com.openstream.downloader.storage.StorageHelper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloaderScreen(
    viewModel: DownloaderViewModel,
    initialSharedUrl: String? = null
) {
    val context = LocalContext.current
    var selectedMainTab by remember { mutableIntStateOf(0) } // 0: Downloading, 1: History

    val activeTasks by viewModel.activeTasks.collectAsState()
    val historyList by viewModel.historyList.collectAsState()
    val settings by viewModel.settings.collectAsState()

    val isAnalyzing by viewModel.isAnalyzing.collectAsState()
    val analysisResult by viewModel.analysisResult.collectAsState()
    val selectedFormat by viewModel.selectedFormat.collectAsState()
    val analysisError by viewModel.analysisError.collectAsState()

    var inputUrl by remember { mutableStateOf(initialSharedUrl ?: "") }
    var showBatchDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var itemToDelete by remember { mutableStateOf<DownloadHistoryRecord?>(null) }
    var showClearAllHistoryDialog by remember { mutableStateOf(false) }

    // SAF Folder Picker Launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Ignore if not supported
            }
            val displayName = uri.lastPathSegment?.substringAfterLast(":") ?: "Custom Folder"
            viewModel.updateDownloadLocation(displayName, uri)
        }
    }

    LaunchedEffect(initialSharedUrl) {
        if (!initialSharedUrl.isNullOrBlank()) {
            inputUrl = initialSharedUrl
            viewModel.analyzeUrl(initialSharedUrl)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OpenStream", fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(6.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                "4K UNLIMITED",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            if (clip.isNotBlank()) {
                                inputUrl = clip
                                viewModel.analyzeUrl(clip)
                            }
                        }
                    }) {
                        Icon(Icons.Default.ContentPaste, contentDescription = "Quick Paste", tint = Color.LightGray)
                    }
                    if (selectedMainTab == 1 && historyList.isNotEmpty()) {
                        IconButton(onClick = { showClearAllHistoryDialog = true }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Clear All History", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings", tint = Color.LightGray)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212),
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Main Tabs: Downloading vs History
            TabRow(
                selectedTabIndex = selectedMainTab,
                containerColor = Color(0xFF1A1A1A),
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                Tab(
                    selected = selectedMainTab == 0,
                    onClick = { selectedMainTab = 0 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Downloading", fontWeight = FontWeight.SemiBold)
                            val runningCount = activeTasks.count { it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.MERGING }
                            if (runningCount > 0) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text("$runningCount") }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedMainTab == 1,
                    onClick = { selectedMainTab = 1 },
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("History", fontWeight = FontWeight.SemiBold)
                            if (historyList.isNotEmpty()) {
                                Spacer(Modifier.width(6.dp))
                                Badge { Text("${historyList.size}") }
                            }
                        }
                    },
                    icon = { Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            if (selectedMainTab == 0) {
                // Downloading Queue View
                DownloadingQueueContent(
                    inputUrl = inputUrl,
                    onUrlChange = {
                        inputUrl = it
                        viewModel.clearAnalysisError()
                    },
                    isAnalyzing = isAnalyzing,
                    analysisError = analysisError,
                    tasks = activeTasks,
                    onAnalyze = { viewModel.analyzeUrl(inputUrl) },
                    onPasteBatch = { showBatchDialog = true },
                    onPauseTask = { viewModel.pauseTask(it) },
                    onResumeTask = { viewModel.resumeTask(it) },
                    onCancelTask = { viewModel.cancelTask(it) },
                    onRetryTask = { viewModel.retryTask(it) },
                    onRemoveTask = { viewModel.removeTask(it) },
                    onClearCompleted = { viewModel.clearCompletedTasks() },
                    onOpenTask = { uriString, mime ->
                        openMedia(context, uriString, mime)
                    },
                    onOpenSettings = { showSettingsSheet = true }
                )
            } else {
                // History View
                HistoryContent(
                    historyList = historyList,
                    onPlay = { record ->
                        openMedia(context, record.fileUri, record.mimeType)
                    },
                    onShare = { record ->
                        shareMedia(context, record.fileUri, record.mimeType)
                    },
                    onDelete = { record ->
                        itemToDelete = record
                    },
                    onClearAll = {
                        showClearAllHistoryDialog = true
                    }
                )
            }
        }
    }

    // Quality Selection Modal Bottom Sheet
    if (analysisResult != null && selectedFormat != null) {
        QualitySelectionSheet(
            metadata = analysisResult!!,
            selectedFormat = selectedFormat!!,
            onSelectFormat = { viewModel.selectFormat(it) },
            onAddToQueue = {
                viewModel.addToQueue()
                selectedMainTab = 0
            },
            onDismiss = { viewModel.dismissAnalysis() }
        )
    }

    // Batch Links Dialog
    if (showBatchDialog) {
        BatchPasteDialog(
            onDismiss = { showBatchDialog = false },
            onQueueUrls = { urls ->
                viewModel.queueBatchUrls(urls)
                showBatchDialog = false
                selectedMainTab = 0
            }
        )
    }

    // Settings Bottom Sheet
    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            context = context,
            onDismiss = { showSettingsSheet = false },
            onChooseFolder = { folderPickerLauncher.launch(null) },
            onResetFolder = { viewModel.resetDefaultLocation() },
            onSetMaxConcurrent = { viewModel.setMaxConcurrent(it) },
            onClearHistory = {
                showSettingsSheet = false
                showClearAllHistoryDialog = true
            }
        )
    }

    // Delete Confirmation Dialog
    if (itemToDelete != null) {
        var deleteFileToo by remember(itemToDelete?.id) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { itemToDelete = null },
            title = { Text("Delete from History") },
            text = {
                Column {
                    Text("Remove \"${itemToDelete?.title}\" from history?")
                    Spacer(Modifier.height(12.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteFileToo = !deleteFileToo }
                    ) {
                        Checkbox(checked = deleteFileToo, onCheckedChange = { deleteFileToo = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Also delete downloaded file from device", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        itemToDelete?.let { viewModel.deleteHistoryRecord(it.id, deleteFileToo) }
                        itemToDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { itemToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Clear All History Dialog
    if (showClearAllHistoryDialog) {
        var deleteAllFiles by remember(showClearAllHistoryDialog) { mutableStateOf(true) }
        AlertDialog(
            onDismissRequest = { showClearAllHistoryDialog = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text("Clear All History", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column {
                    Text("Are you sure you want to clear your entire download history?")
                    Spacer(Modifier.height(14.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { deleteAllFiles = !deleteAllFiles }
                    ) {
                        Checkbox(checked = deleteAllFiles, onCheckedChange = { deleteAllFiles = it })
                        Spacer(Modifier.width(8.dp))
                        Text("Also delete downloaded media files from device", fontSize = 13.sp)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.clearAllHistory(deleteAllFiles)
                        showClearAllHistoryDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Clear All")
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllHistoryDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun DownloadingQueueContent(
    inputUrl: String,
    onUrlChange: (String) -> Unit,
    isAnalyzing: Boolean,
    analysisError: String?,
    tasks: List<DownloadTask>,
    onAnalyze: () -> Unit,
    onPasteBatch: () -> Unit,
    onPauseTask: (String) -> Unit,
    onResumeTask: (String) -> Unit,
    onCancelTask: (String) -> Unit,
    onRetryTask: (String) -> Unit,
    onRemoveTask: (String) -> Unit,
    onClearCompleted: () -> Unit,
    onOpenTask: (String, String) -> Unit,
    onOpenSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    val (internalDrive, sdDrive) = remember { StorageHelper.getStorageSummary(context) }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Link Input Box
        item {
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        "Paste Video / Audio Link or Queue",
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = inputUrl,
                        onValueChange = onUrlChange,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("https://www.youtube.com/watch?v=...", color = Color.DarkGray, fontSize = 13.sp) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = Color(0xFF333333)
                        ),
                        singleLine = true,
                        trailingIcon = {
                            IconButton(onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (clipboard.hasPrimaryClip() && clipboard.primaryClipDescription?.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN) == true) {
                                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    onUrlChange(clip)
                                }
                            }) {
                                Icon(Icons.Default.ContentPaste, contentDescription = "Paste", tint = Color.LightGray)
                            }
                        }
                    )
                    Spacer(Modifier.height(10.dp))
                    // Utility Buttons Row just below link field: Paste, Clear, Batch
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                if (clipboard.hasPrimaryClip()) {
                                    val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                                    if (clip.isNotBlank()) onUrlChange(clip.trim())
                                }
                            },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Paste", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = { onUrlChange("") },
                            modifier = Modifier.weight(1f),
                            enabled = inputUrl.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Clear", fontSize = 13.sp)
                        }

                        OutlinedButton(
                            onClick = onPasteBatch,
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.PlaylistAdd, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Batch", fontSize = 13.sp)
                        }
                    }

                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = onAnalyze,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = inputUrl.isNotBlank() && !isAnalyzing,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(vertical = 12.dp)
                    ) {
                        if (isAnalyzing) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Analyzing Link...", fontSize = 14.sp)
                        } else {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Analyze & Download (No Limits)", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    if (analysisError != null) {
                        Spacer(Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.25f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(10.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Icon(
                                    Icons.Default.WarningAmber,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = analysisError,
                                    color = Color(0xFFFFB4AB),
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp
                                )
                            }
                        }
                    }
                }
            }
        }

        // Live Storage Glance Card
        item {
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenSettings() }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (internalDrive != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Storage,
                                    contentDescription = null,
                                    tint = if (internalDrive.percentUsed > 0.9f) Color(0xFFFF5252) else Color(0xFF00C853),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "Internal: ${internalDrive.formattedFree} Free",
                                    color = Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        if (sdDrive != null) {
                            Box(
                                modifier = Modifier
                                    .height(14.dp)
                                    .width(1.dp)
                                    .background(Color(0xFF444444))
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.SdCard,
                                    contentDescription = null,
                                    tint = Color(0xFFBA68C8),
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "SD: ${sdDrive.formattedFree} Free",
                                    color = Color(0xFFCE93D8),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Icon(
                        Icons.Default.Settings,
                        contentDescription = "Storage Settings",
                        tint = Color.Gray,
                        modifier = Modifier.size(15.dp)
                    )
                }
            }
        }

        // Action header if queue has tasks
        if (tasks.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Download Queue (${tasks.size})",
                        fontWeight = FontWeight.Bold,
                        color = Color.LightGray,
                        fontSize = 14.sp
                    )
                    TextButton(onClick = onClearCompleted) {
                        Text("Clear Finished", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        // Active Tasks List
        if (tasks.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(60.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No active downloads in queue", color = Color.Gray, fontSize = 14.sp)
                        Text("Paste a video link above to start downloading", color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(tasks, key = { it.id }) { task ->
                DownloadTaskCard(
                    task = task,
                    onPause = { onPauseTask(task.id) },
                    onResume = { onResumeTask(task.id) },
                    onCancel = { onCancelTask(task.id) },
                    onRetry = { onRetryTask(task.id) },
                    onRemove = { onRemoveTask(task.id) },
                    onOpen = {
                        val mime = if (task.selectedFormat.container.contains("webm", ignoreCase = true)) "video/webm" else "video/mp4"
                        task.savedUri?.let { uri -> onOpenTask(uri, mime) }
                    }
                )
            }
        }
    }
}

@Composable
fun DownloadTaskCard(
    task: DownloadTask,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onRemove: () -> Unit,
    onOpen: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                if (task.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = task.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 80.dp, height = 50.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(10.dp))
                }

                // Title & Details
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        task.title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                        ) {
                            Text(
                                task.selectedFormat.qualityLabel,
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }

                        if (task.selectedFormat.isHighFps) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = Color(0xFFFFB300).copy(alpha = 0.25f)
                            ) {
                                Text(
                                    "${task.selectedFormat.fps} FPS ⚡",
                                    color = Color(0xFFFFB300),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFF333333)
                        ) {
                            Text(
                                task.selectedFormat.container,
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            )
                        }
                    }
                }

                // Action buttons
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    when (task.status) {
                        DownloadStatus.DOWNLOADING, DownloadStatus.MERGING -> {
                            IconButton(onClick = onPause, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Pause, contentDescription = "Pause", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.PAUSED -> {
                            IconButton(onClick = onResume, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Resume", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.QUEUED -> {
                            IconButton(onClick = onCancel, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Cancel", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.COMPLETED -> {
                            IconButton(onClick = onOpen, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.PlayCircle, contentDescription = "Play", tint = Color(0xFF00C853), modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.FAILED -> {
                            IconButton(onClick = onRetry, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "Remove", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                        DownloadStatus.CANCELLED -> {
                            IconButton(onClick = onRetry, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.Refresh, contentDescription = "Retry", tint = Color(0xFFFFB300), modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                                Icon(Icons.Default.DeleteOutline, contentDescription = "Remove", tint = Color.Gray, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Progress bar
            when (task.status) {
                DownloadStatus.DOWNLOADING -> {
                    LinearProgressIndicator(
                        progress = { task.progress.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        val mbSpeed = String.format("%.2f MB/s", task.progress.speedBytesPerSec / (1024.0 * 1024.0))
                        Text("${task.progress.percentage}% • $mbSpeed", color = Color.LightGray, fontSize = 11.sp)
                        val downloadedMb = String.format("%.1f MB", task.progress.downloadedBytes / (1024.0 * 1024.0))
                        Text(downloadedMb, color = Color.Gray, fontSize = 11.sp)
                    }
                }
                DownloadStatus.PAUSED -> {
                    LinearProgressIndicator(
                        progress = { task.progress.percentage / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color.Gray
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Paused • ${task.progress.percentage}% (Tap ▶ to resume)", color = Color(0xFFFFB300), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        val downloadedMb = String.format("%.1f MB", task.progress.downloadedBytes / (1024.0 * 1024.0))
                        Text(downloadedMb, color = Color.Gray, fontSize = 11.sp)
                    }
                }
                DownloadStatus.MERGING -> {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = Color(0xFFFFB300)
                    )
                    Spacer(Modifier.height(4.dp))
                    Text("Lossless Merging Audio & Video...", color = Color(0xFFFFB300), fontSize = 11.sp)
                }
                DownloadStatus.QUEUED -> {
                    Text("Waiting in queue...", color = Color.Gray, fontSize = 11.sp)
                }
                DownloadStatus.COMPLETED -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF00C853), modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Download Complete • Saved", color = Color(0xFF00C853), fontSize = 11.sp)
                    }
                }
                DownloadStatus.FAILED -> {
                    Text("Error: ${task.errorMessage ?: "Failed"}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                }
                DownloadStatus.CANCELLED -> {
                    Text("Download cancelled", color = Color.Gray, fontSize = 11.sp)
                }
                else -> {}
            }
        }
    }
}

@Composable
fun HistoryContent(
    historyList: List<DownloadHistoryRecord>,
    onPlay: (DownloadHistoryRecord) -> Unit,
    onShare: (DownloadHistoryRecord) -> Unit,
    onDelete: (DownloadHistoryRecord) -> Unit,
    onClearAll: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (historyList.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "${historyList.size} Completed Downloads",
                        color = Color.LightGray,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                    TextButton(onClick = onClearAll) {
                        Text("Clear All", color = Color.Gray, fontSize = 12.sp)
                    }
                }
            }
        }

        if (historyList.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.History, contentDescription = null, tint = Color.DarkGray, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(8.dp))
                        Text("No download history yet", color = Color.Gray, fontSize = 14.sp)
                        Text("Completed downloads will appear here", color = Color.DarkGray, fontSize = 12.sp)
                    }
                }
            }
        } else {
            items(historyList, key = { it.id }) { record ->
                HistoryRecordCard(
                    record = record,
                    onPlay = { onPlay(record) },
                    onShare = { onShare(record) },
                    onDelete = { onDelete(record) }
                )
            }
        }
    }
}

@Composable
fun HistoryRecordCard(
    record: DownloadHistoryRecord,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val dateStr = remember(record.completedAt) {
        SimpleDateFormat("MMM d, yyyy • h:mm a", Locale.getDefault()).format(Date(record.completedAt))
    }

    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Thumbnail with Play overlay
            Box(
                modifier = Modifier
                    .size(width = 85.dp, height = 56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onPlay() },
                contentAlignment = Alignment.Center
            ) {
                if (record.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = record.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(Modifier.fillMaxSize().background(Color(0xFF2C2C2C)))
                }
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(Color.Black.copy(alpha = 0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = "Play", tint = Color.White, modifier = Modifier.size(18.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            // Info
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 4.dp)
            ) {
                Text(
                    text = record.title,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    fontSize = 13.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Spacer(Modifier.height(4.dp))

                // Badges Row (Quality & High FPS)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    ) {
                        Text(
                            text = record.qualityLabel,
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    if (record.fps >= 50) {
                        Surface(
                            shape = RoundedCornerShape(4.dp),
                            color = Color(0xFFFFB300).copy(alpha = 0.25f)
                        ) {
                            Text(
                                text = "${record.fps} FPS ⚡",
                                color = Color(0xFFFFB300),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(5.dp))

                // File format, size & date line
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = record.container,
                        color = Color(0xFFCCCCCC),
                        fontSize = 10.5.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (record.formattedSize.isNotBlank()) {
                        Text("•", color = Color.DarkGray, fontSize = 10.sp)
                        Text(
                            text = record.formattedSize,
                            color = Color(0xFF81C784),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Text("•", color = Color.DarkGray, fontSize = 10.sp)
                    Text(
                        text = dateStr,
                        color = Color.Gray,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Action Buttons
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                IconButton(
                    onClick = onShare,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.Share,
                        contentDescription = "Share",
                        tint = Color.LightGray,
                        modifier = Modifier.size(17.dp)
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Default.DeleteOutline,
                        contentDescription = "Delete",
                        tint = Color(0xFFFF5252).copy(alpha = 0.85f),
                        modifier = Modifier.size(17.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QualitySelectionSheet(
    metadata: VideoMetadata,
    selectedFormat: StreamFormat,
    onSelectFormat: (StreamFormat) -> Unit,
    onAddToQueue: () -> Unit,
    onDismiss: () -> Unit
) {
    BackHandler(enabled = true) {
        onDismiss()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { sheetValue ->
            // Prevent down-swipe from dismissing the sheet; dismiss via Back, outside tap, or X button
            sheetValue != SheetValue.Hidden
        }
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            // Header with Close X Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (metadata.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = metadata.thumbnailUrl,
                        contentDescription = null,
                        modifier = Modifier
                            .size(width = 85.dp, height = 55.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        metadata.title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontSize = 15.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(metadata.uploader, color = Color.Gray, fontSize = 12.sp)
                }

                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF2C2C2C), CircleShape)
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "Close",
                        tint = Color.LightGray,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))

            // Tabs: Video vs Audio
            val videoFormats = remember(metadata.availableFormats) {
                metadata.availableFormats.filter { it.isVideo }
            }
            val audioFormats = remember(metadata.availableFormats) {
                metadata.availableFormats.filter { !it.isVideo }
            }
            var selectedTab by remember { mutableIntStateOf(if (selectedFormat.isVideo) 0 else 1) }

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = Color(0xFF141414),
                contentColor = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                        videoFormats.firstOrNull()?.let { onSelectFormat(it) }
                    },
                    text = { Text("Video (${videoFormats.size})", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                        audioFormats.firstOrNull()?.let { onSelectFormat(it) }
                    },
                    text = { Text("Audio (${audioFormats.size})", fontWeight = FontWeight.SemiBold) },
                    icon = { Icon(Icons.Default.Audiotrack, contentDescription = null, modifier = Modifier.size(18.dp)) }
                )
            }

            Spacer(Modifier.height(12.dp))

            // Format List
            val displayedFormats = if (selectedTab == 0) videoFormats else audioFormats
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 280.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(displayedFormats, key = { it.id }) { format ->
                    val isSelected = format.id == selectedFormat.id
                    Surface(
                        onClick = { onSelectFormat(format) },
                        shape = RoundedCornerShape(10.dp),
                        color = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f) else Color(0xFF262626),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        format.qualityLabel,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        fontSize = 14.sp
                                    )
                                    if (format.isHighFps) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFFFB300).copy(alpha = 0.25f)
                                        ) {
                                            Text(
                                                "${format.fps} FPS ⚡",
                                                color = Color(0xFFFFB300),
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 10.sp,
                                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                            )
                                        }
                                    } else if (format.fps > 0) {
                                        Text("${format.fps} fps", color = Color.Gray, fontSize = 11.sp)
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (format.container == "MP4") Color(0xFF1976D2).copy(alpha = 0.25f) else Color(0xFF7B1FA2).copy(alpha = 0.25f)
                                    ) {
                                        Text(
                                            format.container,
                                            color = if (format.container == "MP4") Color(0xFF64B5F6) else Color(0xFFBA68C8),
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                        )
                                    }
                                }
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(format.resolution, color = Color.Gray, fontSize = 11.sp)
                                    if (format.formattedSize.isNotBlank()) {
                                        Text("•", color = Color.DarkGray, fontSize = 11.sp)
                                        Text("~${format.formattedSize}", color = Color(0xFF81C784), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                    }
                                }
                            }

                            RadioButton(
                                selected = isSelected,
                                onClick = { onSelectFormat(format) },
                                colors = RadioButtonDefaults.colors(
                                    selectedColor = MaterialTheme.colorScheme.primary,
                                    unselectedColor = Color.Gray
                                )
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Add to Queue Button
            Button(
                onClick = onAddToQueue,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C853)),
                shape = RoundedCornerShape(12.dp),
                contentPadding = PaddingValues(vertical = 12.dp)
            ) {
                Icon(Icons.Default.AddCircleOutline, contentDescription = null, tint = Color.Black)
                Spacer(Modifier.width(8.dp))
                val sizeText = if (selectedFormat.formattedSize.isNotBlank()) " • ~${selectedFormat.formattedSize}" else ""
                val fpsText = if (selectedFormat.fps >= 50) " ${selectedFormat.fps}fps" else ""
                Text(
                    "Download ${selectedFormat.qualityLabel}$fpsText [${selectedFormat.container}]$sizeText",
                    color = Color.Black,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun BatchPasteDialog(
    onDismiss: () -> Unit,
    onQueueUrls: (List<String>) -> Unit
) {
    val context = LocalContext.current
    var batchText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Paste Multiple Links") },
        text = {
            Column {
                Text(
                    "Paste multiple video links (one URL per line). Each will be added to the download queue:",
                    fontSize = 13.sp,
                    color = Color.LightGray
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = batchText,
                    onValueChange = { batchText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(140.dp),
                    placeholder = { Text("https://www.youtube.com/watch?v=...\nhttps://youtu.be/...", color = Color.DarkGray, fontSize = 12.sp) }
                )
                Spacer(Modifier.height(8.dp))
                TextButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        if (clipboard.hasPrimaryClip()) {
                            val clip = clipboard.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                            batchText = if (batchText.isBlank()) clip else "$batchText\n$clip"
                        }
                    }
                ) {
                    Icon(Icons.Default.ContentPaste, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Paste from Clipboard")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val urls = batchText.lines().map { it.trim() }.filter { it.isNotBlank() }
                    onQueueUrls(urls)
                },
                enabled = batchText.isNotBlank()
            ) {
                Text("Add All to Queue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: com.openstream.downloader.model.AppSettings,
    context: Context,
    onDismiss: () -> Unit,
    onChooseFolder: () -> Unit,
    onResetFolder: () -> Unit,
    onSetMaxConcurrent: (Int) -> Unit,
    onClearHistory: () -> Unit
) {
    BackHandler(enabled = true) {
        onDismiss()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { sheetValue ->
            sheetValue != SheetValue.Hidden
        }
    )
    val storageDrives = remember { StorageHelper.getStorageDrives(context) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E1E),
        dragHandle = null
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Settings", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = Color.White)
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color(0xFF2C2C2C), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.LightGray, modifier = Modifier.size(18.dp))
                }
            }
            Spacer(Modifier.height(16.dp))

            // Download Location
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("Download Location", fontWeight = FontWeight.SemiBold, color = Color.White)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        settings.downloadLocationName,
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = onChooseFolder,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text("Select Folder")
                        }
                        if (settings.customTreeUri != null) {
                            OutlinedButton(
                                onClick = onResetFolder,
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Text("Reset to Default")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Concurrent Downloads
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Maximum Concurrent Downloads", fontWeight = FontWeight.SemiBold, color = Color.White)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(1, 2, 3, 5).forEach { limit ->
                            val isSelected = settings.maxConcurrentDownloads == limit
                            Surface(
                                onClick = { onSetMaxConcurrent(limit) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) MaterialTheme.colorScheme.primary else Color(0xFF333333),
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier.padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        "$limit",
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.Black else Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Device & SD Card Storage
            Text("Storage & Available Space", fontWeight = FontWeight.SemiBold, color = Color.White, fontSize = 15.sp)
            Spacer(Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                storageDrives.forEach { drive ->
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF262626))
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        if (drive.isRemovable) Icons.Default.SdCard else Icons.Default.Storage,
                                        contentDescription = null,
                                        tint = if (drive.isRemovable) Color(0xFFBA68C8) else MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(drive.description, fontWeight = FontWeight.SemiBold, color = Color.White)
                                }
                                if (drive.isRemovable) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFBA68C8).copy(alpha = 0.25f)
                                    ) {
                                        Text(
                                            "SD Card",
                                            color = Color(0xFFCE93D8),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${drive.formattedFree} Free",
                                    color = if (drive.percentUsed > 0.9f) Color(0xFFFF5252) else Color(0xFF00C853),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Text("Total capacity: ${drive.formattedTotal}", color = Color.Gray, fontSize = 11.sp)
                            }
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { drive.percentUsed },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = if (drive.percentUsed > 0.9f) Color(0xFFFF5252) else if (drive.isRemovable) Color(0xFFBA68C8) else MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Clear History
            OutlinedButton(
                onClick = {
                    onClearHistory()
                    onDismiss()
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Clear All Download History")
            }

            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun openMedia(context: Context, uriString: String, mimeType: String) {
    try {
        val uri = Uri.parse(uriString)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeType)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

private fun shareMedia(context: Context, uriString: String, mimeType: String) {
    try {
        val uri = Uri.parse(uriString)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "Share Downloaded Media"))
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
