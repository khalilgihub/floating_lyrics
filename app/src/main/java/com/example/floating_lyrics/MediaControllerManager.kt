package com.example.floating_lyrics

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object MediaControllerManager {
    private var mediaController: MediaController? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionTrackerJob: Job? = null

    private const val TAG = "MediaControllerManager"

    fun setActiveSession(context: Context, token: MediaSession.Token?) {
        mediaController?.unregisterCallback(mediaControllerCallback)
        mediaController = if (token != null) {
            try {
                MediaController(context, token)
            } catch (e: Exception) {
                Log.e(TAG, "Error creating MediaController", e)
                null
            }
        } else {
            null
        }

        mediaController?.registerCallback(mediaControllerCallback)

        // Immediately process current state
        val metadata = mediaController?.metadata
        val playbackState = mediaController?.playbackState

        Log.d(TAG, "Active session set. Metadata: ${metadata != null}, PlaybackState: ${playbackState?.state}")

        mediaControllerCallback.onMetadataChanged(metadata)
        mediaControllerCallback.onPlaybackStateChanged(playbackState)
    }

    private val mediaControllerCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            super.onPlaybackStateChanged(state)
            val isPlaying = state?.state == PlaybackState.STATE_PLAYING
            Log.d(TAG, "Playback state changed: $isPlaying (state: ${state?.state})")

            LyricsRepository.updatePlaybackState(isPlaying)

            if (isPlaying) {
                startTrackingPosition()
                // Fetch lyrics when playback starts
                serviceScope.launch {
                    LyricsRepository.fetchLyricsForCurrentSong()
                }
            } else {
                stopTrackingPosition()
            }
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) {
            super.onMetadataChanged(metadata)
            if (metadata != null) {
                val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
                val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)

                Log.d(TAG, "Metadata changed: $title - $artist (${duration}ms)")

                LyricsRepository.updateNowPlaying(title, artist, duration)

                // Fetch lyrics immediately when metadata changes
                serviceScope.launch {
                    LyricsRepository.fetchLyricsForCurrentSong()
                }
            } else {
                Log.d(TAG, "Metadata is null")
            }
        }

        override fun onSessionDestroyed() {
            super.onSessionDestroyed()
            Log.d(TAG, "Session destroyed")
            mediaController?.unregisterCallback(this)
            mediaController = null
            LyricsRepository.clearNowPlaying()
        }
    }

    private fun startTrackingPosition() {
        positionTrackerJob?.cancel()
        positionTrackerJob = serviceScope.launch {
            while (isActive) {
                mediaController?.playbackState?.let {
                    LyricsRepository.updatePosition(it.position)
                }
                delay(100) // Update every 100ms for smooth tracking
            }
        }
    }

    private fun stopTrackingPosition() {
        positionTrackerJob?.cancel()
    }
}