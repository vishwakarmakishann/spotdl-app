package com.example.spotdl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spotdl.engine.SpotifyResolver
import com.example.spotdl.model.CollectionMetadata
import com.example.spotdl.queue.DownloadQueueManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed class ShareUiState {
    object Idle : ShareUiState()
    data class Resolving(
        val url: String,
        val stage: Int, // 1: Reading shared link, 2: Fetching details, 3: Checking availability
        val statusMessage: String,
        val tracksFound: Int = 0,
        val previewCoverUrl: String? = null
    ) : ShareUiState()
    data class Resolved(val collection: CollectionMetadata) : ShareUiState()
    data class Error(val message: String) : ShareUiState()
}

class ShareIntentViewModel(application: Application) : AndroidViewModel(application) {

    private val queueManager = DownloadQueueManager.getInstance(application)

    private val _uiState = MutableStateFlow<ShareUiState>(ShareUiState.Idle)
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    fun processSharedText(text: String) {
        val extractedUrl = SpotifyResolver.extractUrl(text) ?: text.trim()
        if (extractedUrl.isBlank()) return

        viewModelScope.launch {
            try {
                // Stage 1: Reading shared link
                _uiState.value = ShareUiState.Resolving(
                    url = extractedUrl,
                    stage = 1,
                    statusMessage = "Reading shared link payload..."
                )
                delay(300) // Brief visual cadence matching Stitch radar UX

                // Stage 2: Fetching music details
                _uiState.value = ShareUiState.Resolving(
                    url = extractedUrl,
                    stage = 2,
                    statusMessage = "Fetching metadata & artwork..."
                )

                val collection = SpotifyResolver.resolve(extractedUrl)

                // Stage 3: Checking stream mirrors
                _uiState.value = ShareUiState.Resolving(
                    url = extractedUrl,
                    stage = 3,
                    statusMessage = "Verifying lossless stream mirrors...",
                    tracksFound = collection.totalTracks,
                    previewCoverUrl = collection.coverUrl ?: collection.tracks.firstOrNull()?.coverUrl
                )
                delay(400)

                _uiState.value = ShareUiState.Resolved(collection)
            } catch (e: Exception) {
                _uiState.value = ShareUiState.Error(e.message ?: "Failed to resolve shared link")
            }
        }
    }

    fun startDownload(collection: CollectionMetadata, selectedIndices: Set<Int>? = null) {
        queueManager.enqueue(collection, selectedIndices)
    }

    fun resetState() {
        _uiState.value = ShareUiState.Idle
    }
}

