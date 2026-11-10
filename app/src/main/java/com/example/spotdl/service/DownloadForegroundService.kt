package com.example.spotdl.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.spotdl.MainActivity
import com.example.spotdl.R
import com.example.spotdl.SpotdlApplication
import com.example.spotdl.queue.DownloadQueueManager

class DownloadForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    companion object {
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "action_start"
        const val ACTION_UPDATE = "action_update"
        const val ACTION_STOP = "action_stop"
        const val ACTION_PAUSE = "action_pause"
        const val ACTION_CANCEL = "action_cancel"

        const val EXTRA_TITLE = "extra_title"
        const val EXTRA_ARTIST = "extra_artist"
        const val EXTRA_PROGRESS = "extra_progress"
        const val EXTRA_SPEED = "extra_speed"

        fun start(
            context: Context,
            trackTitle: String,
            artistName: String,
            progressPercent: Float,
            speedBytesPerSec: Long
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_TITLE, trackTitle)
                putExtra(EXTRA_ARTIST, artistName)
                putExtra(EXTRA_PROGRESS, progressPercent)
                putExtra(EXTRA_SPEED, speedBytesPerSec)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun updateProgress(
            context: Context,
            trackTitle: String,
            artistName: String,
            progressPercent: Float,
            speedBytesPerSec: Long
        ) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_UPDATE
                putExtra(EXTRA_TITLE, trackTitle)
                putExtra(EXTRA_ARTIST, artistName)
                putExtra(EXTRA_PROGRESS, progressPercent)
                putExtra(EXTRA_SPEED, speedBytesPerSec)
            }
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, DownloadForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        acquireLocks()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY

        when (action) {
            ACTION_START, ACTION_UPDATE -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "Downloading music"
                val artist = intent.getStringExtra(EXTRA_ARTIST) ?: "spotdl"
                val progress = intent.getFloatExtra(EXTRA_PROGRESS, 0f)
                val speedBytes = intent.getLongExtra(EXTRA_SPEED, 0L)

                val notification = buildNotification(title, artist, progress, speedBytes)
                startForeground(NOTIFICATION_ID, notification)
            }
            ACTION_PAUSE -> {
                DownloadQueueManager.getInstance(this).pauseAll()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_CANCEL -> {
                DownloadQueueManager.getInstance(this).cancelQueue()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun buildNotification(
        title: String,
        artist: String,
        progress: Float,
        speedBytes: Long
    ): Notification {
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentPendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val pauseIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_PAUSE
        }
        val pausePendingIntent = PendingIntent.getService(
            this,
            1,
            pauseIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = Intent(this, DownloadForegroundService::class.java).apply {
            action = ACTION_CANCEL
        }
        val cancelPendingIntent = PendingIntent.getService(
            this,
            2,
            cancelIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val speedText = formatSpeed(speedBytes)
        val progressInt = progress.toInt().coerceIn(0, 100)

        return NotificationCompat.Builder(this, SpotdlApplication.CHANNEL_DOWNLOAD_PROGRESS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText("$artist · $speedText")
            .setProgress(100, progressInt, progressInt == 0)
            .setContentIntent(contentPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Pause", pausePendingIntent)
            .addAction(0, "Cancel", cancelPendingIntent)
            .build()
    }

    private fun formatSpeed(bytesPerSec: Long): String {
        if (bytesPerSec <= 0) return "Connecting..."
        val mb = bytesPerSec / (1024.0 * 1024.0)
        return if (mb >= 1.0) {
            String.format("%.1f MB/s", mb)
        } else {
            val kb = bytesPerSec / 1024.0
            String.format("%.0f KB/s", kb)
        }
    }

    private fun acquireLocks() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Spotdl:DownloadWakeLock").apply {
                acquire(2 * 60 * 60 * 1000L) // 2 hours max safety timeout
            }

            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Spotdl:DownloadWifiLock").apply {
                acquire()
            }
        } catch (e: Exception) {
            // Lock failure non-fatal
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        } catch (ignored: Exception) {}
    }
}
