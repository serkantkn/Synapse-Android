package com.serkantkn.synapse.data.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.serkantkn.synapse.R

/**
 * "Find my phone": rings loudly on the alarm stream, so it is heard even when the ringer is
 * silent, and vibrates. It stops by itself, from the notification, or when the Mac says STOP.
 */
class FindPhoneController(private val context: Context) {

    companion object {
        private const val TAG = "FindPhone"
        private const val CHANNEL_ID = "synapse_find_phone"
        private const val NOTIFICATION_ID = 1002
        private const val MAX_DURATION_MS = 30_000L
        const val ACTION_STOP = "ACTION_STOP_FIND_PHONE"
    }

    private var player: MediaPlayer? = null
    private var savedAlarmVolume: Int? = null
    private val handler = Handler(Looper.getMainLooper())
    private val autoStop = Runnable { stop() }

    private val audioManager get() = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val vibrator: Vibrator
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    var isRinging = false
        private set

    fun start() {
        if (isRinging) return
        isRinging = true
        Log.d(TAG, "Ringing")
        try {
            val am = audioManager
            savedAlarmVolume = am.getStreamVolume(AudioManager.STREAM_ALARM)
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)

            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(context, uri)
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Could not play alarm sound", e)
            player?.release()
            player = null
            restoreVolume()
        }

        // Without attributes the vibration counts as media and follows the media haptics setting;
        // as an alarm it goes through even when the phone is on silent.
        val pattern = VibrationEffect.createWaveform(longArrayOf(0, 800, 400), 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            vibrator.vibrate(pattern, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }
        showNotification()
        handler.postDelayed(autoStop, MAX_DURATION_MS)
    }

    fun stop() {
        handler.removeCallbacks(autoStop)
        if (!isRinging) return
        isRinging = false
        Log.d(TAG, "Stopped")
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        vibrator.cancel()
        restoreVolume()
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun restoreVolume() {
        savedAlarmVolume?.let { audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it, 0) }
        savedAlarmVolume = null
    }

    private fun showNotification() {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Telefonumu Bul", NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null) // the MediaPlayer makes the sound
            }
        )
        val stopIntent = PendingIntent.getService(
            context, 2,
            Intent(context, SynapseService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Telefonun aranıyor")
            .setContentText("Mac'ten \"Telefonumu Bul\" istendi. Durdurmak için dokun.")
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setContentIntent(stopIntent)
            .setDeleteIntent(stopIntent)
            .addAction(R.mipmap.ic_launcher, "Durdur", stopIntent)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
