package com.example.spotdl.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import com.example.spotdl.model.DownloadItem
import com.example.spotdl.queue.BatchProgressState
import com.example.spotdl.queue.DownloadQueueManager
import kotlinx.coroutines.flow.StateFlow

class QueueViewModel(application: Application) : AndroidViewModel(application) {

    private val queueManager = DownloadQueueManager.getInstance(application)

    val queue: StateFlow<List<DownloadItem>> = queueManager.queue
    val batchState: StateFlow<BatchProgressState> = queueManager.batchState
    val activeItem: StateFlow<DownloadItem?> = queueManager.activeItem

    fun togglePauseResume() {
        if (batchState.value.isPaused) {
            queueManager.resumeAll()
        } else {
            queueManager.pauseAll()
        }
    }

    fun pauseAll() = queueManager.pauseAll()
    fun resumeAll() = queueManager.resumeAll()
    fun retryFailed() = queueManager.retryFailed()
    fun clearCompleted() = queueManager.clearCompleted()
    fun cancelQueue() = queueManager.cancelQueue()
    fun cancelTrack(id: String) = queueManager.cancelTrack(id)
}

