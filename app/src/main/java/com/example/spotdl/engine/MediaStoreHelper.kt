package com.example.spotdl.engine

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.util.Log
import com.example.spotdl.model.CollectionType
import com.example.spotdl.model.TrackMetadata
import java.io.File

object MediaStoreHelper {
    private const val TAG = "MediaStoreHelper"

    fun sanitizeFilename(name: String?): String {
        if (name.isNullOrBlank()) return "Unknown"
        var cleaned = name.replace("[\\\\/*?:\"<>|]".toRegex(), "_")
        cleaned = cleaned.replace("[\\s_]+".toRegex(), " ").trim()
        return if (cleaned.isNotEmpty()) cleaned else "Unknown"
    }

    fun getDownloadBaseDir(context: Context): File {
        // Standard Android Public Music Directory
        val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        val spotdlDir = File(musicDir, "spotdl")
        if (!spotdlDir.exists()) {
            spotdlDir.mkdirs()
        }
        return spotdlDir
    }

    fun computeFileDestinations(
        context: Context,
        meta: TrackMetadata,
        collectionTitle: String?,
        collectionType: CollectionType,
        extension: String
    ): Triple<File, File, File> {
        val baseDir = getDownloadBaseDir(context)
        val extNorm = if (extension.startsWith(".")) extension else ".$extension"

        val parentDir: File
        val fileStem: String

        if (collectionType == CollectionType.PLAYLIST && !collectionTitle.isNullOrBlank()) {
            // Playlist structure: Music/spotdl/<Playlist Name>/<01 - Artist - Title>.<ext>
            val cleanPlaylist = sanitizeFilename(collectionTitle)
            parentDir = File(baseDir, cleanPlaylist)
            val cleanArtist = sanitizeFilename(meta.artist)
            val cleanTitle = sanitizeFilename(meta.title)
            val trackPrefix = if (meta.trackNumber != null && meta.trackNumber > 0) {
                String.format("%02d - ", meta.trackNumber)
            } else ""
            fileStem = "$trackPrefix$cleanArtist - $cleanTitle"
        } else {
            // Artist/Album structure: Music/spotdl/<Artist>/<Album (Year)>/<01 - Title>.<ext>
            val cleanArtist = sanitizeFilename(meta.albumArtist.ifBlank { meta.artist })
            val cleanAlbum = sanitizeFilename(meta.album.ifBlank { meta.title })
            val albumFolder = if (!meta.year.isNullOrBlank()) {
                "$cleanAlbum (${meta.year})"
            } else cleanAlbum
            parentDir = File(File(baseDir, cleanArtist), albumFolder)

            val cleanTitle = sanitizeFilename(meta.title)
            val trackPrefix = if (meta.trackNumber != null && meta.trackNumber > 0) {
                String.format("%02d - ", meta.trackNumber)
            } else ""
            fileStem = "$trackPrefix$cleanTitle"
        }

        parentDir.mkdirs()

        val audioFile = File(parentDir, "$fileStem$extNorm")
        val lrcFile = File(parentDir, "$fileStem.lrc")
        val coverFile = File(parentDir, "cover.jpg")

        return Triple(audioFile, lrcFile, coverFile)
    }

    fun notifyMediaScanner(context: Context, filePath: String) {
        try {
            MediaScannerConnection.scanFile(
                context.applicationContext,
                arrayOf(filePath),
                null
            ) { path, uri ->
                Log.d(TAG, "MediaScanner indexed file: $path -> $uri")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to invoke MediaScanner for $filePath", e)
        }
    }

    fun safeCopyTo(context: Context, source: File, destination: File, mimeType: String = "audio/*") {
        try {
            destination.parentFile?.mkdirs()
            if (destination.exists()) {
                destination.delete()
            }
            source.copyTo(destination, overwrite = true)
            Log.d(TAG, "Direct copy succeeded: ${destination.absolutePath}")
            return
        } catch (e: Exception) {
            Log.w(TAG, "Direct file copy failed (${e.message}), attempting MediaStore scoped fallback...")
        }

        // Scoped Storage ContentResolver Fallback
        val resolver = context.contentResolver
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, destination.name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val musicRoot = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
                val relPath = try {
                    destination.parentFile?.relativeTo(musicRoot)?.path
                } catch (_: Exception) {
                    "spotdl"
                }
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/$relPath")
                put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val collectionUri = if (mimeType.startsWith("image")) {
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        } else {
            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }

        val uri = resolver.insert(collectionUri, values)
            ?: throw IllegalStateException("Could not create MediaStore entry for ${destination.name}")

        resolver.openOutputStream(uri)?.use { out ->
            source.inputStream().use { input ->
                input.copyTo(out)
            }
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.clear()
            values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        }
        Log.d(TAG, "MediaStore fallback write successful: $uri")
    }
}

