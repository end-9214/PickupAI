package com.phoneagent

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import io.livekit.android.AudioOptions
import io.livekit.android.LiveKit
import io.livekit.android.LiveKitOverrides
import io.livekit.android.RoomOptions
import io.livekit.android.audio.AudioHandler
import io.livekit.android.events.RoomEvent
import io.livekit.android.room.Room
import io.livekit.android.room.track.RemoteAudioTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class AudioBridge(private val context: Context) {

    private var room: Room? = null
    private var isRunning = false
    private var eventJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "AudioBridge"
    }

    /**
     * Custom AudioHandler that prevents LiveKit from reverting audio routing to the earpiece.
     */
    private class BridgeAudioHandler : AudioHandler {
        override fun start() {
            Log.d(TAG, "BridgeAudioHandler: start")
        }
        override fun stop() {
            Log.d(TAG, "BridgeAudioHandler: stop")
        }
    }

    fun start(livekitUrl: String, token: String) {
        if (isRunning) return
        isRunning = true

        scope.launch {
            try {
                Log.i(TAG, "=== STARTING SPEAKERPHONE AUDIO BRIDGE ===")

                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

                // Step 1: Set Communication Mode
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION

                // Step 2: Route audio to Main Loudspeaker (TYPE_BUILTIN_SPEAKER)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val devices = audioManager.availableCommunicationDevices
                    val speaker = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                    if (speaker != null) {
                        val success = audioManager.setCommunicationDevice(speaker)
                        Log.i(TAG, "setCommunicationDevice(TYPE_BUILTIN_SPEAKER) result: $success")
                    } else {
                        @Suppress("DEPRECATION")
                        audioManager.isSpeakerphoneOn = true
                        Log.i(TAG, "Speaker device not in list, set isSpeakerphoneOn=true")
                    }
                } else {
                    @Suppress("DEPRECATION")
                    audioManager.isSpeakerphoneOn = true
                    Log.i(TAG, "Pre-Android 12: isSpeakerphoneOn=true")
                }

                // Maximize stream volume for voice call & music so speech is clearly audible
                try {
                    val maxCallVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL)
                    audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, (maxCallVol * 0.9).toInt(), 0)
                    val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, (maxMusicVol * 0.9).toInt(), 0)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not adjust stream volume: ${e.message}")
                }

                // Step 3: Create LiveKit Room with custom AudioHandler
                val overrides = LiveKitOverrides(
                    audioOptions = AudioOptions(
                        audioHandler = BridgeAudioHandler()
                    )
                )

                val livekitRoom = LiveKit.create(
                    appContext = context,
                    options = RoomOptions(),
                    overrides = overrides
                )
                room = livekitRoom

                // Step 4: Connect to Room
                Log.i(TAG, "Connecting to LiveKit room at $livekitUrl...")
                livekitRoom.connect(livekitUrl, token)
                Log.i(TAG, "Connected to LiveKit room: ${livekitRoom.name}")

                // Step 5: Enable mic to capture caller audio from loudspeaker
                livekitRoom.localParticipant.setMicrophoneEnabled(true)
                Log.i(TAG, "Microphone enabled for LiveKit WebRTC")

                // Step 6: Listen for events
                eventJob = scope.launch {
                    livekitRoom.events.events.collectLatest { event ->
                        when (event) {
                            is RoomEvent.TrackSubscribed -> {
                                val track = event.track
                                if (track is RemoteAudioTrack) {
                                    Log.i(TAG, "Subscribed to remote agent audio track: ${track.name}")
                                }
                            }
                            is RoomEvent.TrackUnsubscribed -> {
                                Log.i(TAG, "Remote track unsubscribed")
                            }
                            is RoomEvent.Disconnected -> {
                                Log.i(TAG, "LiveKit room disconnected")
                            }
                            else -> {}
                        }
                    }
                }

                Log.i(TAG, "=== MAIN SPEAKER AUDIO BRIDGE READY ===")

            } catch (e: Exception) {
                Log.e(TAG, "Failed to start AudioBridge", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        eventJob?.cancel()
        eventJob = null

        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        try {
            room?.disconnect()
            room?.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting room", e)
        }
        room = null

        // Restore audio routing
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                audioManager.isSpeakerphoneOn = false
            }
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            Log.e(TAG, "Error resetting audio routing", e)
        }

        Log.i(TAG, "AudioBridge stopped and audio settings restored.")
    }
}
