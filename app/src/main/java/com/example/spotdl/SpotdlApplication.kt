package com.example.spotdl

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SpotdlApplication : Application() {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val TAG = "SpotdlApp"
        const val CHANNEL_DOWNLOAD_PROGRESS = "channel_download_progress"
        const val CHANNEL_DOWNLOAD_COMPLETE = "channel_download_complete"
        lateinit var instance: SpotdlApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        createNotificationChannels()
        initYoutubeDLEngine()
    }

    private fun initYoutubeDLEngine() {
        applicationScope.launch {
            try {
                Log.d(TAG, "Initializing YoutubeDL and FFmpeg runtimes...")
                YoutubeDL.getInstance().init(applicationContext)
                FFmpeg.getInstance().init(applicationContext)
                Log.d(TAG, "YoutubeDL and FFmpeg successfully initialized!")

                try {
                    Log.d(TAG, "Checking for yt-dlp binary updates...")
                    val updateStatus = YoutubeDL.getInstance().updateYoutubeDL(applicationContext)
                    Log.d(TAG, "yt-dlp update status: $updateStatus")
                } catch (e: Exception) {
                    Log.w(TAG, "yt-dlp auto-update check skipped/failed: ${e.message}")
                }
            } catch (e: YoutubeDLException) {
                Log.e(TAG, "Failed to initialize YoutubeDL engine", e)
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during YoutubeDL initialization", e)
            }
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            val progressChannel = NotificationChannel(
                CHANNEL_DOWNLOAD_PROGRESS,
                "Download Progress",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows live progress, transfer speed, and status of active audio downloads"
                setShowBadge(false)
            }

            val completeChannel = NotificationChannel(
                CHANNEL_DOWNLOAD_COMPLETE,
                "Download Completed",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when songs, albums, or playlists finish downloading"
                setShowBadge(true)
            }

            notificationManager.createNotificationChannel(progressChannel)
            notificationManager.createNotificationChannel(completeChannel)
        }
    }
}

