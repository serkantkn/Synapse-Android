package com.serkantkn.synapse.data.service

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Follows whatever app is playing media on the phone and reports it to the Mac (MEDIA_STATE),
 * and carries out the Mac's playback commands (MEDIA_COMMAND).
 *
 * Reading other apps' media sessions needs Notification Access (our NotificationListener);
 * without it this stays silent.
 */
class MediaSessionBridge(
    private val context: Context,
    private val onState: (Map<String, Any?>) -> Unit,
) {
    companion object {
        private const val TAG = "MediaBridge"
        private const val ART_SIZE = 128
    }

    private val handler = Handler(Looper.getMainLooper())
    private val sessionManager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
    private val listenerComponent = ComponentName(context, SynapseNotificationListener::class.java)

    private var controller: MediaController? = null
    private var lastSentKey: String? = null
    private var lastArtKey: String? = null
    private var running = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        selectController(list.orEmpty())
    }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = publish()
        override fun onPlaybackStateChanged(state: PlaybackState?) = publish()
        override fun onSessionDestroyed() {
            selectController(activeSessions())
        }
    }

    fun start() {
        if (running) return
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            running = true
            lastSentKey = null
            lastArtKey = null
            selectController(activeSessions())
        } catch (e: SecurityException) {
            Log.w(TAG, "Notification access not granted; media control unavailable")
        }
    }

    fun stop() {
        if (!running) return
        running = false
        sessionManager.removeOnActiveSessionsChangedListener(sessionsListener)
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    /** Re-sends the current state, art included (e.g. after the Mac asks for it). */
    fun resend() {
        lastSentKey = null
        lastArtKey = null
        publish()
    }

    fun handleCommand(action: String) {
        val c = controller
        when (action) {
            "PLAY_PAUSE" -> {
                if (c == null) return
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            }
            "PLAY" -> c?.transportControls?.play()
            "PAUSE" -> c?.transportControls?.pause()
            "NEXT" -> c?.transportControls?.skipToNext()
            "PREVIOUS" -> c?.transportControls?.skipToPrevious()
            "VOLUME_UP", "VOLUME_DOWN" -> {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val dir = if (action == "VOLUME_UP") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, 0)
                publish(force = true)
            }
            else -> Log.w(TAG, "Unknown media command: $action")
        }
    }

    private fun activeSessions(): List<MediaController> = try {
        sessionManager.getActiveSessions(listenerComponent)
    } catch (e: SecurityException) {
        emptyList()
    }

    private fun selectController(list: List<MediaController>) {
        // Prefer a session that is actually playing; otherwise the most recent one.
        val next = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull()
        if (next?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(controllerCallback)
            controller = next
            next?.registerCallback(controllerCallback, handler)
        }
        publish()
    }

    private fun publish(force: Boolean = false) {
        if (!running) return
        val c = controller
        val metadata = c?.metadata
        val title = metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val artist = (metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val packageName = c?.packageName.orEmpty()
        val hasSession = c != null && (title.isNotEmpty() || artist.isNotEmpty())

        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val volume = am.getStreamVolume(AudioManager.STREAM_MUSIC)
        val maxVolume = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)

        val key = "$hasSession|$packageName|$title|$artist|$playing|$volume"
        if (!force && key == lastSentKey) return
        lastSentKey = key

        val payload = mutableMapOf<String, Any?>(
            "has_session" to hasSession,
            "title" to title,
            "artist" to artist,
            "album" to metadata?.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty(),
            "app_name" to appLabel(packageName),
            "package_name" to packageName,
            "is_playing" to playing,
            "volume" to volume,
            "max_volume" to maxVolume,
        )
        // Art only travels when the track changes; the Mac keeps the last one otherwise.
        // The Mac drops it when the session goes away, so the same track coming back needs it again.
        if (!hasSession) lastArtKey = null
        val artKey = "$packageName|$title|$artist"
        if (hasSession && artKey != lastArtKey) {
            lastArtKey = artKey
            payload["art"] = encodeArt(metadata)
        }
        onState(payload)
    }

    private fun appLabel(packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(packageName, 0)).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun encodeArt(metadata: MediaMetadata?): String {
        val bitmap = metadata?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            ?: return ""
        return try {
            val scaled = Bitmap.createScaledBitmap(bitmap, ART_SIZE, ART_SIZE, true)
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 80, out)
            Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            ""
        }
    }
}
