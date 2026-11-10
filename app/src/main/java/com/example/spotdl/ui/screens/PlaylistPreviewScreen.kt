package com.example.spotdl.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.spotdl.model.CollectionMetadata
import com.example.spotdl.model.CollectionType
import com.example.spotdl.ui.components.KeylineCard
import com.example.spotdl.ui.theme.EditorialCanvas
import com.example.spotdl.ui.theme.EditorialKeyline
import com.example.spotdl.ui.theme.EditorialSurface

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistPreviewScreen(
    collection: CollectionMetadata,
    onStartDownload: (Set<Int>) -> Unit,
    onBack: () -> Unit
) {
    var selectedIndices by remember {
        mutableStateOf(collection.tracks.indices.toSet())
    }

    val selectedCount = selectedIndices.size

    val screenTitle = when (collection.type) {
        CollectionType.TRACK -> "Track Preview"
        CollectionType.ALBUM -> "Album Preview"
        CollectionType.PLAYLIST -> "Playlist Preview"
    }

    val actionButtonText = when (collection.type) {
        CollectionType.TRACK -> "Download track"
        CollectionType.ALBUM -> "Download album"
        CollectionType.PLAYLIST -> "Download playlist"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = screenTitle,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = EditorialCanvas
                )
            )
        },
        bottomBar = {
            // Sticky Bottom Action Bar
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, EditorialKeyline),
                color = EditorialSurface,
                shadowElevation = 0.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        val countLabel = when (selectedCount) {
                            0 -> "No tracks selected"
                            1 -> "1 track selected"
                            else -> "$selectedCount tracks selected"
                        }
                        val subtitle = when {
                            selectedCount == 0 -> "Tap items to include"
                            collection.type == CollectionType.TRACK -> "Lossless bitstream extraction"
                            else -> "Ready to queue $selectedCount tracks"
                        }
                        Text(
                            text = countLabel,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.secondary
                        )
                    }

                    Button(
                        onClick = {
                            if (selectedCount > 0) {
                                onStartDownload(selectedIndices)
                            }
                        },
                        enabled = selectedCount > 0,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = actionButtonText,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
        },
        containerColor = EditorialCanvas
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            item {
                Spacer(modifier = Modifier.height(8.dp))

                // Source Indicator Badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        modifier = Modifier
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.tertiary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        val badgeType = when (collection.type) {
                            CollectionType.TRACK -> "Single Track"
                            CollectionType.ALBUM -> "Official Album"
                            CollectionType.PLAYLIST -> "Curated Playlist"
                        }
                        Text(
                            text = "Spotify · $badgeType",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    val itemsLabel = if (collection.totalTracks == 1) "1 track" else "${collection.totalTracks} tracks"
                    Text(
                        text = itemsLabel,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Hero Artwork
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val cover = collection.coverUrl ?: collection.tracks.firstOrNull()?.coverUrl
                    Box(
                        modifier = Modifier
                            .size(240.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .border(1.dp, EditorialKeyline, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!cover.isNullOrBlank()) {
                            AsyncImage(
                                model = cover,
                                contentDescription = "Collection cover",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(48.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Text(
                        text = collection.title,
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    val curatorOrArtist = when (collection.type) {
                        CollectionType.TRACK -> "${collection.tracks.firstOrNull()?.artist ?: "Unknown Artist"} · Master Stream"
                        CollectionType.ALBUM -> "${collection.curator ?: collection.tracks.firstOrNull()?.artist ?: "Unknown Artist"} · ${collection.totalTracks} tracks"
                        CollectionType.PLAYLIST -> "Curated by ${collection.curator ?: "Spotify"} · ${collection.totalTracks} tracks"
                    }
                    Text(
                        text = curatorOrArtist,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Availability Summary Stats Banner
                KeylineCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Total", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                            Text("${collection.totalTracks}", style = MaterialTheme.typography.headlineSmall)
                        }
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(EditorialKeyline))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(MaterialTheme.colorScheme.tertiary))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Available", fontSize = 11.sp, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Text("${collection.totalTracks}", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.tertiary)
                        }
                        Box(modifier = Modifier.width(1.dp).height(24.dp).background(EditorialKeyline))
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("Selected", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary)
                            Text("$selectedCount", style = MaterialTheme.typography.headlineSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Tracklist Header & Select All
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "Tracks",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surfaceContainer)
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "${collection.totalTracks}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                    }

                    val allSelected = selectedCount == collection.tracks.size
                    TextButton(
                        onClick = {
                            selectedIndices = if (allSelected) emptySet() else collection.tracks.indices.toSet()
                        }
                    ) {
                        Icon(
                            imageVector = if (allSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (allSelected) "Deselect all" else "Select all",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Track List Items
            itemsIndexed(collection.tracks) { index, track ->
                val isSelected = selectedIndices.contains(index)
                val trackNumStr = String.format("%02d", index + 1)

                KeylineCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp)
                        .clickable {
                            selectedIndices = if (isSelected) {
                                selectedIndices - index
                            } else {
                                selectedIndices + index
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Checkbox
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .clip(CircleShape)
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isSelected) {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = trackNumStr,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.secondary,
                            fontWeight = FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.width(10.dp))

                        // Artwork thumbnail
                        val thumb = track.coverUrl ?: collection.coverUrl
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!thumb.isNullOrBlank()) {
                                AsyncImage(
                                    model = thumb,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Text(
                                    text = trackNumStr,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Title & Artist
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = track.title,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = track.artist,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        // Duration if available
                        if (track.durationSeconds != null && track.durationSeconds > 0) {
                            val mins = track.durationSeconds / 60
                            val secs = track.durationSeconds % 60
                            Text(
                                text = String.format(java.util.Locale.US, "%d:%02d", mins, secs),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.secondary,
                                fontWeight = FontWeight.Normal
                            )
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

