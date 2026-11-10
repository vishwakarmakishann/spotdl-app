package com.example.spotdl.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.spotdl.model.DownloadItem
import com.example.spotdl.model.DownloadStatus
import com.example.spotdl.ui.components.KeylineCard
import com.example.spotdl.ui.components.LinearGauge
import com.example.spotdl.ui.components.formatFileSize
import com.example.spotdl.ui.theme.EditorialCanvas
import com.example.spotdl.ui.theme.EditorialKeyline
import com.example.spotdl.ui.theme.EditorialPrimary
import com.example.spotdl.ui.theme.EditorialPrimaryContainer
import com.example.spotdl.viewmodel.QueueViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DownloadQueueScreen(
    viewModel: QueueViewModel,
    modifier: Modifier = Modifier
) {
    val queue by viewModel.queue.collectAsState()
    val batchState by viewModel.batchState.collectAsState()
    val activeItem by viewModel.activeItem.collectAsState()

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Queue",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    if (queue.isNotEmpty()) {
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, contentDescription = "Queue Options")
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Retry failed") },
                                    onClick = {
                                        viewModel.retryFailed()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Refresh, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Clear completed") },
                                    onClick = {
                                        viewModel.clearCompleted()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.CheckCircle, contentDescription = null) }
                                )
                                DropdownMenuItem(
                                    text = { Text("Cancel whole queue", color = MaterialTheme.colorScheme.error) },
                                    onClick = {
                                        viewModel.cancelQueue()
                                        showMenu = false
                                    },
                                    leadingIcon = { Icon(Icons.Default.Cancel, contentDescription = null, tint = MaterialTheme.colorScheme.error) }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = EditorialCanvas)
            )
        },
        containerColor = EditorialCanvas,
        modifier = modifier
    ) { padding ->
        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.MusicNote,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Queue is empty",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Share songs or playlists from Spotify or YouTube Music to start downloading.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
            ) {
                item {
                    Spacer(modifier = Modifier.height(8.dp))

                    // Header row: Track count & Pause/Resume
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (queue.size == 1) "1 track in queue" else "${queue.size} tracks in queue",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )

                        // Pause / Resume All Button (only when there are active/waiting tracks or paused)
                        if (batchState.activeCount > 0 || batchState.waitingCount > 0 || batchState.isPaused) {
                            Surface(
                                onClick = { viewModel.togglePauseResume() },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.secondaryContainer,
                                modifier = Modifier.clip(CircleShape)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = if (batchState.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (batchState.isPaused) "Resume" else "Pause all",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Batch Progress Card (only if batch total > 0)
                    if (batchState.totalCount > 0) {
                        KeylineCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f, fill = false)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PlaylistPlay,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = batchState.collectionTitle ?: "Batch Progress",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clip(CircleShape)
                                            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f))
                                            .padding(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text(
                                            text = String.format(java.util.Locale.US, "%.0f%%", batchState.overallPercent),
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                LinearGauge(
                                    progressPercent = batchState.overallPercent,
                                    barHeight = 6.dp
                                )
                                Spacer(modifier = Modifier.height(10.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "${batchState.completedCount} of ${batchState.totalCount} completed",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )

                                    val statusNote = when {
                                        batchState.isPaused -> "Paused"
                                        batchState.currentSpeedBytesPerSec > 0 && batchState.activeCount > 0 -> "${formatFileSize(batchState.currentSpeedBytesPerSec)}/s"
                                        batchState.etaSeconds > 0 -> {
                                            val mins = batchState.etaSeconds / 60
                                            val secs = batchState.etaSeconds % 60
                                            if (mins > 0) "~${mins}m ${secs}s left" else "~${secs}s left"
                                        }
                                        batchState.completedCount == batchState.totalCount && batchState.totalCount > 0 -> "Done"
                                        else -> ""
                                    }

                                    if (statusNote.isNotEmpty()) {
                                        Text(
                                            text = statusNote,
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                }

                                if (batchState.failedCount > 0) {
                                    Spacer(modifier = Modifier.height(10.dp))
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f))
                                            .padding(horizontal = 10.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "${batchState.failedCount} track(s) failed",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.error,
                                            fontWeight = FontWeight.Medium
                                        )
                                        TextButton(
                                            onClick = { viewModel.retryFailed() }
                                        ) {
                                            Text(
                                                text = "Retry Failed",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Currently active downloading track hero card
                    if (activeItem != null && (activeItem!!.status == DownloadStatus.DOWNLOADING || activeItem!!.status == DownloadStatus.EMBEDDING)) {
                        val active = activeItem!!

                        KeylineCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(14.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    // Thumbnail
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        val cover = active.trackMetadata.coverUrl
                                        val activeCoverModel: Any? = if (!cover.isNullOrBlank()) {
                                            if (cover.startsWith("/")) java.io.File(cover) else cover
                                        } else {
                                            active.audioPath?.let { path ->
                                                java.io.File(java.io.File(path).parentFile, "cover.jpg").takeIf { it.exists() }
                                            }
                                        }

                                        if (activeCoverModel != null) {
                                            AsyncImage(
                                                model = activeCoverModel,
                                                contentDescription = null,
                                                modifier = Modifier.fillMaxSize(),
                                                contentScale = ContentScale.Crop
                                            )
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.MusicNote,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column(modifier = Modifier.weight(1f)) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Text(
                                                text = active.trackMetadata.title,
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Text(
                                                text = if (active.status == DownloadStatus.EMBEDDING) "Tagging..." else String.format(java.util.Locale.US, "%.0f%%", active.progressPercent),
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                        Text(
                                            text = active.trackMetadata.artist,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))

                                        val textBytes = if (active.totalBytes > 0) {
                                            "${formatFileSize(active.downloadedBytes)} / ${formatFileSize(active.totalBytes)}"
                                        } else if (active.downloadedBytes > 0) {
                                            formatFileSize(active.downloadedBytes)
                                        } else {
                                            ""
                                        }
                                        if (textBytes.isNotEmpty()) {
                                            Text(
                                                text = textBytes,
                                                fontSize = 11.sp,
                                                color = MaterialTheme.colorScheme.secondary
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.width(8.dp))

                                    IconButton(
                                        onClick = { viewModel.cancelTrack(active.id) },
                                        modifier = Modifier.size(28.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "Cancel",
                                            tint = MaterialTheme.colorScheme.secondary,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                LinearGauge(
                                    progressPercent = active.progressPercent,
                                    barHeight = 4.dp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    Text(
                        text = "Tracks",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }

                items(queue) { item ->
                    QueueRowItem(
                        item = item,
                        onCancel = { viewModel.cancelTrack(item.id) },
                        onRetry = { viewModel.retryTrack(item.id) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                }

                item {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }
    }
}

@Composable
fun QueueRowItem(
    item: DownloadItem,
    onCancel: () -> Unit,
    onRetry: () -> Unit
) {
    KeylineCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Status Icon / Number
            when (item.status) {
                DownloadStatus.COMPLETED -> {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                DownloadStatus.DOWNLOADING, DownloadStatus.RESOLVING, DownloadStatus.EMBEDDING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                DownloadStatus.FAILED -> {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(20.dp)
                    )
                }
                else -> {
                    val trackNum = item.trackMetadata.trackNumber
                    Text(
                        text = if (trackNum != null) String.format("%02d", trackNum) else "--",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Artwork
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                val cover = item.trackMetadata.coverUrl
                val itemCoverModel: Any? = if (!cover.isNullOrBlank()) {
                    if (cover.startsWith("/")) java.io.File(cover) else cover
                } else {
                    item.audioPath?.let { path ->
                        java.io.File(java.io.File(path).parentFile, "cover.jpg").takeIf { it.exists() }
                    }
                }

                if (itemCoverModel != null) {
                    AsyncImage(
                        model = itemCoverModel,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.trackMetadata.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = item.trackMetadata.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.secondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.status == DownloadStatus.FAILED && !item.errorMessage.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.errorMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 11.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Status chip / Retry Action
            if (item.status == DownloadStatus.FAILED) {
                IconButton(
                    onClick = onRetry,
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Retry track",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(
                            when (item.status) {
                                DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                                DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary.copy(alpha = 0.15f)
                                else -> MaterialTheme.colorScheme.surfaceContainer
                            }
                        )
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = when (item.status) {
                            DownloadStatus.COMPLETED -> "Done"
                            DownloadStatus.DOWNLOADING -> String.format("%.0f%%", item.progressPercent)
                            DownloadStatus.PAUSED -> "Paused"
                            DownloadStatus.RESOLVING -> "Resolving"
                            DownloadStatus.EMBEDDING -> "Tagging"
                            DownloadStatus.QUEUED -> "Queued"
                            else -> ""
                        },
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = when (item.status) {
                            DownloadStatus.COMPLETED -> MaterialTheme.colorScheme.primary
                            DownloadStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.secondary
                        }
                    )
                }
            }

            if (item.status != DownloadStatus.COMPLETED) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = onCancel,
                    modifier = Modifier.size(24.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Cancel track",
                        tint = MaterialTheme.colorScheme.outline,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
        }
    }
}

