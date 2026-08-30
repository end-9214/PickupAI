package com.phoneagent

import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.VideoProfile
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

class CallService : InCallService() {

    private val scope = CoroutineScope(Dispatchers.IO)
    private val httpClient = OkHttpClient()
    private var audioBridge: AudioBridge? = null
    private var isBridgeInitiated = false

    companion object {
        private const val TAG = "CallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        isBridgeInitiated = false
        val callerHandle = call.details.handle?.schemeSpecificPart ?: "Unknown"
        Log.i(TAG, "Incoming cellular call detected from $callerHandle. Initial State: ${call.state}")

        val prefs = getSharedPreferences("phone_agent_prefs", MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "http://YOUR_SERVER_IP:8000") ?: ""
        val authToken = prefs.getString("auth_token", "") ?: ""

        // If the call is already in RINGING state when onCallAdded fires
        if (call.state == Call.STATE_RINGING) {
            Log.i(TAG, "Call is immediately in STATE_RINGING. Answering now...")
            try {
                call.answer(VideoProfile.STATE_AUDIO_ONLY)
            } catch (e: Exception) {
                Log.e(TAG, "Error answering call in onCallAdded", e)
            }
        } else if (call.state == Call.STATE_ACTIVE) {
            if (!isBridgeInitiated) {
                isBridgeInitiated = true
                Log.i(TAG, "Call is already active in onCallAdded! Switching route to SPEAKER and initializing bridge...")
                setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                initiateVoiceBridge(callerHandle, backendUrl, authToken)
            }
        }

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(activeCall: Call, state: Int) {
                super.onStateChanged(activeCall, state)
                Log.i(TAG, "Call state changed to: $state")
                when (state) {
                    Call.STATE_RINGING -> {
                        Log.i(TAG, "Call is ringing from $callerHandle. Auto-answering...")
                        try {
                            activeCall.answer(VideoProfile.STATE_AUDIO_ONLY)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error answering call in onStateChanged", e)
                        }
                    }
                    Call.STATE_ACTIVE -> {
                        if (!isBridgeInitiated) {
                            isBridgeInitiated = true
                            Log.i(TAG, "Call connected! Switching telephony audio route to ROUTE_SPEAKER (Main Loudspeaker)...")
                            // Switch Android telephony audio route to Main Loudspeaker
                            setAudioRoute(CallAudioState.ROUTE_SPEAKER)
                            initiateVoiceBridge(callerHandle, backendUrl, authToken)
                        }
                    }
                    Call.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Call disconnected. Stopping audio bridge.")
                        isBridgeInitiated = false
                        audioBridge?.stop()
                        audioBridge = null
                    }
                }
            }
        })
    }

    private fun initiateVoiceBridge(callerNumber: String, backendUrl: String, authToken: String) {
        scope.launch {
            try {
                // Ensure speaker route is enforced
                setAudioRoute(CallAudioState.ROUTE_SPEAKER)

                val cleanUrl = backendUrl.trim().removeSuffix("/").removeSuffix("/api")
                val targetEndpoint = "$cleanUrl/api/calls/init/"
                Log.i(TAG, "Requesting call session from: $targetEndpoint for caller: $callerNumber")

                val jsonBody = JSONObject().apply {
                    put("caller_number", callerNumber)
                }
                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(targetEndpoint)
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                val responseStr = response.body?.string() ?: "{}"
                Log.i(TAG, "Call init response: ${response.code} body: $responseStr")

                if (response.isSuccessful) {
                    val respJson = JSONObject(responseStr)
                    val livekitUrl = respJson.optString("livekit_url", "")
                    val livekitToken = respJson.optString("livekit_token", "")
                    val sessionId = respJson.optString("session_id", "call-$callerNumber")

                    Log.i(TAG, "Obtained session $sessionId with token length ${livekitToken.length}. Connecting AudioBridge...")
                    audioBridge = AudioBridge(applicationContext)
                    audioBridge?.start(livekitUrl, livekitToken)
                } else {
                    Log.e(TAG, "Backend rejected call init request: code ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error initiating voice bridge", e)
            }
        }
    }

    override fun onCallRemoved(call: Call) {
        super.onCallRemoved(call)
        audioBridge?.stop()
        audioBridge = null
    }
}
