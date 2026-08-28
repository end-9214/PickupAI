package com.phoneagent

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import io.livekit.android.LiveKit
import io.livekit.android.room.Room
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class AudioBridge(private val context: Context) {

    private var room: Room? = null
    private var isRunning = false
    private var recordingJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "AudioBridge"
        private const val SAMPLE_RATE = 16000
    }

    fun start(livekitUrl: String, tokenOrRoom: String) {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                Log.i(TAG, "Connecting to LiveKit WebRTC audio room...")
                val livekitRoom = LiveKit.create(context)
                room = livekitRoom
                
                // Configure audio routing for in-call mode
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                Log.i(TAG, "LiveKit room audio session initialized.")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioBridge", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        recordingJob?.cancel()
        recordingJob = null
        try {
            room?.disconnect()
            room?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting room", e)
        }
        room = null
    }
}
