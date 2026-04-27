package com.serkantkn.synapse.data.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SynapseAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "SynapseAccessibility"
        
        // Static instance for easy access from SynapseService
        var instance: SynapseAccessibilityService? = null
            private set
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d(TAG, "✅ Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We don't need to process events for remote control, just execute gestures
    }

    override fun onInterrupt() {
        Log.d(TAG, "⚠️ Accessibility Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "🛑 Accessibility Service Destroyed")
    }

    /**
     * Executes a remote input command.
     * @param x The relative X coordinate (0.0 to 1.0)
     * @param y The relative Y coordinate (0.0 to 1.0)
     * @param action The type of action (CLICK, SWIPE_UP, SWIPE_DOWN, SWIPE_LEFT, SWIPE_RIGHT, BACK, HOME, RECENTS)
     */
    fun executeRemoteAction(x: Double, y: Double, action: String) {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val screenHeight = displayMetrics.heightPixels

        val absX = (x * screenWidth).toFloat()
        val absY = (y * screenHeight).toFloat()

        when (action) {
            "CLICK" -> performTap(absX, absY)
            "SWIPE_UP" -> performSwipe(absX, absY, absX, absY - 300)
            "SWIPE_DOWN" -> performSwipe(absX, absY, absX, absY + 300)
            "SWIPE_LEFT" -> performSwipe(absX, absY, absX - 300, absY)
            "SWIPE_RIGHT" -> performSwipe(absX, absY, absX + 300, absY)
            "BACK" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "HOME" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "RECENTS" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            else -> Log.w(TAG, "Unknown action: $action")
        }
    }

    private fun performTap(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                Log.d(TAG, "Tap completed at ($x, $y)")
            }
            override fun onCancelled(gestureDescription: GestureDescription?) {
                Log.w(TAG, "Tap cancelled at ($x, $y)")
            }
        }, null)
    }

    private fun performSwipe(startX: Float, startY: Float, endX: Float, endY: Float) {
        val path = Path()
        path.moveTo(startX, startY)
        path.lineTo(endX, endY)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 300))
            .build()
        
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swipe performed from ($startX, $startY) to ($endX, $endY)")
    }
}
