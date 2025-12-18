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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
    // Ensure this matches your Python Server IP/Port
    private const val ROMAJI_SERVER_URL = "http://localhost:8080/convert"

    private val _nowPlaying = MutableStateFlow<NowPlayingInfo?>(null)
    val nowPlaying = _nowPlaying.asStateFlow()

    private val _currentPosition = MutableStateFlow(0L)
    val currentPosition = _currentPosition.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying = _isPlaying.asStateFlow()

    private val _isFloatingLyricsActive = MutableStateFlow(false)
    val isFloatingLyricsActive = _isFloatingLyricsActive.asStateFlow()

    // Scope for managing background fetch jobs
    private val repoScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Track the current active fetch job
    private var fetchJob: Job? = null

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
        // Only update if something actually changed
        if (currentTrack?.title != title || currentTrack.artist != artist) {
            _nowPlaying.value = NowPlayingInfo(title, artist, duration)
            // Trigger fetch automatically on song change
            fetchLyrics(title, artist, duration)
        } else {
            if (currentTrack.duration != duration) {
                _nowPlaying.value = currentTrack.copy(duration = duration)
            }
        }
    }

    fun clearNowPlaying() {
        _nowPlaying.value = null
        fetchJob?.cancel()
    }

    suspend fun getRomajiFromCloud(japaneseText: String): String {
        if (!containsJapanese(japaneseText)) return japaneseText

        val currentTrack = _nowPlaying.value
        val songTitle = currentTrack?.title ?: "Unknown"
        val songArtist = currentTrack?.artist ?: "Unknown"

        return withContext(Dispatchers.IO) {
            try {
                val responseText = client.post(ROMAJI_SERVER_URL) {
                    contentType(ContentType.Application.Json)
                    setBody(RomajiRequest(japaneseText, songTitle, songArtist))
                }.bodyAsText()
                val response = json.decodeFromString<RomajiResponse>(responseText)
                response.romaji
            } catch (e: Exception) {
                Log.e(TAG, "AI Server failed: ${e.message}")
                japaneseText
            }
        }
    }

    /**
     * OPTIMIZED: Converts lyrics in PARALLEL using async/awaitAll
     */
    private suspend fun convertLyricsToRomaji(original: LyricData): LyricData = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting PARALLEL conversion for ${original.lines.size} lines...")

        // 1. Launch ALL requests at once
        val deferredLines = original.lines.map { line ->
            async {
                // Cancellation check
                if (!isActive) throw kotlinx.coroutines.CancellationException()

                if (line.text.isBlank()) {
                    line
                } else {
                    // This network call now runs in parallel with others
                    val romaji = getRomajiFromCloud(line.text)
                    line.copy(text = romaji)
                }
            }
        }

        // 2. Wait for all of them to finish
        val convertedLines = deferredLines.awaitAll()

        Log.d(TAG, "Conversion complete.")
        original.copy(lines = convertedLines)
    }

    fun fetchLyricsForCurrentSong() {
        val currentTrack = _nowPlaying.value ?: return
        if (currentTrack.lyrics != null && currentTrack.lyrics.lines.isNotEmpty()) return
        fetchLyrics(currentTrack.title, currentTrack.artist, currentTrack.duration)
    }

    fun fetchLyrics(title: String, artist: String, duration: Long = 0L) {
        if (title.isEmpty() || artist.isEmpty()) {
            clearNowPlaying()
            return
        }

        // Cancel previous job to prevent "Flood" from rapid song skipping
        fetchJob?.cancel()

        fetchJob = repoScope.launch {
            try {
                Log.d(TAG, "Starting fetch job for: $title")
                val lyricData = fetchFromLrclibSearch(title, artist, duration)

                if (isActive) {
                    if (lyricData != null) {
                        _nowPlaying.value = _nowPlaying.value?.copy(lyrics = lyricData)
                            ?: NowPlayingInfo(title, artist, duration, lyricData)
                    } else {
                        val noLyrics = LyricData(emptyList(), "No synced lyrics found")
                        _nowPlaying.value = _nowPlaying.value?.copy(lyrics = noLyrics)
                            ?: NowPlayingInfo(title, artist, duration, noLyrics)
                    }
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    Log.e(TAG, "Error fetching lyrics", e)
                }
            }
        }
    }

    private fun containsJapanese(text: String): Boolean {
        return japanesePattern.matcher(text).find()
    }

    private suspend fun fetchFromLrclibSearch(title: String, artist: String, duration: Long): LyricData? {
        return try {
            val isJapaneseSong = containsJapanese(title) || containsJapanese(artist)
            val allResults = mutableSetOf<LrclibSearchResult>()

            Log.d(TAG, "Searching: $title")
            allResults.addAll(searchLrclib(title, artist))

            val englishPart = extractEnglishPart(title)
            if (englishPart != null && englishPart != title) {
                allResults.addAll(searchLrclib(englishPart, artist))
            }

            val japanesePart = extractJapanesePart(title)
            if (japanesePart != null && japanesePart != title) {
                allResults.addAll(searchLrclib(japanesePart, artist))
            }

            if (allResults.isEmpty()) return null

            val (romanizedResults, otherResults) = allResults.partition { isResultRomanized(it) }

            val finalResults = if (isJapaneseSong) {
                if (romanizedResults.isNotEmpty()) romanizedResults else allResults.toList()
            } else {
                if (romanizedResults.isNotEmpty()) romanizedResults else allResults.toList()
            }

            val bestMatch = findBestMatchByDuration(finalResults, duration) ?: return null
            Log.d(TAG, "Best match: ${bestMatch.trackName}")

            val rawLyricData = if (!bestMatch.syncedLyrics.isNullOrEmpty()) {
                parseLrc(bestMatch.syncedLyrics)
            } else if (!bestMatch.plainLyrics.isNullOrEmpty()) {
                LyricData(emptyList(), bestMatch.plainLyrics)
            } else {
                return null
            }

            val needsConversion = isJapaneseSong && !isResultRomanized(bestMatch)

            return if (needsConversion) {
                Log.d(TAG, "Result is Japanese. Converting...")
                convertLyricsToRomaji(rawLyricData)
            } else {
                rawLyricData
            }

        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e(TAG, "Lrclib search failed", e)
            null
        }
    }

    private fun extractEnglishPart(title: String): String? {
        val separators = listOf(" - ", " / ", " (")
        for (separator in separators) {
            if (title.contains(separator)) {
                val parts = title.split(separator)
                for (part in parts) {
                    val cleaned = part.trim().removeSuffix(")").removeSuffix("]")
                    if (cleaned.isNotEmpty() && !containsJapanese(cleaned)) return cleaned
                }
            }
        }
        return null
    }

    private fun extractJapanesePart(title: String): String? {
        val separators = listOf(" - ", " / ", " (")
        for (separator in separators) {
            if (title.contains(separator)) {
                val parts = title.split(separator)
                for (part in parts) {
                    val cleaned = part.trim()
                    if (cleaned.isNotEmpty() && containsJapanese(cleaned)) return cleaned
                }
            }
        }
        return null
    }

    private suspend fun searchLrclib(title: String, artist: String): List<LrclibSearchResult> {
        return try {
            val encodedTitle = URLEncoder.encode(title, "UTF-8")
            val encodedArtist = URLEncoder.encode(artist, "UTF-8")
            val url = "https://lrclib.net/api/search?track_name=$encodedTitle&artist_name=$encodedArtist"
            val responseText = client.get(url).bodyAsText()
            json.decodeFromString<List<LrclibSearchResult>>(responseText)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun isResultRomanized(result: LrclibSearchResult): Boolean {
        if (result.albumName?.contains("romanized", true) == true) return true
        if (result.albumName?.contains("romaji", true) == true) return true
        if (result.trackName?.contains("romanized", true) == true) return true
        if (result.trackName?.contains("romaji", true) == true) return true
        return checkLyricsContentIsRomanized(result)
    }

    private fun checkLyricsContentIsRomanized(result: LrclibSearchResult): Boolean {
        val lyrics = result.syncedLyrics ?: result.plainLyrics ?: return false
        val lyricsOnly = lyrics.replace("""\[[^\]]*\]""".toRegex(), "").trim()
        if (lyricsOnly.isBlank()) return false
        val sample = lyricsOnly.take(500)
        return !japanesePattern.matcher(sample).find()
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
        } catch (e: Exception) {
            return LyricData(emptyList(), "Error parsing lyrics")
        }
        return LyricData(lines, lrcText)
    }
}