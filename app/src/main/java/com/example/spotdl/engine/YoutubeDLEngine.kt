package com.example.spotdl.engine

import android.content.Context
import android.util.Log
import com.example.spotdl.model.AudioFormatPreference
import com.example.spotdl.model.CollectionType
import com.example.spotdl.model.TrackMetadata
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class ProgressTelemetry(
    val percent: Float,
    val speedBytesPerSec: Long,
    val downloadedBytes: Long,
    val totalBytes: Long,
    val etaSeconds: Long,
    val statusText: String,
    val coverUrl: String? = null
)

object YoutubeDLEngine {
    private const val TAG = "YoutubeDLEngine"

    // Regex to parse yt-dlp stdout progress lines:
    // e.g.: [download]  42.5% of ~   3.07MiB at    3.40MiB/s ETA 00:03
    // e.g.: [download]  100% of 3.07MiB in 00:00:01 at 2.71MiB/s
    private val PROGRESS_PATTERN = Pattern.compile(
        "\\[download\\]\\s+([0-9.]+)%\\s+of\\s+~?\\s*([0-9.]+)\\s*([kKMmGg]i?B)(?:\\s+at\\s+([0-9.]+)\\s*([kKMmGg]i?B/s))?(?:\\s+ETA\\s+([0-9:]+))?"
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    suspend fun downloadTrack(
        context: Context,
        track: TrackMetadata,
        collectionTitle: String?,
        collectionType: CollectionType,
        formatPref: AudioFormatPreference = AudioFormatPreference.BEST_LOSSLESS,
        onProgress: (ProgressTelemetry) -> Unit
    ): File = withContext(Dispatchers.IO) {
        val cleanArtist = LrclibClient.cleanQueryTerm(track.artist)
        val cleanTitle = LrclibClient.cleanQueryTerm(track.title)

        // 1. Initial State: Indeterminate resolving (0% progress, zero fake bytes)
        onProgress(
            ProgressTelemetry(
                percent = 0f,
                speedBytesPerSec = 0,
                downloadedBytes = 0,
                totalBytes = 0,
                etaSeconds = 0,
                statusText = "Resolving canonical metadata & lyrics..."
            )
        )

        // Enrich canonical metadata via iTunes / YouTube Music
        val enrichedMeta = try {
            CanonicalMetadataEnricher.enrichWithItunes(track)
        } catch (e: Exception) {
            Log.w(TAG, "Metadata enrichment fallback to Spotify metadata: ${e.message}")
            track
        }

        // Fetch synchronized lyrics
        val lyrics = try {
            LrclibClient.fetchLyrics(
                trackName = enrichedMeta.title,
                artistName = enrichedMeta.artist,
                albumName = enrichedMeta.album,
                durationSeconds = enrichedMeta.durationSeconds
            )
        } catch (e: Exception) {
            Log.w(TAG, "Lyrics fetch skipped: ${e.message}")
            null
        }

        // Temporary workspace for downloading & remuxing
        val tempDir = File(context.cacheDir, "spotdl_dl_${System.currentTimeMillis()}")
        tempDir.mkdirs()

        // 2. Download square artwork if available
        var tempCoverFile: File? = null
        val coverUrl = enrichedMeta.coverUrl ?: track.coverUrl
        if (!coverUrl.isNullOrBlank()) {
            try {
                val coverReq = Request.Builder().url(coverUrl).build()
                httpClient.newCall(coverReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.bytes()
                        if (body != null && body.size > 500) {
                            val squareJpeg = MetadataBlockPictureHelper.ensureSquareJpeg(body)
                            tempCoverFile = File(tempDir, "cover.jpg")
                            FileOutputStream(tempCoverFile!!).use { it.write(squareJpeg) }
                            Log.d(TAG, "Cover art downloaded successfully: ${tempCoverFile!!.length()} bytes")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Artwork download skipped: ${e.message}")
            }
        }

        // 3. Search query: target official YouTube Music studio release
        val primaryQuery = "ytsearch1:$cleanArtist - $cleanTitle Topic"
        val tempRawOutputFile = File(tempDir, "raw_stream.%(ext)s")

        Log.d(TAG, "Primary stream query: $primaryQuery (Format: ${formatPref.ytDlpFormat})")

        val activeCoverUrl = enrichedMeta.coverUrl ?: track.coverUrl

        onProgress(
            ProgressTelemetry(
                percent = 0f,
                speedBytesPerSec = 0,
                downloadedBytes = 0,
                totalBytes = 0,
                etaSeconds = 0,
                statusText = "Locating studio master stream...",
                coverUrl = activeCoverUrl
            )
        )

        // 4. Download raw stream via yt-dlp (No transcoding, direct stream download)
        val request = YoutubeDLRequest(primaryQuery).apply {
            addOption("-f", formatPref.ytDlpFormat)
            addOption("--no-mtime")
            addOption("--no-update")
            addOption("--socket-timeout", "30")
            addOption("--retries", "10")
            addOption("--fragment-retries", "10")
            addOption("-o", tempRawOutputFile.absolutePath)
        }

        try {
            YoutubeDL.getInstance().execute(request) { progress, etaInSeconds, line ->
                val telemetry = parseProgressLine(progress, etaInSeconds, line, activeCoverUrl)
                onProgress(telemetry)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Primary search failed, attempting fallback query: $cleanArtist $cleanTitle Audio", e)
            val fallbackQuery = "ytsearch1:$cleanArtist $cleanTitle Audio"
            val fallbackRequest = YoutubeDLRequest(fallbackQuery).apply {
                addOption("-f", formatPref.ytDlpFormat)
                addOption("--no-mtime")
                addOption("--no-update")
                addOption("--socket-timeout", "30")
                addOption("--retries", "10")
                addOption("--fragment-retries", "10")
                addOption("-o", tempRawOutputFile.absolutePath)
            }
            YoutubeDL.getInstance().execute(fallbackRequest) { progress, etaInSeconds, line ->
                val telemetry = parseProgressLine(progress, etaInSeconds, line, activeCoverUrl)
                onProgress(telemetry)
            }
        }

        // 5. Locate downloaded raw stream file in tempDir
        val rawDownloadedFile = tempDir.listFiles()?.firstOrNull {
            it.isFile && it.name.startsWith("raw_stream") && !it.name.endsWith(".part") && !it.name.endsWith(".ytdl")
        } ?: throw IllegalStateException("Could not find downloaded audio file in ${tempDir.absolutePath}")

        val rawSize = rawDownloadedFile.length()
        Log.d(TAG, "Raw stream downloaded: ${rawDownloadedFile.name} ($rawSize bytes)")

        onProgress(
            ProgressTelemetry(
                percent = 100f,
                speedBytesPerSec = 0,
                downloadedBytes = rawSize,
                totalBytes = rawSize,
                etaSeconds = 0,
                statusText = "Losslessly remuxing & embedding canonical metadata..."
            )
        )

        // 6. Determine native target container (.opus or .m4a or .mp3)
        val rawExt = rawDownloadedFile.extension.lowercase()
        val targetExt = when (formatPref) {
            AudioFormatPreference.BEST_LOSSLESS -> if (rawExt == "webm") "opus" else if (rawExt == "m4a") "m4a" else "opus"
            AudioFormatPreference.OPUS -> "opus"
            AudioFormatPreference.AAC -> "m4a"
            AudioFormatPreference.MP3_320 -> "mp3"
        }

        // Remux and inject canonical metadata + cover art + lyrics
        val tempRemuxedFile = File(tempDir, "tagged_audio.$targetExt")
        FFmpegEngine.remuxAndTag(
            context = context,
            rawInputFile = rawDownloadedFile,
            targetOutputFile = tempRemuxedFile,
            metadata = enrichedMeta,
            coverFile = tempCoverFile,
            syncedLyrics = lyrics?.syncedLyrics
        )

        // 7. Compute destination in Android Music directory
        val (finalAudio, finalLrc, finalCover) = MediaStoreHelper.computeFileDestinations(
            context = context,
            meta = enrichedMeta,
            collectionTitle = collectionTitle,
            collectionType = collectionType,
            extension = targetExt
        )

        // Move remuxed audio to destination via Scoped Storage safe copy
        val mimeType = when (targetExt) {
            "opus" -> "audio/opus"
            "m4a" -> "audio/mp4"
            "mp3" -> "audio/mpeg"
            else -> "audio/*"
        }
        MediaStoreHelper.safeCopyTo(context, tempRemuxedFile, finalAudio, mimeType)

        // 8. Save companion cover.jpg in album directory for music players (Samsung Music, etc.)
        if (tempCoverFile != null && tempCoverFile!!.exists()) {
            if (!finalCover.exists()) {
                MediaStoreHelper.safeCopyTo(context, tempCoverFile!!, finalCover, "image/jpeg")
                MediaStoreHelper.notifyMediaScanner(context, finalCover.absolutePath)
                Log.d(TAG, "Saved companion cover.jpg to: ${finalCover.absolutePath}")
            }
        }

        // 9. Save companion .lrc file if synced lyrics exist
        if (lyrics != null && lyrics.hasSynced) {
            LrclibClient.saveLrcFile(lyrics.syncedLyrics!!, finalLrc)
            MediaStoreHelper.notifyMediaScanner(context, finalLrc.absolutePath)
            Log.d(TAG, "Saved companion .lrc to: ${finalLrc.absolutePath}")
        }

        // 10. Clean up temp workspace
        tempDir.deleteRecursively()

        // 11. Notify MediaScanner so Android audio players immediately index the new track
        MediaStoreHelper.notifyMediaScanner(context, finalAudio.absolutePath)

        val finalLength = finalAudio.length()
        onProgress(
            ProgressTelemetry(
                percent = 100f,
                speedBytesPerSec = 0,
                downloadedBytes = finalLength,
                totalBytes = finalLength,
                etaSeconds = 0,
                statusText = "Completed"
            )
        )

        Log.d(TAG, "Download and packaging completed successfully: ${finalAudio.absolutePath} ($finalLength bytes)")
        return@withContext finalAudio
    }

    private fun parseProgressLine(progress: Float, etaInSeconds: Long, line: String?, coverUrl: String? = null): ProgressTelemetry {
        var pct = progress
        var speedBytes: Long = 0
        var totalBytes: Long = 0
        var downloadedBytes: Long = 0
        var eta = etaInSeconds

        if (line != null) {
            val matcher = PROGRESS_PATTERN.matcher(line)
            if (matcher.find()) {
                val pctStr = matcher.group(1)
                val totalStr = matcher.group(2)
                val totalUnit = matcher.group(3)
                val speedStr = matcher.group(4)
                val speedUnit = matcher.group(5)
                val etaStr = matcher.group(6)

                pct = pctStr?.toFloatOrNull() ?: pct
                totalBytes = parseUnitToBytes(totalStr, totalUnit)
                speedBytes = parseSpeedToBytes(speedStr, speedUnit)
                downloadedBytes = (totalBytes * (pct / 100f)).toLong()

                if (etaStr != null) {
                    eta = parseEtaToSeconds(etaStr)
                }
            }
        }

        return ProgressTelemetry(
            percent = pct.coerceIn(0f, 100f),
            speedBytesPerSec = speedBytes,
            downloadedBytes = downloadedBytes,
            totalBytes = totalBytes,
            etaSeconds = eta,
            statusText = if (pct >= 100f) "Finishing download..." else "Downloading audio bitstream...",
            coverUrl = coverUrl
        )
    }

    private fun parseUnitToBytes(amountStr: String?, unitStr: String?): Long {
        if (amountStr == null || unitStr == null) return 0
        val amount = amountStr.toDoubleOrNull() ?: return 0
        val u = unitStr.uppercase()
        return when {
            u.startsWith("G") -> (amount * 1024 * 1024 * 1024).toLong()
            u.startsWith("M") -> (amount * 1024 * 1024).toLong()
            u.startsWith("K") -> (amount * 1024).toLong()
            else -> amount.toLong()
        }
    }

    private fun parseSpeedToBytes(speedStr: String?, unitStr: String?): Long {
        if (speedStr == null || unitStr == null) return 0
        val amount = speedStr.toDoubleOrNull() ?: return 0
        val u = unitStr.uppercase()
        return when {
            u.startsWith("G") -> (amount * 1024 * 1024 * 1024).toLong()
            u.startsWith("M") -> (amount * 1024 * 1024).toLong()
            u.startsWith("K") -> (amount * 1024).toLong()
            else -> amount.toLong()
        }
    }

    private fun parseEtaToSeconds(etaStr: String): Long {
        val parts = etaStr.split(":")
        return when (parts.size) {
            2 -> (parts[0].toLongOrNull() ?: 0) * 60 + (parts[1].toLongOrNull() ?: 0)
            3 -> (parts[0].toLongOrNull() ?: 0) * 3600 + (parts[1].toLongOrNull() ?: 0) * 60 + (parts[2].toLongOrNull() ?: 0)
            else -> 0
        }
    }
}
