package com.example.spotdl.engine

import android.content.Context
import android.util.Log
import com.example.spotdl.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

object FFmpegEngine {
    private const val TAG = "FFmpegEngine"

    suspend fun execute(context: Context, args: List<String>): Int = withContext(Dispatchers.IO) {
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        val ffmpegBin = File(nativeLibDir, "libffmpeg.so")
        if (!ffmpegBin.exists()) {
            throw IllegalStateException("libffmpeg.so not found at ${ffmpegBin.absolutePath}")
        }

        val noBackupDir = context.noBackupFilesDir
        val ffmpegUsrLib = File(noBackupDir, "youtubedl-android/packages/ffmpeg/usr/lib")
        val pythonUsrLib = File(noBackupDir, "youtubedl-android/packages/python/usr/lib")

        val ldLibraryPath = "${nativeLibDir}:${ffmpegUsrLib.absolutePath}:${pythonUsrLib.absolutePath}"

        val fullCommand = mutableListOf<String>()
        fullCommand.add(ffmpegBin.absolutePath)
        fullCommand.addAll(args)

        Log.d(TAG, "Executing FFmpeg: ${fullCommand.take(10).joinToString(" ")} ...")

        val processBuilder = ProcessBuilder(fullCommand).apply {
            environment()["LD_LIBRARY_PATH"] = ldLibraryPath
            redirectErrorStream(true)
        }

        val process = processBuilder.start()

        val output = StringBuilder()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                output.appendLine(line)
            }
        }

        val exitCode = process.waitFor()
        if (exitCode != 0) {
            Log.e(TAG, "FFmpeg failed with exit code $exitCode:\n$output")
            throw IllegalStateException("FFmpeg execution failed (code $exitCode):\n$output")
        } else {
            Log.d(TAG, "FFmpeg execution completed successfully.")
        }
        exitCode
    }

    /**
     * Losslessly remuxes raw downloaded stream to target container (.opus, .m4a)
     * and embeds authoritative canonical metadata tags, square cover art, and lyrics.
     */
    suspend fun remuxAndTag(
        context: Context,
        rawInputFile: File,
        targetOutputFile: File,
        metadata: TrackMetadata,
        coverFile: File?,
        syncedLyrics: String?
    ): File = withContext(Dispatchers.IO) {
        val ext = targetOutputFile.extension.lowercase()
        val args = mutableListOf<String>()
        args.add("-y")
        args.add("-i")
        args.add(rawInputFile.absolutePath)

        if (ext == "opus") {
            // Case 1: Opus Audio Bitstream Copy + METADATA_BLOCK_PICTURE
            args.add("-vn")
            args.add("-c:a")
            args.add("copy")

            // Canonical Metadata Tags
            args.add("-metadata")
            args.add("title=${metadata.title}")

            args.add("-metadata")
            args.add("artist=${metadata.artist}")

            args.add("-metadata")
            args.add("album=${metadata.album}")

            args.add("-metadata")
            args.add("album_artist=${metadata.artist}")

            metadata.year?.let {
                args.add("-metadata")
                args.add("date=$it")
                args.add("-metadata")
                args.add("year=$it")
            }

            metadata.trackNumber?.let {
                args.add("-metadata")
                args.add("track=$it")
            }

            if (!syncedLyrics.isNullOrBlank()) {
                args.add("-metadata")
                args.add("lyrics=$syncedLyrics")
            }

            // Embed Picture block if cover exists
            if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                try {
                    val coverBytes = coverFile.readBytes()
                    val b64Pic = MetadataBlockPictureHelper.buildMetadataBlockPicture(coverBytes)
                    args.add("-metadata")
                    args.add("METADATA_BLOCK_PICTURE=$b64Pic")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to encode cover into METADATA_BLOCK_PICTURE: ${e.message}")
                }
            }

            args.add(targetOutputFile.absolutePath)
            execute(context, args)
        } else if (ext == "m4a") {
            // Case 2: M4A (AAC) Stream Copy + Attached Pic
            if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                args.add("-i")
                args.add(coverFile.absolutePath)
                args.add("-map")
                args.add("0:a")
                args.add("-map")
                args.add("1:v")
                args.add("-c")
                args.add("copy")
                args.add("-disposition:v:0")
                args.add("attached_pic")
                args.add("-metadata:s:v")
                args.add("title=Album cover")
                args.add("-metadata:s:v")
                args.add("comment=Cover (front)")
            } else {
                args.add("-vn")
                args.add("-c:a")
                args.add("copy")
            }

            args.add("-metadata")
            args.add("title=${metadata.title}")
            args.add("-metadata")
            args.add("artist=${metadata.artist}")
            args.add("-metadata")
            args.add("album=${metadata.album}")
            args.add("-metadata")
            args.add("album_artist=${metadata.artist}")

            metadata.year?.let {
                args.add("-metadata")
                args.add("date=$it")
            }

            metadata.trackNumber?.let {
                args.add("-metadata")
                args.add("track=$it")
            }

            if (!syncedLyrics.isNullOrBlank()) {
                args.add("-metadata")
                args.add("lyrics=$syncedLyrics")
            }

            args.add(targetOutputFile.absolutePath)
            execute(context, args)
        } else {
            // Case 3: MP3 Transcode (320kbps)
            if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                args.add("-i")
                args.add(coverFile.absolutePath)
                args.add("-map")
                args.add("0:a")
                args.add("-map")
                args.add("1:v")
                args.add("-c:a")
                args.add("libmp3lame")
                args.add("-b:a")
                args.add("320k")
                args.add("-c:v")
                args.add("copy")
                args.add("-id3v2_version")
                args.add("3")
                args.add("-metadata:s:v")
                args.add("title=Album cover")
                args.add("-metadata:s:v")
                args.add("comment=Cover (front)")
            } else {
                args.add("-vn")
                args.add("-c:a")
                args.add("libmp3lame")
                args.add("-b:a")
                args.add("320k")
            }

            args.add("-metadata")
            args.add("title=${metadata.title}")
            args.add("-metadata")
            args.add("artist=${metadata.artist}")
            args.add("-metadata")
            args.add("album=${metadata.album}")
            args.add("-metadata")
            args.add("date=${metadata.year ?: ""}")
            args.add(targetOutputFile.absolutePath)
            execute(context, args)
        }

        targetOutputFile
    }
}

