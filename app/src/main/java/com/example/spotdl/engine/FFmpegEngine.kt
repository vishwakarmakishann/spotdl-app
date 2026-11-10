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

    private fun escapeFfmetadataValue(value: String): String {
        return value
            .replace("\\", "\\\\")
            .replace("=", "\\=")
            .replace(";", "\\;")
            .replace("#", "\\#")
            .replace("\n", "\\\n")
    }

    /**
     * Losslessly remuxes raw downloaded stream to target container (.opus, .m4a)
     * and embeds authoritative canonical metadata tags, square cover art, and lyrics.
     * Uses FFmpeg ffmetadata files to avoid Linux ARG_MAX (error=7) command line overflow.
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
        val tempMetaFile = File(rawInputFile.parentFile, "meta_${System.currentTimeMillis()}.txt")

        try {
            // Write standard ;FFMETADATA1 file
            val metaContent = StringBuilder()
            metaContent.appendLine(";FFMETADATA1")
            metaContent.appendLine("title=${escapeFfmetadataValue(metadata.title)}")
            metaContent.appendLine("artist=${escapeFfmetadataValue(metadata.artist)}")
            metaContent.appendLine("album=${escapeFfmetadataValue(metadata.album)}")
            metaContent.appendLine("album_artist=${escapeFfmetadataValue(metadata.artist)}")

            metadata.year?.let {
                metaContent.appendLine("date=${escapeFfmetadataValue(it)}")
                metaContent.appendLine("year=${escapeFfmetadataValue(it)}")
            }

            metadata.trackNumber?.let {
                metaContent.appendLine("track=$it")
            }

            if (!syncedLyrics.isNullOrBlank()) {
                metaContent.appendLine("lyrics=${escapeFfmetadataValue(syncedLyrics)}")
            }

            if (ext == "opus") {
                // For Opus: Embed Picture block via RFC VorbisComment inside ffmetadata
                if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                    try {
                        val coverBytes = coverFile.readBytes()
                        val b64Pic = MetadataBlockPictureHelper.buildMetadataBlockPicture(coverBytes)
                        metaContent.appendLine("METADATA_BLOCK_PICTURE=$b64Pic")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to encode cover into METADATA_BLOCK_PICTURE: ${e.message}")
                    }
                }
            }

            tempMetaFile.writeText(metaContent.toString(), Charsets.UTF_8)

            val args = mutableListOf<String>()
            args.add("-y")
            args.add("-i")
            args.add(rawInputFile.absolutePath)

            if (ext == "opus") {
                // Case 1: Opus Audio Bitstream Copy + Metadata File
                args.add("-i")
                args.add(tempMetaFile.absolutePath)
                args.add("-map")
                args.add("0:a")
                args.add("-map_metadata")
                args.add("1")
                args.add("-c:a")
                args.add("copy")
                args.add(targetOutputFile.absolutePath)
                execute(context, args)
            } else if (ext == "m4a") {
                // Case 2: M4A (AAC) Stream Copy + Attached Pic + Metadata File
                if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                    args.add("-i")
                    args.add(coverFile.absolutePath)
                    args.add("-i")
                    args.add(tempMetaFile.absolutePath)
                    args.add("-map")
                    args.add("0:a")
                    args.add("-map")
                    args.add("1:v")
                    args.add("-map_metadata")
                    args.add("2")
                    args.add("-c")
                    args.add("copy")
                    args.add("-disposition:v:0")
                    args.add("attached_pic")
                    args.add("-metadata:s:v")
                    args.add("title=Album cover")
                    args.add("-metadata:s:v")
                    args.add("comment=Cover (front)")
                } else {
                    args.add("-i")
                    args.add(tempMetaFile.absolutePath)
                    args.add("-vn")
                    args.add("-map")
                    args.add("0:a")
                    args.add("-map_metadata")
                    args.add("1")
                    args.add("-c:a")
                    args.add("copy")
                }
                args.add(targetOutputFile.absolutePath)
                execute(context, args)
            } else {
                // Case 3: MP3 Transcode (320kbps)
                if (coverFile != null && coverFile.exists() && coverFile.length() > 0) {
                    args.add("-i")
                    args.add(coverFile.absolutePath)
                    args.add("-i")
                    args.add(tempMetaFile.absolutePath)
                    args.add("-map")
                    args.add("0:a")
                    args.add("-map")
                    args.add("1:v")
                    args.add("-map_metadata")
                    args.add("2")
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
                    args.add("-i")
                    args.add(tempMetaFile.absolutePath)
                    args.add("-vn")
                    args.add("-map")
                    args.add("0:a")
                    args.add("-map_metadata")
                    args.add("1")
                    args.add("-c:a")
                    args.add("libmp3lame")
                    args.add("-b:a")
                    args.add("320k")
                }
                args.add(targetOutputFile.absolutePath)
                execute(context, args)
            }
        } finally {
            if (tempMetaFile.exists()) {
                tempMetaFile.delete()
            }
        }

        targetOutputFile
    }
}
