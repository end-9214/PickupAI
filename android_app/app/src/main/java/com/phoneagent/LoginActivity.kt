package com.phoneagent

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LoginActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager
    private lateinit var apiClient: ApiClient

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager.getInstance(this)
        apiClient = ApiClient(this)

        if (authManager.isLoggedIn()) {
            launchMainActivity()
            return
        }

        setContentView(R.layout.activity_login)

        val editUsername = findViewById<EditText>(R.id.editUsername)
        val editPassword = findViewById<EditText>(R.id.editPassword)
        val btnLogin = findViewById<MaterialButton>(R.id.btnLogin)
        val progressLogin = findViewById<ProgressBar>(R.id.progressLogin)
        val txtBackendHostInfo = findViewById<TextView>(R.id.txtBackendHostInfo)

        txtBackendHostInfo.text = "Target Server: ${BuildConfig.BACKEND_URL}"

        btnLogin.setOnClickListener {
            val username = editUsername.text.toString().trim()
            val password = editPassword.text.toString()

            if (username.isEmpty() || password.isEmpty()) {
                Toast.makeText(this, "Please enter your admin username and password", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnLogin.isEnabled = false
            progressLogin.visibility = View.VISIBLE

            CoroutineScope(Dispatchers.Main).launch {
                val loginResult = apiClient.login(username, password)
                btnLogin.isEnabled = true
                progressLogin.visibility = View.GONE

                loginResult.onSuccess {
                    Toast.makeText(this@LoginActivity, "Welcome back, $username!", Toast.LENGTH_SHORT).show()
                    launchMainActivity()
                }.onFailure { err ->
                    Toast.makeText(this@LoginActivity, err.message ?: "Authentication failed", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun launchMainActivity() {
        val intent = Intent(this, MainActivity::class.java)
        startActivity(intent)
        finish()
    }
}
