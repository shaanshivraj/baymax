package com.phoneai.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.KeyEvent

class BaymaxAccessibilityService : AccessibilityService() {

    private var lastVolumeDownTime: Long = 0

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) {
        // Not used, but required to override
    }

    override fun onInterrupt() {
        // Not used, but required to override
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastVolumeDownTime < 500) {
                    // Double tap detected! Open the chat.
                    val intent = Intent("com.phoneai.app.TOGGLE_CHAT")
                    intent.setPackage(packageName)
                    sendBroadcast(intent)
                    lastVolumeDownTime = 0 // Reset
                    return true // Consume the event so the volume doesn't actually change
                }
                lastVolumeDownTime = currentTime
            }
            // Do not consume single taps, so volume control still works normally
            return false
        }
        return super.onKeyEvent(event)
    }
}
