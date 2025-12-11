package com.example.floating_lyrics

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
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

// Response from your Python Server
@Serializable
data class RomajiResponse(val original: String, val romaji: String)


// --- Repository Object ---

object LyricsRepository {

    private const val TAG = "LyricsRepository"

    // REPLACE THIS IF YOUR URL IS DIFFERENT
    private const val ROMAJI_SERVER_URL = "https://romaji-server.onrender.com/convert"

    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val json = Json {
        isLenient = true
        ignoreUnknownKeys = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
    }

    // Regex to detect Japanese characters
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

    // --- NEW: Cloud Romaji Conversion Function ---
    suspend fun getRomajiFromCloud(japaneseText: String): String {
        // Optimization: Don't ask server if text has no Japanese
        if (!japanesePattern.matcher(japaneseText).find()) {
            return japaneseText
        }

        return withContext(Dispatchers.IO) {
            try {
                val encodedText = URLEncoder.encode(japaneseText, "UTF-8")
                val url = "$ROMAJI_SERVER_URL?text=$encodedText"

                // Log.d(TAG, "Fetching Romaji: $url") // Uncomment for debugging

                val responseText = client.get(url).bodyAsText()
                val response = json.decodeFromString<RomajiResponse>(responseText)

                response.romaji
            } catch (e: Exception) {
                Log.e(TAG, "Cloud conversion failed", e)
                japaneseText // Fallback to original text
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
            Log.d(TAG, "Empty title or artist")
            clearNowPlaying()
            return
        }

        withContext(Dispatchers.IO) {
            try {
                Log.d(TAG, "Fetching lyrics for: $title - $artist (${duration}ms)")

                var lyricData: LyricData? = null

                // 1. Try lrclib.net with GET endpoint
                lyricData = fetchFromLrclibGet(title, artist, duration)

                if (lyricData == null) {
                    // 2. Try lrclib.net search endpoint
                    lyricData = fetchFromLrclibSearch(title, artist, duration)
                }

                // Update with results
                if (lyricData != null && lyricData.lines.isNotEmpty()) {
                    Log.d(TAG, "Successfully found synced lyrics")
                    _nowPlaying.value = _nowPlaying.value?.copy(lyrics = lyricData)
                        ?: NowPlayingInfo(title, artist, duration, lyricData)
                } else if (lyricData != null && lyricData.fullText.isNotEmpty()) {
                    Log.d(TAG, "Found plain text lyrics only")
                    _nowPlaying.value = _nowPlaying.value?.copy(lyrics = lyricData)
                        ?: NowPlayingInfo(title, artist, duration, lyricData)
                } else {
                    Log.d(TAG, "No lyrics found")
                    _nowPlaying.value = _nowPlaying.value?.copy(
                        lyrics = LyricData(emptyList(), "No synced lyrics found")
                    ) ?: NowPlayingInfo(title, artist, duration, LyricData(emptyList(), "No synced lyrics found"))
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching lyrics", e)
                _nowPlaying.value = _nowPlaying.value?.copy(
                    lyrics = LyricData(emptyList(), "Error: ${e.message}")
                ) ?: NowPlayingInfo(title, artist, duration, LyricData(emptyList(), "Error: ${e.message}"))
            }
        }
    }

    private suspend fun fetchFromLrclibGet(title: String, artist: String, duration: Long): LyricData? {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val durationSeconds = (duration / 1000).toInt()

            val url = "https://lrclib.net/api/get?artist_name=$encodedArtist&track_name=$encodedTitle&duration=$durationSeconds"

            val responseText = client.get(url).bodyAsText()
            val result = json.decodeFromString<LrclibSearchResult>(responseText)

            if (result.syncedLyrics != null && result.syncedLyrics.isNotEmpty()) {
                parseLrc(result.syncedLyrics)
            } else if (result.plainLyrics != null && result.plainLyrics.isNotEmpty()) {
                LyricData(emptyList(), result.plainLyrics)
            } else {
                null
            }
        } catch (e: Exception) {
            null
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

            // Find best match based on duration
            val bestMatch = if (duration > 0) {
                val durationSeconds = duration / 1000.0
                searchResults.minByOrNull { result ->
                    if (result.duration != null) {
                        kotlin.math.abs(result.duration - durationSeconds)
                    } else {
                        Double.MAX_VALUE
                    }
                }
            } else {
                searchResults.firstOrNull()
            }

            if (bestMatch?.syncedLyrics != null && bestMatch.syncedLyrics.isNotEmpty()) {
                parseLrc(bestMatch.syncedLyrics)
            } else if (bestMatch?.plainLyrics != null && bestMatch.plainLyrics.isNotEmpty()) {
                LyricData(emptyList(), bestMatch.plainLyrics)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseLrc(lrcText: String): LyricData {
        val lines = mutableListOf<LyricLine>()
        val timeRegex = """\[(\d{2}):(\d{2})\.(\d{2,3})\]""".toRegex()

        try {
            lrcText.lines().forEach { line ->
                val text = line.replace(timeRegex, "").trim()
                if (text.isNotEmpty()) {
                    val matches = timeRegex.findAll(line)
                    if (matches.any()) {
                        matches.forEach { match ->
                            val minutes = match.groupValues[1].toLong()
                            val seconds = match.groupValues[2].toLong()
                            val millis = match.groupValues[3].padEnd(3, '0').toLong()
                            val totalMillis = (minutes * 60 + seconds) * 1000 + millis
                            lines.add(LyricLine(totalMillis, text))
                        }
                    }
                }
            }
            lines.sortBy { it.time }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing LRC file", e)
            return LyricData(emptyList(), "Error parsing lyrics")
        }

        return LyricData(lines, lrcText)
    }
}