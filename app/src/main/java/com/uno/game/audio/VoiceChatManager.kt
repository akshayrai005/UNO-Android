package com.uno.game.audio

import android.content.Context
import android.util.Log

/**
 * Voice chat stub — WebRTC will be added in a future update.
 * All socket signaling hooks are preserved for when WebRTC is integrated.
 */
class VoiceChatManager(private val context: Context, private val roomCode: String) {
    private val TAG = "VoiceChat"
    private var isMuted = false

    fun initialize() {
        Log.d(TAG, "Voice chat initialized (stub) for room $roomCode")
        // TODO: Integrate WebRTC when library is available
    }

    fun toggleMute(): Boolean {
        isMuted = !isMuted
        Log.d(TAG, "Mute toggled: $isMuted")
        return isMuted
    }

    fun isMuted() = isMuted

    fun release() {
        Log.d(TAG, "Voice chat released")
    }
}
