package com.serkantkn.synapse.data.service

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.IBinder
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream

/**
 * Listens for all incoming notifications on the device and forwards
 * selected ones to the connected Mac via SynapseService.
 *
 * Requires the user to grant "Notification Access" in system settings.
 */
class SynapseNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "NotifListener"
        private const val PREFS_NAME = "notification_filter_prefs"
        private const val KEY_ALLOWED_PACKAGES = "allowed_packages"
        private const val MAX_REPLY_ACTIONS = 50

        /** Reply actions of recent notifications, by notification key (insertion ordered). */
        private val replyActions = object : LinkedHashMap<String, Notification.Action>() {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Notification.Action>?) =
                size > MAX_REPLY_ACTIONS
        }

        /**
         * Answers a notification the way its inline "Reply" box would.
         * Returns false if the notification is gone or has no reply action.
         */
        fun reply(context: Context, key: String, text: String): Boolean {
            val action = synchronized(replyActions) { replyActions[key] } ?: return false
            val inputs = action.remoteInputs ?: return false
            return try {
                val results = Bundle().apply { inputs.forEach { putCharSequence(it.resultKey, text) } }
                val fillIn = Intent()
                RemoteInput.addResultsToIntent(inputs, fillIn, results)
                action.actionIntent.send(context, 0, fillIn)
                synchronized(replyActions) { replyActions.remove(key) } // one reply per notification
                true
            } catch (e: Exception) {
                Log.e(TAG, "Reply failed", e)
                false
            }
        }

        private fun findReplyAction(notification: Notification): Notification.Action? =
            notification.actions?.firstOrNull { action ->
                action.remoteInputs?.any { it.allowFreeFormInput } == true
            }
    }

    private var synapseService: SynapseService? = null
    private var isBound = false

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? SynapseService.SynapseBinder
            synapseService = binder?.getService()
            isBound = true
            // Notification access may have been granted after the session started.
            synapseService?.onNotificationAccessReady()
            Log.d(TAG, "Bound to SynapseService")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            synapseService = null
            isBound = false
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected")
        // Bind to SynapseService
        val intent = Intent(this, SynapseService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.d(TAG, "NotificationListener disconnected")
        if (isBound) {
            try { unbindService(serviceConnection) } catch (_: Exception) {}
            isBound = false
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return

        val packageName = sbn.packageName

        // Skip our own notifications
        if (packageName == applicationContext.packageName) return

        // Skip ongoing/foreground-service notifications
        val notification = sbn.notification ?: return
        if (notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        if (notification.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) return

        // Check filter
        if (!isPackageAllowed(packageName)) return

        // Extract notification data
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""

        // Skip empty notifications
        if (title.isEmpty() && text.isEmpty()) return

        // Get app name
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            packageName
        }

        // Get app icon as Base64
        val iconBase64 = getAppIconBase64(packageName)

        val replyAction = findReplyAction(notification)
        if (replyAction != null) synchronized(replyActions) { replyActions[sbn.key] = replyAction }

        Log.d(TAG, "Forwarding notification from $appName")

        // Send to SynapseService
        synapseService?.sendNotification(
            appName = appName,
            title = title,
            text = text,
            iconBase64 = iconBase64,
            packageName = packageName,
            key = sbn.key,
            canReply = replyAction != null
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        synchronized(replyActions) { replyActions.remove(sbn.key) }
    }

    private fun isPackageAllowed(packageName: String): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val allowed = prefs.getStringSet(KEY_ALLOWED_PACKAGES, null)
        // If no filter is configured, allow all
        return allowed?.contains(packageName) ?: true
    }

    private fun getAppIconBase64(packageName: String): String {
        return try {
            val drawable = packageManager.getApplicationIcon(packageName)
            val bitmap = if (drawable is BitmapDrawable) {
                drawable.bitmap
            } else {
                val b = Bitmap.createBitmap(
                    drawable.intrinsicWidth.coerceAtLeast(1),
                    drawable.intrinsicHeight.coerceAtLeast(1),
                    Bitmap.Config.ARGB_8888
                )
                val canvas = Canvas(b)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                b
            }

            // Scale to 48x48 for performance
            val scaled = Bitmap.createScaledBitmap(bitmap, 48, 48, true)
            val stream = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.PNG, 100, stream)
            Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get icon for $packageName", e)
            ""
        }
    }
}
