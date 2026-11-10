package com.example.spotdl.engine

import android.util.Log
import com.example.spotdl.model.LyricsResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

object LrclibClient {
    private const val TAG = "LrclibClient"
    private const val BASE_URL = "https://lrclib.net/api"
    private const val USER_AGENT = "spotdl-android/1.0 (https://github.com/spotDL/spotify-downloader)"

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun cleanQueryTerm(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var text = raw.trim()

        if (text.contains("|")) {
            text = text.substringBefore("|").trim()
        }

        // Remove bracketed noise like (Official Video), [Audio], (Lyrics), etc.
        text = text.replace(
            "[\\[({].*?(official|video|audio|lyrics|remaster|hd|4k|ft\\.|feat\\.|visualizer|lyrical).*?[\\])}]".toRegex(
                RegexOption.IGNORE_CASE
            ),
            ""
        )

        // Remove trailing feat./ft.
        text = text.replace("\\b(feat\\.|ft\\.)\\s+.*$".toRegex(RegexOption.IGNORE_CASE), "")

        // Remove trailing dashes, colons or spaces
        text = text.replace("[\\s\\-:]+$".toRegex(), "").trim()

        return if (text.isNotBlank()) text else raw.trim()
    }

    suspend fun fetchLyrics(
        trackName: String,
        artistName: String,
        albumName: String? = null,
        durationSeconds: Long? = null
    ): LyricsResult? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanQueryTerm(trackName)
        val cleanArtist = cleanQueryTerm(artistName)

        if (cleanTitle.isBlank() || cleanArtist.isBlank()) return@withContext null

        // 1. Try exact match
        try {
            val urlBuilder = "$BASE_URL/get".toHttpUrlOrNull()?.newBuilder()
            if (urlBuilder != null) {
                urlBuilder.addQueryParameter("track_name", cleanTitle)
                urlBuilder.addQueryParameter("artist_name", cleanArtist)
                if (!albumName.isNullOrBlank()) {
                    urlBuilder.addQueryParameter("album_name", albumName)
                }
                if (durationSeconds != null && durationSeconds > 0) {
                    urlBuilder.addQueryParameter("duration", durationSeconds.toString())
                }

                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("User-Agent", USER_AGENT)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val json = JSONObject(body)
                            return@withContext LyricsResult(
                                syncedLyrics = json.optString("syncedLyrics").takeIf { it.isNotBlank() },
                                plainLyrics = json.optString("plainLyrics").takeIf { it.isNotBlank() },
                                isInstrumental = json.optBoolean("instrumental", false)
                            )
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Exact lyrics match failed for $cleanArtist - $cleanTitle: ${e.message}")
        }

        // 2. Search fallback endpoint
        try {
            val query = "$cleanArtist $cleanTitle".trim()
            val urlBuilder = "$BASE_URL/search".toHttpUrlOrNull()?.newBuilder()
            if (urlBuilder != null) {
                urlBuilder.addQueryParameter("q", query)
                val request = Request.Builder()
                    .url(urlBuilder.build())
                    .header("User-Agent", USER_AGENT)
                    .build()

                httpClient.newCall(request).execute().use { resp ->
                    if (resp.isSuccessful) {
                        val body = resp.body?.string()
                        if (!body.isNullOrBlank()) {
                            val arr = JSONArray(body)
                            var bestCandidate: JSONObject? = null
                            for (i in 0 until arr.length()) {
                                val item = arr.getJSONObject(i)
                                if (item.optString("syncedLyrics").isNotBlank()) {
                                    bestCandidate = item
                                    break
                                }
                            }
                            if (bestCandidate == null && arr.length() > 0) {
                                bestCandidate = arr.getJSONObject(0)
                            }
                            if (bestCandidate != null) {
                                return@withContext LyricsResult(
                                    syncedLyrics = bestCandidate.optString("syncedLyrics").takeIf { it.isNotBlank() },
                                    plainLyrics = bestCandidate.optString("plainLyrics").takeIf { it.isNotBlank() },
                                    isInstrumental = bestCandidate.optBoolean("instrumental", false)
                                )
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Search lyrics fallback failed: ${e.message}")
        }

        null
    }

    fun saveLrcFile(lrcContent: String, destinationFile: File) {
        try {
            destinationFile.parentFile?.mkdirs()
            FileOutputStream(destinationFile).use { fos ->
                fos.write(lrcContent.trim().toByteArray(Charsets.UTF_8))
                fos.write("\n".toByteArray(Charsets.UTF_8))
            }
            Log.d(TAG, "Saved companion LRC file: ${destinationFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save companion LRC file", e)
        }
    }
}

