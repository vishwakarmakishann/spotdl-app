package com.example.spotdl.engine

import android.content.Context
import android.util.Base64
import android.util.Log
import com.example.spotdl.model.AudioFormatPreference
import com.example.spotdl.model.CollectionType
import com.example.spotdl.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

sealed class PingResult {
    data class Success(val latencyMs: Long, val version: String) : PingResult()
    data class Error(val message: String) : PingResult()
}

object RemoteServerClient {
    private const val TAG = "RemoteServerClient"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS) // Allow time for server to resolve and package
        .build()

    suspend fun ping(serverUrl: String): PingResult = withContext(Dispatchers.IO) {
        val cleanUrl = sanitizeUrl(serverUrl)
        val healthUrl = "$cleanUrl/health"

        val startTime = System.currentTimeMillis()
        try {
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val elapsed = System.currentTimeMillis() - startTime
                if (response.isSuccessful) {
                    val bodyStr = response.body?.string() ?: ""
                    val version = if (bodyStr.contains("\"version\"")) {
                        try {
                            org.json.JSONObject(bodyStr).optString("version", "1.0.0")
                        } catch (_: Exception) {
                            "1.0.0"
                        }
                    } else {
                        "1.0.0"
                    }
                    PingResult.Success(latencyMs = elapsed, version = version)
                } else {
                    PingResult.Error("HTTP ${response.code}: ${response.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ping failed for $healthUrl", e)
            PingResult.Error(e.message ?: "Connection failed")
        }
    }

    suspend fun downloadTrack(
        context: Context,
        serverUrl: String,
        track: TrackMetadata,
        collectionTitle: String?,
        collectionType: CollectionType,
        formatPref: AudioFormatPreference = AudioFormatPreference.BEST_LOSSLESS,
        onProgress: (ProgressTelemetry) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cleanUrl = sanitizeUrl(serverUrl)
        val downloadEndpoint = "$cleanUrl/api/download"

        val targetFormat = when (formatPref) {
            AudioFormatPreference.BEST_LOSSLESS -> "opus"
            AudioFormatPreference.OPUS -> "opus"
            AudioFormatPreference.AAC -> "m4a"
            AudioFormatPreference.MP3_320 -> "mp3"
        }

        val urlBuilder = downloadEndpoint.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid server URL: $serverUrl")

        urlBuilder.addQueryParameter("title", track.title)
        urlBuilder.addQueryParameter("artist", track.artist)
        if (!track.album.isNullOrBlank()) urlBuilder.addQueryParameter("album", track.album)
        if (!track.year.isNullOrBlank()) urlBuilder.addQueryParameter("year", track.year)
        if (track.trackNumber != null) urlBuilder.addQueryParameter("track_number", track.trackNumber.toString())
        if (!track.coverUrl.isNullOrBlank()) urlBuilder.addQueryParameter("cover_url", track.coverUrl)
        urlBuilder.addQueryParameter("format", targetFormat)

        val fullUrl = urlBuilder.build()
        Log.d(TAG, "Requesting audio stream from server: $fullUrl")

        onProgress(
            ProgressTelemetry(
                percent = 0f,
                speedBytesPerSec = 0,
                downloadedBytes = 0,
                totalBytes = 0,
                etaSeconds = 0,
                statusText = "Server processing & packaging..."
            )
        )

        val request = Request.Builder()
            .url(fullUrl)
            .get()
            .build()

        val tempDir = File(context.cacheDir, "spotdl_remote_${System.currentTimeMillis()}")
        tempDir.mkdirs()
        val tempAudioFile = File(tempDir, "temp_stream.$targetFormat")

        var syncedLyrics: String? = null

        val call = httpClient.newCall(request)
        call.execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: response.message
                throw RuntimeException("Server error HTTP ${response.code}: $err")
            }

            // Extract custom response headers
            val lyricsHeader = response.header("X-Synced-Lyrics")
            if (!lyricsHeader.isNullOrBlank()) {
                try {
                    val decodedBytes = Base64.decode(lyricsHeader, Base64.DEFAULT)
                    syncedLyrics = String(decodedBytes, Charsets.UTF_8)
                } catch (_: Exception) {}
            }

            val body = response.body ?: throw RuntimeException("Empty response body from server")
            val totalBytes = body.contentLength()

            val inputStream = body.byteStream()
            val outputStream = FileOutputStream(tempAudioFile)

            val buffer = ByteArray(8 * 1024)
            var bytesRead: Int
            var totalRead = 0L
            val startTime = System.currentTimeMillis()

            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
                totalRead += bytesRead

                val elapsedSec = (System.currentTimeMillis() - startTime) / 1000f
                val speed = if (elapsedSec > 0) (totalRead / elapsedSec).toLong() else 0L
                val pct = if (totalBytes > 0) (totalRead.toFloat() / totalBytes.toFloat() * 100f).coerceIn(0f, 99f) else 0f
                val eta = if (speed > 0 && totalBytes > totalRead) ((totalBytes - totalRead) / speed) else 0L

                onProgress(
                    ProgressTelemetry(
                        percent = pct,
                        speedBytesPerSec = speed,
                        downloadedBytes = totalRead,
                        totalBytes = if (totalBytes > 0) totalBytes else totalRead,
                        etaSeconds = eta,
                        statusText = "Streaming from server..."
                    )
                )
            }
            outputStream.flush()
            outputStream.close()
            inputStream.close()
        }

        // Compute destination in Android Music directory
        val (finalAudio, finalLrc, finalCover) = MediaStoreHelper.computeFileDestinations(
            context = context,
            meta = track,
            collectionTitle = collectionTitle,
            collectionType = collectionType,
            extension = targetFormat
        )

        val mimeType = when (targetFormat) {
            "opus" -> "audio/opus"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> "audio/*"
        }
        MediaStoreHelper.safeCopyTo(context, tempAudioFile, finalAudio, mimeType)

        // Save companion artwork if available
        if (!track.coverUrl.isNullOrBlank() && !finalCover.exists()) {
            try {
                val coverReq = Request.Builder().url(track.coverUrl).build()
                httpClient.newCall(coverReq).execute().use { cResp ->
                    if (cResp.isSuccessful) {
                        val cBytes = cResp.body?.bytes()
                        if (cBytes != null) {
                            val square = MetadataBlockPictureHelper.ensureSquareJpeg(cBytes)
                            val tmpCover = File(tempDir, "cover.jpg")
                            FileOutputStream(tmpCover).use { it.write(square) }
                            MediaStoreHelper.safeCopyTo(context, tmpCover, finalCover, "image/jpeg")
                            MediaStoreHelper.notifyMediaScanner(context, finalCover.absolutePath)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save companion cover art: ${e.message}")
            }
        }

        // Save companion .lrc if lyrics exist
        if (!syncedLyrics.isNullOrBlank()) {
            LrclibClient.saveLrcFile(syncedLyrics!!, finalLrc)
            MediaStoreHelper.notifyMediaScanner(context, finalLrc.absolutePath)
        }

        // Clean up temp
        tempDir.deleteRecursively()

        // Notify MediaScanner for music players
        MediaStoreHelper.notifyMediaScanner(context, finalAudio.absolutePath)

        onProgress(
            ProgressTelemetry(
                percent = 100f,
                speedBytesPerSec = 0,
                downloadedBytes = finalAudio.length(),
                totalBytes = finalAudio.length(),
                etaSeconds = 0,
                statusText = "Completed"
            )
        )

        Log.d(TAG, "Server download complete: ${finalAudio.absolutePath} (${finalAudio.length()} bytes)")
        return@withContext finalAudio
    }

    private fun sanitizeUrl(url: String): String {
        var clean = url.trim()
        while (clean.endsWith("/")) {
            clean = clean.substring(0, clean.length - 1)
        }
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
            clean = "https://$clean"
        }
        return clean
    }
}

