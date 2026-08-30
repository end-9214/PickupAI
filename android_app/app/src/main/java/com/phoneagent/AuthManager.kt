package com.phoneagent

import android.content.Context
import android.content.SharedPreferences

class AuthManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        private const val PREFS_NAME = "pickup_ai_secure_auth_prefs"
        private const val KEY_ACCESS_TOKEN = "jwt_access_token"
        private const val KEY_REFRESH_TOKEN = "jwt_refresh_token"
        private const val KEY_USERNAME = "auth_username"
        private const val KEY_IS_STAFF = "auth_is_staff"

        @Volatile
        private var instance: AuthManager? = null

        fun getInstance(context: Context): AuthManager {
            return instance ?: synchronized(this) {
                instance ?: AuthManager(context.applicationContext).also { instance = it }
            }
        }
    }

    fun saveTokens(accessToken: String, refreshToken: String, username: String, isStaff: Boolean = true) {
        prefs.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putString(KEY_REFRESH_TOKEN, refreshToken)
            .putString(KEY_USERNAME, username)
            .putBoolean(KEY_IS_STAFF, isStaff)
            .apply()
    }

    fun updateAccessToken(accessToken: String) {
        prefs.edit().putString(KEY_ACCESS_TOKEN, accessToken).apply()
    }

    fun getAccessToken(): String? {
        return prefs.getString(KEY_ACCESS_TOKEN, null)
    }

    fun getRefreshToken(): String? {
        return prefs.getString(KEY_REFRESH_TOKEN, null)
    }

    fun getUsername(): String {
        return prefs.getString(KEY_USERNAME, "Admin") ?: "Admin"
    }

    fun isLoggedIn(): Boolean {
        val token = getAccessToken()
        return !token.isNullOrBlank()
    }

    fun clearSession() {
        prefs.edit().clear().apply()
    }
}
