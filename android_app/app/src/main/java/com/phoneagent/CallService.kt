package com.phoneagent

import android.telecom.Call
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

    companion object {
        private const val TAG = "CallService"
    }

    override fun onCallAdded(call: Call) {
        super.onCallAdded(call)
        Log.i(TAG, "Incoming cellular call detected. State: ${call.state}")

        val prefs = getSharedPreferences("phone_agent_prefs", MODE_PRIVATE)
        val backendUrl = prefs.getString("backend_url", "http://YOUR_SERVER_IP:8000") ?: ""
        val authToken = prefs.getString("auth_token", "") ?: ""

        val callerHandle = call.details.handle?.schemeSpecificPart ?: "Unknown"

        call.registerCallback(object : Call.Callback() {
            override fun onStateChanged(activeCall: Call, state: Int) {
                super.onStateChanged(activeCall, state)
                when (state) {
                    Call.STATE_RINGING -> {
                        Log.i(TAG, "Call is ringing from $callerHandle. Auto-answering...")
                        // Pick up the phone call automatically
                        activeCall.answer(VideoProfile.STATE_AUDIO_ONLY)
                    }
                    Call.STATE_ACTIVE -> {
                        Log.i(TAG, "Call connected! Initializing LiveKit voice bridge...")
                        initiateVoiceBridge(callerHandle, backendUrl, authToken)
                    }
                    Call.STATE_DISCONNECTED -> {
                        Log.i(TAG, "Call disconnected. Stopping audio bridge.")
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
                val jsonBody = JSONObject().apply {
                    put("caller_number", callerNumber)
                }
                val requestBody = jsonBody.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$backendUrl/api/calls/init/")
                    .addHeader("Authorization", "Bearer $authToken")
                    .post(requestBody)
                    .build()

                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    val respJson = JSONObject(response.body?.string() ?: "{}")
                    val livekitUrl = respJson.optString("livekit_url", "")
                    val sessionId = respJson.optString("session_id", "call-$callerNumber")

                    Log.i(TAG, "Obtained call session $sessionId. Connecting AudioBridge...")
                    audioBridge = AudioBridge(applicationContext)
                    audioBridge?.start(livekitUrl, sessionId)
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
