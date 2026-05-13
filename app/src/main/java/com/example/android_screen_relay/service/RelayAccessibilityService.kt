package com.example.android_screen_relay

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent
import android.util.Log

class RelayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("RelayAccessibility", "Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // No-op for now, we just want to perform gestures
    }

    override fun onInterrupt() {
        instance = null
        Log.d("RelayAccessibility", "Service Interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
    }

    fun click(x: Float, y: Float) {
        val path = Path()
        path.moveTo(x, y)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d("RelayAccessibility", "Click at $x, $y")
    }

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long = 300) {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        dispatchGesture(gesture, null, null)
        Log.d("RelayAccessibility", "Swipe from $x1,$y1 to $x2,$y2")
    }

    // Back, Home, Recent
    fun performGlobal(action: Int) {
        performGlobalAction(action)
    }

    companion object {
        var instance: RelayAccessibilityService? = null
    }
}
