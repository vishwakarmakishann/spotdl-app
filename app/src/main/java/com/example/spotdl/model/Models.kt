package com.example.spotdl.model

import java.io.Serializable

enum class CollectionType {
    TRACK,
    ALBUM,
    PLAYLIST
}

data class TrackMetadata(
    val title: String,
    val artist: String,
    val album: String,
    val albumArtist: String = artist,
    val releaseDate: String? = null,
    val year: String? = null,
    val trackNumber: Int? = null,
    val totalTracks: Int? = null,
    val discNumber: Int = 1,
    val genre: String? = null,
    val coverUrl: String? = null,
    val isrc: String? = null,
    val durationSeconds: Long? = null,
    val spotifyId: String? = null,
    val youtubeVideoId: String? = null
) : Serializable

data class CollectionMetadata(
    val title: String,
    val type: CollectionType,
    val curator: String? = null,
    val coverUrl: String? = null,
    val totalTracks: Int,
    val tracks: List<TrackMetadata>
) : Serializable

enum class DownloadStatus {
    QUEUED,
    RESOLVING,
    DOWNLOADING,
    EMBEDDING,
    COMPLETED,
    FAILED,
    PAUSED
}

data class DownloadItem(
    val id: String,
    val trackMetadata: TrackMetadata,
    val collectionTitle: String? = null,
    val collectionType: CollectionType = CollectionType.TRACK,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progressPercent: Float = 0f,
    val speedBytesPerSec: Long = 0,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val etaSeconds: Long = 0,
    val audioPath: String? = null,
    val lrcPath: String? = null,
    val coverPath: String? = null,
    val errorMessage: String? = null
) {
    val isFinished: Boolean
        get() = status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED

    val isActive: Boolean
        get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.EMBEDDING || status == DownloadStatus.RESOLVING
}

data class LyricsResult(
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val isInstrumental: Boolean = false,
    val source: String = "lrclib"
) {
    val hasSynced: Boolean
        get() = !syncedLyrics.isNullOrBlank()

    val hasAny: Boolean
        get() = hasSynced || !plainLyrics.isNullOrBlank()
}

enum class AudioFormatPreference(val label: String, val extension: String, val ytDlpFormat: String) {
    BEST_LOSSLESS("Best Native (Opus 160k / AAC 256k)", "opus", "141/251/bestaudio[ext=webm]/140/bestaudio/best"),
    OPUS("Opus (~160 kbps 48kHz)", "opus", "251/bestaudio[ext=webm]/bestaudio"),
    AAC("AAC (~256 kbps)", "m4a", "141/140/bestaudio[ext=m4a]/bestaudio"),
    MP3_320("MP3 (320 kbps)", "mp3", "bestaudio")
}
