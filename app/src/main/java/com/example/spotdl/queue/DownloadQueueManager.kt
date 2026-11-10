package com.example.spotdl.queue

import android.content.Context
import android.util.Log
import com.example.spotdl.engine.RemoteServerClient
import com.example.spotdl.engine.YoutubeDLEngine
import com.example.spotdl.model.AudioFormatPreference
import com.example.spotdl.model.CollectionMetadata
import com.example.spotdl.model.CollectionType
import com.example.spotdl.model.DownloadItem
import com.example.spotdl.model.DownloadStatus
import com.example.spotdl.model.TrackMetadata
import com.example.spotdl.service.DownloadForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

data class BatchProgressState(
    val totalCount: Int = 0,
    val completedCount: Int = 0,
    val failedCount: Int = 0,
    val activeCount: Int = 0,
    val waitingCount: Int = 0,
    val overallPercent: Float = 0f,
    val currentSpeedBytesPerSec: Long = 0,
    val totalBytesDownloaded: Long = 0,
    val totalBytesEstimated: Long = 0,
    val etaSeconds: Long = 0,
    val isPaused: Boolean = false,
    val collectionTitle: String? = null
)

class DownloadQueueManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var workerJob: Job? = null

    private val _queue = MutableStateFlow<List<DownloadItem>>(emptyList())
    val queue: StateFlow<List<DownloadItem>> = _queue.asStateFlow()

    private val _batchState = MutableStateFlow(BatchProgressState())
    val batchState: StateFlow<BatchProgressState> = _batchState.asStateFlow()

    private val _activeItem = MutableStateFlow<DownloadItem?>(null)
    val activeItem: StateFlow<DownloadItem?> = _activeItem.asStateFlow()

    var formatPreference: AudioFormatPreference = AudioFormatPreference.BEST_LOSSLESS
    var isPaused: Boolean = false
        private set

    private val prefs = appContext.getSharedPreferences("spotdl_settings", Context.MODE_PRIVATE)

    var useRemoteServer: Boolean
        get() = prefs.getBoolean("use_remote_server", false)
        set(value) = prefs.edit().putBoolean("use_remote_server", value).apply()

    var remoteServerUrl: String
        get() = prefs.getString("remote_server_url", "https://spotdl-backend.onrender.com") ?: "https://spotdl-backend.onrender.com"
        set(value) = prefs.edit().putString("remote_server_url", value).apply()

    companion object {
        private const val TAG = "QueueManager"

        @Volatile
        private var INSTANCE: DownloadQueueManager? = null

        fun getInstance(context: Context): DownloadQueueManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DownloadQueueManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    fun enqueue(collection: CollectionMetadata, selectedIndices: Set<Int>? = null) {
        val itemsToAdd = mutableListOf<DownloadItem>()
        collection.tracks.forEachIndexed { index, track ->
            if (selectedIndices == null || selectedIndices.contains(index)) {
                itemsToAdd.add(
                    DownloadItem(
                        id = UUID.randomUUID().toString(),
                        trackMetadata = track,
                        collectionTitle = collection.title,
                        collectionType = collection.type,
                        status = DownloadStatus.QUEUED
                    )
                )
            }
        }

        _queue.update { current -> current + itemsToAdd }
        updateBatchState(collectionTitle = collection.title)
        triggerWorker()
    }

    fun enqueueSingle(track: TrackMetadata) {
        val item = DownloadItem(
            id = UUID.randomUUID().toString(),
            trackMetadata = track,
            collectionTitle = track.album,
            collectionType = CollectionType.TRACK,
            status = DownloadStatus.QUEUED
        )
        _queue.update { current -> current + item }
        updateBatchState()
        triggerWorker()
    }

    fun pauseAll() {
        isPaused = true
        _batchState.update { it.copy(isPaused = true) }
        workerJob?.cancel()
        _activeItem.value?.let { active ->
            updateItemStatus(active.id, DownloadStatus.PAUSED)
        }
        _activeItem.value = null
        DownloadForegroundService.stop(appContext)
    }

    fun resumeAll() {
        isPaused = false
        _batchState.update { it.copy(isPaused = false) }
        _queue.update { list ->
            list.map {
                if (it.status == DownloadStatus.PAUSED) it.copy(status = DownloadStatus.QUEUED) else it
            }
        }
        triggerWorker()
    }

    fun retryFailed() {
        _queue.update { list ->
            list.map {
                if (it.status == DownloadStatus.FAILED) it.copy(status = DownloadStatus.QUEUED, errorMessage = null) else it
            }
        }
        updateBatchState()
        triggerWorker()
    }

    fun retryTrack(id: String) {
        _queue.update { list ->
            list.map {
                if (it.id == id) it.copy(status = DownloadStatus.QUEUED, errorMessage = null) else it
            }
        }
        updateBatchState()
        triggerWorker()
    }

    fun clearCompleted() {
        _queue.update { list ->
            list.filter { it.status != DownloadStatus.COMPLETED }
        }
        updateBatchState()
    }

    fun cancelQueue() {
        workerJob?.cancel()
        _queue.value = emptyList()
        _activeItem.value = null
        _batchState.value = BatchProgressState()
        DownloadForegroundService.stop(appContext)
    }

    fun cancelTrack(id: String) {
        if (_activeItem.value?.id == id) {
            workerJob?.cancel()
            _activeItem.value = null
            _queue.update { list -> list.filter { it.id != id } }
            triggerWorker()
        } else {
            _queue.update { list -> list.filter { it.id != id } }
        }
        updateBatchState()
    }

    private fun triggerWorker() {
        if (isPaused) return
        if (workerJob?.isActive == true) return

        workerJob = scope.launch {
            while (!isPaused) {
                val nextItem = _queue.value.firstOrNull { it.status == DownloadStatus.QUEUED }
                if (nextItem == null) {
                    _activeItem.value = null
                    updateBatchState()
                    DownloadForegroundService.stop(appContext)
                    break
                }

                _activeItem.value = nextItem
                updateItemStatus(nextItem.id, DownloadStatus.DOWNLOADING)
                DownloadForegroundService.start(
                    context = appContext,
                    trackTitle = nextItem.trackMetadata.title,
                    artistName = nextItem.trackMetadata.artist,
                    progressPercent = 0f,
                    speedBytesPerSec = 0
                )

                try {
                    val downloadedFile = if (useRemoteServer && remoteServerUrl.isNotBlank()) {
                        RemoteServerClient.downloadTrack(
                            context = appContext,
                            serverUrl = remoteServerUrl,
                            track = nextItem.trackMetadata,
                            collectionTitle = nextItem.collectionTitle,
                            collectionType = nextItem.collectionType,
                            formatPref = formatPreference
                        ) { telemetry ->
                            updateItemProgress(
                                id = nextItem.id,
                                percent = telemetry.percent,
                                speedBytes = telemetry.speedBytesPerSec,
                                downloadedBytes = telemetry.downloadedBytes,
                                totalBytes = telemetry.totalBytes,
                                etaSeconds = telemetry.etaSeconds
                            )
                            DownloadForegroundService.updateProgress(
                                context = appContext,
                                trackTitle = nextItem.trackMetadata.title,
                                artistName = nextItem.trackMetadata.artist,
                                progressPercent = telemetry.percent,
                                speedBytesPerSec = telemetry.speedBytesPerSec
                            )
                        }
                    } else {
                        YoutubeDLEngine.downloadTrack(
                            context = appContext,
                            track = nextItem.trackMetadata,
                            collectionTitle = nextItem.collectionTitle,
                            collectionType = nextItem.collectionType,
                            formatPref = formatPreference
                        ) { telemetry ->
                            updateItemProgress(
                                id = nextItem.id,
                                percent = telemetry.percent,
                                speedBytes = telemetry.speedBytesPerSec,
                                downloadedBytes = telemetry.downloadedBytes,
                                totalBytes = telemetry.totalBytes,
                                etaSeconds = telemetry.etaSeconds,
                                coverUrl = telemetry.coverUrl
                            )
                            DownloadForegroundService.updateProgress(
                                context = appContext,
                                trackTitle = nextItem.trackMetadata.title,
                                artistName = nextItem.trackMetadata.artist,
                                progressPercent = telemetry.percent,
                                speedBytesPerSec = telemetry.speedBytesPerSec
                            )
                        }
                    }

                    val companionCover = File(downloadedFile.parentFile, "cover.jpg")
                    val finalCoverPath = if (companionCover.exists()) companionCover.absolutePath else nextItem.trackMetadata.coverUrl

                    _queue.update { list ->
                        list.map {
                            if (it.id == nextItem.id) {
                                it.copy(
                                    status = DownloadStatus.COMPLETED,
                                    trackMetadata = it.trackMetadata.copy(coverUrl = finalCoverPath ?: it.trackMetadata.coverUrl),
                                    progressPercent = 100f,
                                    audioPath = downloadedFile.absolutePath,
                                    speedBytesPerSec = 0,
                                    etaSeconds = 0
                                )
                            } else it
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error downloading ${nextItem.trackMetadata.title}", e)
                    val friendlyError = if (useRemoteServer) {
                        if (e.message?.contains("404") == true) {
                            "Remote server 404 (Not Found). Deploy server or disable Remote Server in Settings to use on-device engine."
                        } else if (e is java.net.ConnectException || e is java.net.UnknownHostException) {
                            "Cannot connect to remote server ($remoteServerUrl). Check URL or disable in Settings."
                        } else {
                            "Remote server error: ${e.message}"
                        }
                    } else {
                        e.message ?: "Download failed"
                    }

                    _queue.update { list ->
                        list.map {
                            if (it.id == nextItem.id) {
                                it.copy(
                                    status = DownloadStatus.FAILED,
                                    errorMessage = friendlyError
                                )
                            } else it
                        }
                    }
                }

                updateBatchState()
            }
        }
    }

    private fun updateItemStatus(id: String, status: DownloadStatus) {
        _queue.update { list ->
            list.map { if (it.id == id) it.copy(status = status) else it }
        }
        updateBatchState()
    }

    private fun updateItemProgress(
        id: String,
        percent: Float,
        speedBytes: Long,
        downloadedBytes: Long,
        totalBytes: Long,
        etaSeconds: Long,
        coverUrl: String? = null
    ) {
        _queue.update { list ->
            list.map {
                if (it.id == id) {
                    val updatedMeta = if (!coverUrl.isNullOrBlank() && it.trackMetadata.coverUrl != coverUrl) {
                        it.trackMetadata.copy(coverUrl = coverUrl)
                    } else {
                        it.trackMetadata
                    }
                    it.copy(
                        trackMetadata = updatedMeta,
                        progressPercent = percent,
                        speedBytesPerSec = speedBytes,
                        downloadedBytes = downloadedBytes,
                        totalBytes = totalBytes,
                        etaSeconds = etaSeconds
                    )
                } else it
            }
        }
        _activeItem.value = _queue.value.firstOrNull { it.id == id }
        updateBatchState()
    }

    private fun updateBatchState(collectionTitle: String? = null) {
        val list = _queue.value
        val total = list.size
        val completed = list.count { it.status == DownloadStatus.COMPLETED }
        val failed = list.count { it.status == DownloadStatus.FAILED }
        val active = list.count { it.isActive }
        val waiting = list.count { it.status == DownloadStatus.QUEUED }

        val activeItem = _activeItem.value
        val speed = activeItem?.speedBytesPerSec ?: 0L
        val eta = activeItem?.etaSeconds ?: 0L

        val totalDownloadedBytes = list.sumOf { it.downloadedBytes }
        val totalEstimatedBytes = list.sumOf { it.totalBytes }

        val overallPercent = if (total > 0) {
            val sumOfProgress = list.sumOf { it.progressPercent.toDouble() }
            (sumOfProgress / total).toFloat()
        } else 0f

        _batchState.value = BatchProgressState(
            totalCount = total,
            completedCount = completed,
            failedCount = failed,
            activeCount = active,
            waitingCount = waiting,
            overallPercent = overallPercent,
            currentSpeedBytesPerSec = speed,
            totalBytesDownloaded = totalDownloadedBytes,
            totalBytesEstimated = totalEstimatedBytes,
            etaSeconds = eta,
            isPaused = isPaused,
            collectionTitle = collectionTitle ?: _batchState.value.collectionTitle
        )
    }
}

