package com.example.spotdl.engine

import android.util.Log
import com.example.spotdl.model.CollectionMetadata
import com.example.spotdl.model.CollectionType
import com.example.spotdl.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object SpotifyResolver {
    private const val TAG = "SpotifyResolver"
    private const val USER_AGENT_CRAWLER =
        "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)"
    private const val USER_AGENT_BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    fun isSpotifyUrl(query: String?): Boolean {
        if (query.isNullOrBlank()) return false
        val q = query.trim()
        val regex = "^(https?://)?(open\\.spotify\\.com/(track|album|playlist)/[a-zA-Z0-9]+|spotify\\.link/[a-zA-Z0-9]+|spotify:(track|album|playlist):[a-zA-Z0-9]+)".toRegex(RegexOption.IGNORE_CASE)
        return regex.containsMatchIn(q)
    }

    fun extractUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val pattern = Pattern.compile("https?://[^\\s]+")
        val matcher = pattern.matcher(text)
        return if (matcher.find()) {
            matcher.group(0)
        } else {
            text.trim()
        }
    }

    suspend fun resolve(rawUrlOrText: String): CollectionMetadata = withContext(Dispatchers.IO) {
        val extracted = extractUrl(rawUrlOrText) ?: rawUrlOrText
        val canonicalUrl = normalizeSpotifyUrl(extracted)
        val (type, id) = parseLinkType(canonicalUrl)

        Log.d(TAG, "Resolving Spotify $type with ID: $id")

        when (type) {
            "track" -> {
                val track = fetchSpotifyTrack(id)
                CollectionMetadata(
                    title = track.title,
                    type = CollectionType.TRACK,
                    curator = track.artist,
                    coverUrl = track.coverUrl,
                    totalTracks = 1,
                    tracks = listOf(track)
                )
            }
            "album" -> fetchSpotifyCollection("album", id)
            "playlist" -> fetchSpotifyCollection("playlist", id)
            else -> throw IllegalArgumentException("Unsupported Spotify entity type: $type")
        }
    }

    private fun normalizeSpotifyUrl(url: String): String {
        var clean = url.trim()
        if (clean.startsWith("spotify:")) {
            val parts = clean.split(":")
            if (parts.size >= 3) {
                return "https://open.spotify.com/${parts[1]}/${parts[2]}"
            }
        }

        if (clean.contains("spotify.link")) {
            val request = Request.Builder()
                .url(clean)
                .head()
                .build()
            try {
                httpClient.newCall(request).execute().use { response ->
                    clean = response.request.url.toString()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to resolve spotify.link redirect, continuing with raw URL", e)
            }
        }

        return clean.substringBefore("?")
    }

    private fun parseLinkType(url: String): Pair<String, String> {
        val pattern = Pattern.compile("open\\.spotify\\.com/(track|album|playlist)/([a-zA-Z0-9]+)", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(url)
        if (matcher.find()) {
            return Pair(matcher.group(1)!!.lowercase(), matcher.group(2)!!)
        }
        throw IllegalArgumentException("Invalid or unsupported Spotify URL: $url")
    }

    private fun fetchSpotifyTrack(trackId: String): TrackMetadata {
        val targetUrl = "https://open.spotify.com/track/$trackId"
        val request = Request.Builder()
            .url(targetUrl)
            .header("User-Agent", USER_AGENT_CRAWLER)
            .build()

        val html = executeWithRetry(request)

        val ogTitle = extractMetaContent(html, "og:title") ?: "Unknown Title"
        val ogDesc = extractMetaContent(html, "og:description")
        val ogImage = extractMetaContent(html, "og:image")
        val musicArtist = extractMetaContent(html, "music:musician_description")
        val musicTrackNo = extractMetaContent(html, "music:album:track")
        val musicReleaseDate = extractMetaContent(html, "music:release_date")
        val musicDuration = extractMetaContent(html, "music:duration")?.toLongOrNull()

        var artist = musicArtist ?: "Unknown Artist"
        var album = ogTitle
        var year: String? = null

        if (ogDesc != null) {
            val parts = ogDesc.split("·").map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size >= 2) {
                artist = parts[0]
                album = parts[1]
            }
            for (p in parts) {
                if (p.matches("^\\d{4}$".toRegex())) {
                    year = p
                }
            }
        }

        if (year == null && musicReleaseDate != null && musicReleaseDate.length >= 4) {
            year = musicReleaseDate.substring(0, 4)
        }

        val trackNumber = musicTrackNo?.toIntOrNull()

        return TrackMetadata(
            title = ogTitle,
            artist = artist,
            album = album,
            albumArtist = artist,
            releaseDate = musicReleaseDate,
            year = year,
            trackNumber = trackNumber,
            durationSeconds = musicDuration,
            coverUrl = ogImage,
            spotifyId = trackId
        )
    }

    private fun fetchSpotifyCollection(type: String, entityId: String): CollectionMetadata {
        val embedUrl = "https://open.spotify.com/embed/$type/$entityId"
        val request = Request.Builder()
            .url(embedUrl)
            .header("User-Agent", USER_AGENT_BROWSER)
            .build()

        val html = executeWithRetry(request)
        val scriptPattern = Pattern.compile("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", Pattern.DOTALL)
        val matcher = scriptPattern.matcher(html)
        if (!matcher.find()) {
            throw IOException("Could not parse Spotify embed metadata for $type: $entityId")
        }

        val jsonStr = matcher.group(1)!!
        val root = JSONObject(jsonStr)
        val entity = root.optJSONObject("props")
            ?.optJSONObject("pageProps")
            ?.optJSONObject("state")
            ?.optJSONObject("data")
            ?.optJSONObject("entity")
            ?: throw IOException("Spotify collection JSON payload missing entity node")

        val collectionTitle = entity.optString("title", entity.optString("name", "Spotify Collection"))
        val trackListArray = entity.optJSONArray("trackList") ?: org.json.JSONArray()

        // Extract largest collection cover art (images array or visualIdentity)
        var collectionCoverUrl: String? = null
        val images = entity.optJSONArray("images")
        if (images != null && images.length() > 0) {
            val largest = images.getJSONObject(images.length() - 1)
            collectionCoverUrl = largest.optString("url")
        }

        val isAlbum = type.equals("album", ignoreCase = true)
        val collectionType = if (isAlbum) CollectionType.ALBUM else CollectionType.PLAYLIST
        val tracks = mutableListOf<TrackMetadata>()

        for (i in 0 until trackListArray.length()) {
            val t = trackListArray.getJSONObject(i)
            val trackTitle = t.optString("title", t.optString("name", "Unknown Title"))
            var artistName = t.optString("subtitle", "")
            if (artistName.isBlank() && t.has("artists")) {
                val artistsArr = t.optJSONArray("artists")
                if (artistsArr != null) {
                    val names = mutableListOf<String>()
                    for (a in 0 until artistsArr.length()) {
                        names.add(artistsArr.getJSONObject(a).optString("name"))
                    }
                    artistName = names.joinToString(", ")
                }
            }
            if (artistName.isBlank()) {
                artistName = "Unknown Artist"
            }

            val trackNumber = i + 1
            val trackCoverUrl = if (isAlbum) collectionCoverUrl else null
            val durationMs = t.optLong("duration_ms", t.optLong("duration", 0L))
            val durationSec = if (durationMs > 1000L) durationMs / 1000L else if (durationMs > 0L) durationMs else null

            tracks.add(
                TrackMetadata(
                    title = trackTitle,
                    artist = artistName,
                    album = if (isAlbum) collectionTitle else trackTitle,
                    albumArtist = artistName,
                    trackNumber = trackNumber,
                    totalTracks = trackListArray.length(),
                    durationSeconds = durationSec,
                    coverUrl = trackCoverUrl,
                    spotifyId = t.optString("id").takeIf { it.isNotBlank() }
                )
            )
        }

        return CollectionMetadata(
            title = collectionTitle,
            type = collectionType,
            curator = if (isAlbum) tracks.firstOrNull()?.artist else "Spotify Curator",
            coverUrl = collectionCoverUrl,
            totalTracks = tracks.size,
            tracks = tracks
        )
    }

    private fun extractMetaContent(html: String, property: String): String? {
        val pattern = Pattern.compile("<meta\\s+(?:property|name)=\"$property\"\\s+content=\"(.*?)\"", Pattern.CASE_INSENSITIVE)
        val matcher = pattern.matcher(html)
        return if (matcher.find()) {
            matcher.group(1)?.replace("&amp;", "&")?.replace("&#39;", "'")?.replace("&quot;", "\"")
        } else null
    }

    private fun executeWithRetry(request: Request, retries: Int = 3): String {
        var lastException: Exception? = null
        for (attempt in 1..retries) {
            try {
                httpClient.newCall(request).execute().use { response ->
                    if (response.isSuccessful) {
                        return response.body?.string() ?: ""
                    }
                    if (response.code in 400..499 && response.code != 429) {
                        throw IOException("HTTP request failed with status: ${response.code}")
                    }
                }
            } catch (e: Exception) {
                lastException = e
                Log.w(TAG, "Request failed (attempt $attempt/$retries): ${e.message}")
            }
            try {
                Thread.sleep((1000 * attempt).toLong())
            } catch (ignored: InterruptedException) {}
        }
        throw lastException ?: IOException("Failed to fetch request after $retries retries")
    }
}
