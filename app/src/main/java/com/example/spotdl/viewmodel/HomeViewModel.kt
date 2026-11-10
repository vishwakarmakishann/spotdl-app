package com.example.spotdl.viewmodel

import android.app.Application
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spotdl.engine.MediaStoreHelper
import com.example.spotdl.engine.SpotifyResolver
import com.example.spotdl.queue.DownloadQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class RecentDownload(
    val title: String,
    val artist: String,
    val filePath: String,
    val coverFile: File?,
    val format: String,
    val lastModified: Long
)

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val queueManager = DownloadQueueManager.getInstance(application)
    val batchState = queueManager.batchState
    val activeItem = queueManager.activeItem

    private val _clipboardUrl = MutableStateFlow<String?>(null)
    val clipboardUrl: StateFlow<String?> = _clipboardUrl.asStateFlow()

    private val _recentDownloads = MutableStateFlow<List<RecentDownload>>(emptyList())
    val recentDownloads: StateFlow<List<RecentDownload>> = _recentDownloads.asStateFlow()

    fun checkClipboard() {
        try {
            val clipboard = getApplication<Application>().getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = clipboard?.primaryClip
            if (clip != null && clip.itemCount > 0) {
                val text = clip.getItemAt(0).text?.toString()
                if (SpotifyResolver.isSpotifyUrl(text) || text?.contains("music.youtube.com") == true || text?.contains("youtu.be") == true) {
                    _clipboardUrl.value = text
                }
            }
        } catch (ignored: Exception) {}
    }

    fun dismissClipboardBanner() {
        _clipboardUrl.value = null
    }

    fun loadRecentDownloads() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseDir = MediaStoreHelper.getDownloadBaseDir(getApplication())
            val audioExtensions = listOf("opus", "m4a", "mp3", "flac")
            val results = mutableListOf<RecentDownload>()

            baseDir.walkTopDown().maxDepth(4).forEach { file ->
                if (file.isFile && audioExtensions.contains(file.extension.lowercase())) {
                    val cover = file.parentFile?.listFiles()?.firstOrNull {
                        it.isFile && it.name.startsWith("cover", ignoreCase = true)
                    }
                    val fileNameWithoutExt = file.nameWithoutExtension

                    // Try extracting artist and title cleanly
                    val parentFolder = file.parentFile
                    val grandParentFolder = parentFolder?.parentFile
                    val isOrganizedTree = grandParentFolder != null &&
                            grandParentFolder.absolutePath != baseDir.parentFile?.absolutePath &&
                            grandParentFolder.name != baseDir.name &&
                            parentFolder.name != baseDir.name

                    var fallbackArtist = if (isOrganizedTree) grandParentFolder!!.name else "Unknown Artist"
                    var fallbackTitle = fileNameWithoutExt

                    val trackNumberPrefixRegex = Regex("""^\d{1,3}\s*-\s*(.+)""")
                    val match = trackNumberPrefixRegex.find(fileNameWithoutExt)
                    if (match != null) {
                        fallbackTitle = match.groupValues[1]
                    } else if (fileNameWithoutExt.contains(" - ")) {
                        val pieces = fileNameWithoutExt.split(" - ")
                        if (pieces.size >= 2) {
                            if (!isOrganizedTree) {
                                fallbackArtist = pieces[0].trim()
                            }
                            fallbackTitle = pieces.drop(1).joinToString(" - ").trim()
                        }
                    }

                    // Try MediaMetadataRetriever for exact embedded tags
                    var tagTitle: String? = null
                    var tagArtist: String? = null
                    try {
                        val retriever = android.media.MediaMetadataRetriever()
                        retriever.setDataSource(file.absolutePath)
                        tagTitle = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_TITLE)
                        tagArtist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        retriever.release()
                    } catch (_: Exception) {}

                    val finalTitle = tagTitle?.takeIf { it.isNotBlank() } ?: fallbackTitle
                    val finalArtist = tagArtist?.takeIf { it.isNotBlank() } ?: fallbackArtist

                    results.add(
                        RecentDownload(
                            title = finalTitle,
                            artist = finalArtist,
                            filePath = file.absolutePath,
                            coverFile = cover,
                            format = file.extension.uppercase(),
                            lastModified = file.lastModified()
                        )
                    )
                }
            }

            results.sortByDescending { it.lastModified }
            _recentDownloads.value = results.take(10)
        }
    }
}

