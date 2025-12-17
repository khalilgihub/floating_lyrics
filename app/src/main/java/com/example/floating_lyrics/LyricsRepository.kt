package com.example.floating_lyrics

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URLEncoder
import java.util.regex.Pattern

// --- Data Classes ---

data class LyricLine(val time: Long, val text: String)

data class LyricData(val lines: List<LyricLine>, val fullText: String)

data class NowPlayingInfo(
    val title: String,
    val artist: String,
    val duration: Long,
    val lyrics: LyricData? = null
)

@Serializable
data class LrclibSearchResult(
    val id: Int? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null
)

// --- UPDATED REQUEST MODEL ---
@Serializable
data class RomajiRequest(
    val text: String,
    val title: String = "",
    val artist: String = ""
)

@Serializable
data class RomajiResponse(val original: String, val romaji: String)


// --- Repository Object ---

object LyricsRepository {

    private const val TAG = "LyricsRepository"

    // Point to your Termux Server (Localhost)
    private const val ROMAJI_SERVER_URL = "http://localhost:8080/convert"

    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isFloatingLyricsActive = MutableStateFlow(false)
    val isFloatingLyricsActive = _isFloatingLyricsActive.asStateFlow()

    fun setFloatingLyricsActive(isActive: Boolean) {
        _isFloatingLyricsActive.value = isActive
    }

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    private val japanesePattern = Pattern.compile("[\\u3040-\\u309F\\u30A0-\\u30FF\\u4E00-\\u9FAF]")

    fun updatePlaybackState(isPlaying: Boolean) {
        _isPlaying.value = isPlaying
    }

    fun updatePosition(position: Long) {
        _currentPosition.value = position
    }

    fun updateNowPlaying(title: String, artist: String, duration: Long) {
        val currentTrack = _nowPlaying.value
        if (currentTrack?.title != title || currentTrack.artist != artist) {
            _nowPlaying.value = NowPlayingInfo(title, artist, duration)
        } else {
            if (currentTrack.duration != duration) {
                _nowPlaying.value = currentTrack.copy(duration = duration)
            }
        }
    }

    fun clearNowPlaying() {
        _nowPlaying.value = null
    }

    // --- SMART CONVERSION FUNCTION ---
    suspend fun getRomajiFromCloud(japaneseText: String): String {
        // 1. Check if text is Japanese
        if (!japanesePattern.matcher(japaneseText).find()) {
            return japaneseText
        }

        // 2. Get Current Song Info (Context for AI)
        val currentTrack = _nowPlaying.value
        val songTitle = currentTrack?.title ?: "Unknown Song"
        val songArtist = currentTrack?.artist ?: "Unknown Artist"

        return withContext(Dispatchers.IO) {
            try {
                // 3. Send Text + Title + Artist to Termux
                val responseText = client.post(ROMAJI_SERVER_URL) {
                    contentType(ContentType.Application.Json)
                    setBody(RomajiRequest(
                        text = japaneseText,
                        title = songTitle,
                        artist = songArtist
                    ))
                }.bodyAsText()

                val response = json.decodeFromString<RomajiResponse>(responseText)
                response.romaji

            } catch (e: Exception) {
                Log.e(TAG, "Termux connection failed: ${e.message}")
                japaneseText // Fallback to original
            }
        }
    }

    suspend fun fetchLyricsForCurrentSong() {
        val currentTrack = _nowPlaying.value ?: return
        if (currentTrack.lyrics != null && currentTrack.lyrics.lines.isNotEmpty()) return
        fetchLyrics(currentTrack.title, currentTrack.artist, currentTrack.duration)
    }

    suspend fun fetchLyrics(title: String, artist: String, duration: Long = 0L) {
        if (title.isEmpty() || artist.isEmpty()) {
            clearNowPlaying()
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching lyrics for: $title - $artist")
                
                val lyricData = fetchFromLrclibSearch(title, artist, duration)

                if (lyricData != null) {
                    _nowPlaying.value = _nowPlaying.value?.copy(lyrics = lyricData)
                        ?: NowPlayingInfo(title, artist, duration, lyricData)
                } else {
                    val noLyrics = LyricData(emptyList(), "No synced lyrics found for '$title'")
                    _nowPlaying.value = _nowPlaying.value?.copy(lyrics = noLyrics)
                        ?: NowPlayingInfo(title, artist, duration, noLyrics)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching lyrics", e)
            }
        }
    }

    private suspend fun fetchFromLrclibSearch(title: String, artist: String, duration: Long): LyricData? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/search?track_name=$encodedTitle&artist_name=$encodedArtist"
            val responseText = client.get(url).bodyAsText()
            val searchResults = json.decodeFromString<List<LrclibSearchResult>>(responseText)
            if (searchResults.isEmpty()) return null

            // Always prioritize romanized lyrics if available.
            val (romanizedResults, otherResults) = searchResults.partition { result ->
                val hasKeyword = result.trackName?.contains("romanized", ignoreCase = true) == true ||
                                 result.albumName?.contains("romanized", ignoreCase = true) == true

                val isContentRomanized = if (result.syncedLyrics.isNullOrEmpty()) {
                    false
                } else {
                    val tagRegex = """\[[^\]]*\]""".toRegex()
                    val lyricsOnly = tagRegex.replace(result.syncedLyrics, "")
                    lyricsOnly.isNotBlank() && !japanesePattern.matcher(lyricsOnly).find()
                }

                hasKeyword || isContentRomanized
            }

            val finalResults = if (romanizedResults.isNotEmpty()) {
                Log.d(TAG, "Found ${romanizedResults.size} romanized results. Prioritizing them.")
                romanizedResults
            } else {
                Log.d(TAG, "No romanized results found. Using original search results.")
                otherResults
            }

            val bestMatch = findBestMatchByDuration(finalResults, duration)

            if (!bestMatch?.syncedLyrics.isNullOrEmpty()) {
                parseLrc(bestMatch!!.syncedLyrics!!)
            } else if (!bestMatch?.plainLyrics.isNullOrEmpty()) {
                LyricData(emptyList(), bestMatch!!.plainLyrics!!)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Lrclib search failed", e)
            null
        }
    }

    private fun findBestMatchByDuration(results: List<LrclibSearchResult>, duration: Long): LrclibSearchResult? {
        if (results.isEmpty()) return null
        return if (duration > 0) {
            val durationSeconds = duration / 1000.0
            results.minByOrNull { result ->
                if (result.duration != null) kotlin.math.abs(result.duration - durationSeconds) else Double.MAX_VALUE
            }
        } else {
            results.first()
        }
    }

    private fun parseLrc(lrcText: String): LyricData {
        val lines = mutableListOf<LyricLine>()
        val timeRegex = """\[(\d{2}):(\d{2})\.(\d{2,3})\]""".toRegex()
        try {
            lrcText.lines().forEach { line ->
                val text = line.replace(timeRegex, "").trim()
                if (text.isNotEmpty()) {
                    timeRegex.findAll(line).forEach { match ->
                        val minutes = match.groupValues[1].toLong()
                        val seconds = match.groupValues[2].toLong()
                        val millis = match.groupValues[3].padEnd(3, '0').toLong()
                        lines.add(LyricLine((minutes * 60 + seconds) * 1000 + millis, text))
                    }
                }
            }
            lines.sortBy { it.time }
        } catch (e: Exception) { return LyricData(emptyList(), "Error parsing lyrics") }
        return LyricData(lines, lrcText)
    }
}
