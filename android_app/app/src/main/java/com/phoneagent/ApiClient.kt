package com.phoneagent

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ApiClient(private val context: Context) {

    private val authManager = AuthManager.getInstance(context)
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val baseUrl: String
        get() = BuildConfig.BACKEND_URL.trim().removeSuffix("/")

    suspend fun login(username: String, password: String):Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val payload = JSONObject().apply {
                put("username", username)
                put("password", password)
            }
            val request = Request.Builder()
                .url("$baseUrl/auth/login/")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                val json = JSONObject(bodyString)
                val access = json.optString("access_token", "")
                val refresh = json.optString("refresh_token", "")
                val userObj = json.optJSONObject("user")
                val uname = userObj?.optString("username", username) ?: username
                val isStaff = userObj?.optBoolean("is_staff", true) ?: true

                authManager.saveTokens(access, refresh, uname, isStaff)
                Result.success(json)
            } else {
                val errorMsg = try {
                    val errJson = JSONObject(bodyString)
                    errJson.optString("detail", errJson.optString("non_field_errors", "Authentication failed"))
                } catch (e: Exception) {
                    "Login error: HTTP ${response.code}"
                }
                Result.failure(Exception(errorMsg))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchDashboardSummary(): Result<JSONObject> = withContext(Dispatchers.IO) {
        authenticatedGetRequest("$baseUrl/telephony/dashboard/")
    }

    suspend fun fetchSipTrunkStatus(): Result<JSONObject> = withContext(Dispatchers.IO) {
        authenticatedGetRequest("$baseUrl/telephony/sip-trunk/")
    }

    suspend fun fetchInsights(): Result<JSONArray> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken() ?: ""
            val request = Request.Builder()
                .url("$baseUrl/insights/")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "[]"
            if (response.isSuccessful) {
                Result.success(JSONArray(bodyString))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun fetchPersonalities(): Result<JSONArray> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken() ?: ""
            val request = Request.Builder()
                .url("$baseUrl/personalities/")
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "[]"
            if (response.isSuccessful) {
                Result.success(JSONArray(bodyString))
            } else {
                Result.failure(Exception("HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun savePersonality(
        id: Int?,
        phoneNumber: String,
        contactName: String,
        relationship: String,
        prompt: String,
        language: String,
        isVip: Boolean,
        isBlocked: Boolean
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken() ?: ""
            val payload = JSONObject().apply {
                put("phone_number", phoneNumber)
                put("contact_name", contactName)
                put("relationship", relationship)
                put("custom_system_prompt", prompt)
                put("preferred_language", language)
                put("is_vip", isVip)
                put("is_blocked", isBlocked)
            }

            val requestBuilder = Request.Builder()
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")

            val request = if (id != null && id > 0) {
                requestBuilder.url("$baseUrl/personalities/$id/")
                    .put(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            } else {
                requestBuilder.url("$baseUrl/personalities/")
                    .post(payload.toString().toRequestBody(jsonMediaType))
                    .build()
            }

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                Result.success(JSONObject(bodyString))
            } else {
                Result.failure(Exception("Failed to save: HTTP ${response.code} ($bodyString)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deletePersonality(id: Int): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken() ?: ""
            val request = Request.Builder()
                .url("$baseUrl/personalities/$id/")
                .addHeader("Authorization", "Bearer $token")
                .delete()
                .build()

            val response = httpClient.newCall(request).execute()
            if (response.isSuccessful || response.code == 204) {
                Result.success(true)
            } else {
                Result.failure(Exception("Delete failed: HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun initiateOutboundCall(
        phoneNumber: String,
        customPrompt: String,
        contactName: String = ""
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            val token = authManager.getAccessToken() ?: ""
            val payload = JSONObject().apply {
                put("phone_number", phoneNumber)
                put("custom_prompt", customPrompt)
                put("contact_name", contactName)
            }

            val request = Request.Builder()
                .url("$baseUrl/calls/outbound/")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "{}"
            if (response.isSuccessful || response.code == 201) {
                Result.success(JSONObject(bodyString))
            } else {
                Result.failure(Exception("Outbound call initiation failed: HTTP ${response.code} ($bodyString)"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }


    suspend fun sendAssistantMessage(message: String, history: JSONArray = JSONArray()): Result<JSONObject> = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("message", message)
            put("history", history)
        }
        authenticatedPostRequest("$baseUrl/assistant/chat/", payload)
    }

    private fun authenticatedGetRequest(url: String): Result<JSONObject> {
        return try {
            val token = authManager.getAccessToken() ?: ""
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "{}"
            if (response.isSuccessful) {
                Result.success(JSONObject(bodyString))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $bodyString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun authenticatedPostRequest(url: String, payload: JSONObject): Result<JSONObject> {
        return try {
            val token = authManager.getAccessToken() ?: ""
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            val response = httpClient.newCall(request).execute()
            val bodyString = response.body?.string() ?: "{}"
            if (response.isSuccessful || response.code == 201) {
                Result.success(JSONObject(bodyString))
            } else {
                Result.failure(Exception("HTTP ${response.code}: $bodyString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

