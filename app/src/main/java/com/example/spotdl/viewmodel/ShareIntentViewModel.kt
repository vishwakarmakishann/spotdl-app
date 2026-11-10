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
    data class Error(val message: String, val isPrivatePlaylist: Boolean = false) : ShareUiState()
}

class ShareIntentViewModel(application: Application) : AndroidViewModel(application) {

    private val queueManager = DownloadQueueManager.getInstance(application)

    private val _uiState = MutableStateFlow<ShareUiState>(ShareUiState.Idle)
    val uiState: StateFlow<ShareUiState> = _uiState.asStateFlow()

    fun processSharedText(text: String) {
        val extractedUrl = SpotifyResolver.extractUrl(text) ?: text.trim()
        if (extractedUrl.isBlank()) {
            _uiState.value = ShareUiState.Error("No valid link found to process.")
            return
        }

        if (!SpotifyResolver.isSpotifyUrl(extractedUrl)) {
            _uiState.value = ShareUiState.Error("The shared link is not a supported Spotify track, album, or playlist.")
            return
        }

        viewModelScope.launch {
            try {
                // Stage 1: Reading shared link
                _uiState.value = ShareUiState.Resolving(
                    url = extractedUrl,
                    stage = 1,
                    statusMessage = "Reading shared link..."
                )
                delay(200)

                // Stage 2: Fetching music details
                _uiState.value = ShareUiState.Resolving(
                    url = extractedUrl,
                    stage = 2,
                    statusMessage = "Fetching tracklist & artwork..."
                )

                val collection = SpotifyResolver.resolve(extractedUrl)

                if (collection.tracks.isEmpty() || collection.totalTracks == 0) {
                    _uiState.value = ShareUiState.Error(
                        message = "This playlist is private or contains no playable tracks.",
                        isPrivatePlaylist = true
                    )
                    return@launch
                }

                // Stage 3: Checking stream mirrors
                _uiState.value = ShareUiState.Resolving(
                    url = extractedUrl,
                    stage = 3,
                    statusMessage = "Preparing audio streams...",
                    tracksFound = collection.totalTracks,
                    previewCoverUrl = collection.coverUrl ?: collection.tracks.firstOrNull()?.coverUrl
                )
                delay(250)

                _uiState.value = ShareUiState.Resolved(collection)
            } catch (e: Exception) {
                val isPrivate = e.message?.contains("private", ignoreCase = true) == true ||
                                e.message?.contains("status: 403") == true
                val friendlyMessage = when {
                    isPrivate -> "This Spotify playlist is private or restricted. Please set the playlist privacy to Public on Spotify before importing."
                    e is java.net.UnknownHostException -> "No internet connection detected. Please verify your network."
                    e is java.net.SocketTimeoutException -> "Network timed out while contacting Spotify servers."
                    e.message?.contains("status: 404") == true -> "This Spotify link was not found. It may have been deleted."
                    !e.message.isNullOrBlank() -> e.message!!
                    else -> "Failed to resolve Spotify link. Please ensure the link is public and accessible."
                }
                _uiState.value = ShareUiState.Error(
                    message = friendlyMessage,
                    isPrivatePlaylist = isPrivate
                )
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
