package com.example.spotdl.engine

import android.graphics.BitmapFactory
import android.util.Log
import com.example.spotdl.model.TrackMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object CanonicalMetadataEnricher {
    private const val TAG = "MetaEnricher"
    private const val USER_AGENT_BROWSER =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun fetchYtMusicSquareArtwork(videoId: String): String? = withContext(Dispatchers.IO) {
        if (videoId.length != 11) return@withContext null

        val url = "https://music.youtube.com/youtubei/v1/player"
        val payload = JSONObject().apply {
            put("videoId", videoId)
            put(
                "context",
                JSONObject().apply {
                    put(
                        "client",
                        JSONObject().apply {
                            put("clientName", "WEB_REMIX")
                            put("clientVersion", "1.20241001.01.00")
                            put("hl", "en")
                            put("gl", "US")
                        }
                    )
                }
            )
        }

        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", USER_AGENT_BROWSER)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        try {
            httpClient.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return@withContext null
                    val json = JSONObject(body)
                    val thumbnails = json.optJSONObject("videoDetails")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")

                    if (thumbnails != null && thumbnails.length() > 0) {
                        val rawUrl = thumbnails.getJSONObject(thumbnails.length() - 1).optString("url")
                        if (rawUrl.isNotBlank()) {
                            val hiResUrl = rawUrl.replace("=w\\d+-h\\d+.*$".toRegex(), "=w1400-h1400-l90-rj")
                            if (isSquareArtwork(hiResUrl)) {
                                return@withContext hiResUrl
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch YTM artwork for video $videoId: ${e.message}")
        }
        null
    }

    private fun isSquareArtwork(imageUrl: String): Boolean {
        try {
            val req = Request.Builder().url(imageUrl).build()
            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val bytes = resp.body?.bytes() ?: return false
                    val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                    val diff = Math.abs(options.outWidth - options.outHeight)
                    return diff <= 10 && options.outWidth > 200
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Square artwork check failed: ${e.message}")
        }
        return false
    }

    suspend fun enrichWithItunes(base: TrackMetadata): TrackMetadata = withContext(Dispatchers.IO) {
        val searchQueries = listOf(
            "${base.artist} ${base.title}".trim(),
            base.title.trim()
        )

        for (query in searchQueries) {
            if (query.isBlank()) continue
            try {
                val encoded = URLEncoder.encode(query, "UTF-8")
                val itunesUrl = "https://itunes.apple.com/search?term=$encoded&media=music&entity=song&limit=10"
                val req = Request.Builder().url(itunesUrl).build()

                httpClient.newCall(req).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string() ?: return@use
                        val results = JSONObject(body).optJSONArray("results")
                        if (results != null && results.length() > 0) {
                            var bestMatch: JSONObject? = null

                            // 1. Try matching album name
                            if (base.album.isNotBlank()) {
                                for (i in 0 until results.length()) {
                                    val r = results.getJSONObject(i)
                                    if (r.optString("collectionName").equals(base.album, ignoreCase = true)) {
                                        bestMatch = r
                                        break
                                    }
                                }
                            }

                            // 2. Default to first result
                            if (bestMatch == null) {
                                bestMatch = results.getJSONObject(0)
                            }

                            val trackNo = bestMatch.optInt("trackNumber", -1).takeIf { it > 0 } ?: base.trackNumber
                            val totalTracks = bestMatch.optInt("trackCount", -1).takeIf { it > 0 } ?: base.totalTracks
                            val discNo = bestMatch.optInt("discNumber", 1)
                            val genre = bestMatch.optString("primaryGenreName").takeIf { it.isNotBlank() } ?: base.genre
                            val releaseDate = bestMatch.optString("releaseDate")
                            val year = if (!releaseDate.isNullOrBlank() && releaseDate.length >= 4) {
                                releaseDate.substring(0, 4)
                            } else base.year

                            val art100 = bestMatch.optString("artworkUrl100")
                            val hiResCover = if (art100.isNotBlank()) {
                                art100.replace("100x100bb", "1400x1400bb")
                            } else null

                            val finalCover = base.coverUrl ?: hiResCover

                            return@withContext base.copy(
                                trackNumber = trackNo,
                                totalTracks = totalTracks,
                                discNumber = discNo,
                                genre = genre,
                                year = year,
                                coverUrl = finalCover
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "iTunes enrichment search failed for '$query': ${e.message}")
            }
        }

        base
    }
}

