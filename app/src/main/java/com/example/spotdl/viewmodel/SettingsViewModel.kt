package com.example.spotdl.viewmodel

import android.app.Application
import android.os.StatFs
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.spotdl.engine.MediaStoreHelper
import com.example.spotdl.engine.PingResult
import com.example.spotdl.engine.RemoteServerClient
import com.example.spotdl.model.AudioFormatPreference
import com.example.spotdl.queue.DownloadQueueManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

data class StorageMetrics(
    val vaultBytes: Long = 0,
    val cacheBytes: Long = 0,
    val freeBytes: Long = 0,
    val totalBytes: Long = 0,
    val trackCount: Int = 0
)

class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val queueManager = DownloadQueueManager.getInstance(application)

    private val _storageMetrics = MutableStateFlow(StorageMetrics())
    val storageMetrics: StateFlow<StorageMetrics> = _storageMetrics.asStateFlow()

    private val _formatPref = MutableStateFlow(queueManager.formatPreference)
    val formatPref: StateFlow<AudioFormatPreference> = _formatPref.asStateFlow()

    private val _wifiOnly = MutableStateFlow(false)
    val wifiOnly: StateFlow<Boolean> = _wifiOnly.asStateFlow()

    private val _autoOrganize = MutableStateFlow(true)
    val autoOrganize: StateFlow<Boolean> = _autoOrganize.asStateFlow()

    private val _useRemoteServer = MutableStateFlow(queueManager.useRemoteServer)
    val useRemoteServer: StateFlow<Boolean> = _useRemoteServer.asStateFlow()

    private val _remoteServerUrl = MutableStateFlow(queueManager.remoteServerUrl)
    val remoteServerUrl: StateFlow<String> = _remoteServerUrl.asStateFlow()

    private val _pingResult = MutableStateFlow<PingResult?>(null)
    val pingResult: StateFlow<PingResult?> = _pingResult.asStateFlow()

    private val _isPinging = MutableStateFlow(false)
    val isPinging: StateFlow<Boolean> = _isPinging.asStateFlow()

    init {
        refreshStorageMetrics()
    }

    fun refreshStorageMetrics() {
        viewModelScope.launch(Dispatchers.IO) {
            val baseDir = MediaStoreHelper.getDownloadBaseDir(getApplication())
            val cacheDir = getApplication<Application>().cacheDir

            var vaultBytes = 0L
            var trackCount = 0
            baseDir.walkTopDown().forEach { f ->
                if (f.isFile) {
                    vaultBytes += f.length()
                    if (listOf("opus", "m4a", "mp3", "flac").contains(f.extension.lowercase())) {
                        trackCount++
                    }
                }
            }

            var cacheBytes = 0L
            cacheDir.walkTopDown().forEach { if (it.isFile) cacheBytes += it.length() }

            val stat = StatFs(baseDir.path)
            val freeBytes = stat.availableBytes
            val totalBytes = stat.totalBytes

            _storageMetrics.value = StorageMetrics(
                vaultBytes = vaultBytes,
                cacheBytes = cacheBytes,
                freeBytes = freeBytes,
                totalBytes = totalBytes,
                trackCount = trackCount
            )
        }
    }

    fun pruneCache() {
        viewModelScope.launch(Dispatchers.IO) {
            val cacheDir = getApplication<Application>().cacheDir
            cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            refreshStorageMetrics()
        }
    }

    fun setFormatPreference(pref: AudioFormatPreference) {
        _formatPref.value = pref
        queueManager.formatPreference = pref
    }

    fun toggleWifiOnly() {
        _wifiOnly.value = !_wifiOnly.value
    }

    fun toggleAutoOrganize() {
        _autoOrganize.value = !_autoOrganize.value
    }

    fun toggleRemoteServer() {
        val newVal = !_useRemoteServer.value
        _useRemoteServer.value = newVal
        queueManager.useRemoteServer = newVal
    }

    fun setRemoteServerUrl(url: String) {
        _remoteServerUrl.value = url
        queueManager.remoteServerUrl = url
        _pingResult.value = null
    }

    fun pingServer() {
        viewModelScope.launch(Dispatchers.IO) {
            _isPinging.value = true
            _pingResult.value = null
            val result = RemoteServerClient.ping(_remoteServerUrl.value)
            _pingResult.value = result
            _isPinging.value = false
        }
    }
}

