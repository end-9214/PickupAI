package com.phoneagent

import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val REQUEST_DIALER_ROLE = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val editBackendUrl = findViewById<EditText>(R.id.editBackendUrl)
        val editAuthToken = findViewById<EditText>(R.id.editAuthToken)
        val btnSave = findViewById<Button>(R.id.btnSave)
        val btnSetDialer = findViewById<Button>(R.id.btnSetDialer)
        val txtDialerStatus = findViewById<TextView>(R.id.txtDialerStatus)
        val txtStatusBadge = findViewById<TextView>(R.id.txtStatusBadge)

        val prefs = getSharedPreferences("phone_agent_prefs", MODE_PRIVATE)
        val defaultUrl = prefs.getString("backend_url", "http://YOUR_SERVER_IP:8000")
        val defaultToken = prefs.getString("auth_token", "")

        editBackendUrl.setText(defaultUrl)
        editAuthToken.setText(defaultToken)

        btnSave.setOnClickListener {
            val url = editBackendUrl.text.toString().trim()
            val token = editAuthToken.text.toString().trim()

            if (url.isEmpty() || token.isEmpty()) {
                Toast.makeText(this, "Please enter both Server URL and Auth Token", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            prefs.edit()
                .putString("backend_url", url)
                .putString("auth_token", token)
                .apply()
            Toast.makeText(this, "Settings Saved!", Toast.LENGTH_SHORT).show()
        }

        btnSetDialer.setOnClickListener {
            requestDialerRole()
        }

        updateRoleStatus(txtDialerStatus, txtStatusBadge)
    }

    override fun onResume() {
        super.onResume()
        val txtDialerStatus = findViewById<TextView>(R.id.txtDialerStatus)
        val txtStatusBadge = findViewById<TextView>(R.id.txtStatusBadge)
        updateRoleStatus(txtDialerStatus, txtStatusBadge)
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, REQUEST_DIALER_ROLE)
                } else {
                    Toast.makeText(this, "App is already active default dialer", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun updateRoleStatus(statusView: TextView, badgeView: TextView) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            val isDefault = roleManager?.isRoleHeld(RoleManager.ROLE_DIALER) ?: false
            if (isDefault) {
                statusView.text = "Active Default Dialer (Calls intercepted & handled automatically)"
                statusView.setTextColor(resources.getColor(R.color.text_primary))
                badgeView.text = "ACTIVE"
                badgeView.setBackgroundResource(R.drawable.bg_status_badge)
            } else {
                statusView.text = "Permission Required — Tap below to grant default dialer role"
                statusView.setTextColor(resources.getColor(R.color.status_amber_text))
                badgeView.text = "SETUP REQUIRED"
                badgeView.setBackgroundColor(Color.parseColor("#332408"))
            }
        }
    }
}
