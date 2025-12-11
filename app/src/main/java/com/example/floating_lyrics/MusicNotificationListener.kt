
package com.example.floating_lyrics

import android.app.Notification
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MusicNotificationListener : NotificationListenerService() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val supportedPackageNames = setOf(
        "com.google.android.apps.youtube.music",
        "app.rvx.android.apps.youtube.music",
        "com.spotify.music",
        "deezer.android.app",
        "com.soundcloud.android"
    )

    companion object {
        private const val TAG = "MusicNotificationListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)

        if (sbn.packageName !in supportedPackageNames) return

        val token = sbn.notification.extras.get(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
        MediaControllerManager.setActiveSession(applicationContext, token)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        super.onNotificationRemoved(sbn)
        if (sbn.packageName in supportedPackageNames) {
            MediaControllerManager.setActiveSession(applicationContext, null)
            LyricsRepository.clearNowPlaying()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Log.d(TAG, "Service destroyed")
    }
}
