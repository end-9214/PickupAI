package com.phoneagent

import android.app.Dialog
import android.app.role.RoleManager
import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private val REQUEST_DIALER_ROLE = 1001

    private lateinit var authManager: AuthManager
    private lateinit var apiClient: ApiClient

    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var layoutOverviewTab: LinearLayout
    private lateinit var layoutBehaviorsTab: LinearLayout
    private lateinit var layoutAnalysisTab: LinearLayout
    private lateinit var layoutSettingsTab: LinearLayout

    private lateinit var tabOverview: MaterialButton
    private lateinit var tabBehaviors: MaterialButton
    private lateinit var tabAnalysis: MaterialButton
    private lateinit var tabSettings: MaterialButton

    private lateinit var containerOverviewInsights: LinearLayout
    private lateinit var containerBehaviorCards: LinearLayout
    private lateinit var containerDetailedAnalysis: LinearLayout

    private lateinit var txtMetricTotalCalls: TextView
    private lateinit var txtMetricActiveRules: TextView
    private lateinit var txtLoggedInUser: TextView
    private lateinit var txtDialerRoleStatus: TextView
    private lateinit var txtSettingsBackendUrl: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager.getInstance(this)
        apiClient = ApiClient(this)

        if (!authManager.isLoggedIn()) {
            launchLoginActivity()
            return
        }

        setContentView(R.layout.activity_main)

        initViews()
        setupTabs()
        setupListeners()
        loadDashboardData()
        checkAndRequestPermissions()
    }

    private fun initViews() {
        swipeRefresh = findViewById(R.id.swipeRefresh)
        layoutOverviewTab = findViewById(R.id.layoutOverviewTab)
        layoutBehaviorsTab = findViewById(R.id.layoutBehaviorsTab)
        layoutAnalysisTab = findViewById(R.id.layoutAnalysisTab)
        layoutSettingsTab = findViewById(R.id.layoutSettingsTab)

        tabOverview = findViewById(R.id.tabOverview)
        tabBehaviors = findViewById(R.id.tabBehaviors)
        tabAnalysis = findViewById(R.id.tabAnalysis)
        tabSettings = findViewById(R.id.tabSettings)

        containerOverviewInsights = findViewById(R.id.containerOverviewInsights)
        containerBehaviorCards = findViewById(R.id.containerBehaviorCards)
        containerDetailedAnalysis = findViewById(R.id.containerDetailedAnalysis)

        txtMetricTotalCalls = findViewById(R.id.txtMetricTotalCalls)
        txtMetricActiveRules = findViewById(R.id.txtMetricActiveRules)
        txtLoggedInUser = findViewById(R.id.txtLoggedInUser)
        txtDialerRoleStatus = findViewById(R.id.txtDialerRoleStatus)
        txtSettingsBackendUrl = findViewById(R.id.txtSettingsBackendUrl)

        val txtSipPhoneDisplay = findViewById<TextView>(R.id.txtSipPhoneDisplay)
        txtSipPhoneDisplay?.text = "Twilio Line: +1 (640) 230-3978"

        txtLoggedInUser.text = "Signed in as @${authManager.getUsername()}"
        txtSettingsBackendUrl.text = "API: ${BuildConfig.BACKEND_URL}"

    }

    private fun setupTabs() {
        val allTabs = listOf(tabOverview, tabBehaviors, tabAnalysis, tabSettings)
        val allLayouts = listOf(layoutOverviewTab, layoutBehaviorsTab, layoutAnalysisTab, layoutSettingsTab)

        fun selectTab(selectedIndex: Int) {
            for (i in allTabs.indices) {
                if (i == selectedIndex) {
                    allTabs[i].setBackgroundColor(getColor(R.color.accent_indigo))
                    allTabs[i].setTextColor(Color.WHITE)
                    allLayouts[i].visibility = View.VISIBLE
                } else {
                    allTabs[i].setBackgroundColor(getColor(R.color.bg_surface_elevated))
                    allTabs[i].setTextColor(getColor(R.color.text_secondary))
                    allLayouts[i].visibility = View.GONE
                }
            }
        }

        tabOverview.setOnClickListener { selectTab(0) }
        tabBehaviors.setOnClickListener { selectTab(1) }
        tabAnalysis.setOnClickListener { selectTab(2) }
        tabSettings.setOnClickListener { selectTab(3) }
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener {
            loadDashboardData()
        }

        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            authManager.clearSession()
            Toast.makeText(this, "Logged out successfully", Toast.LENGTH_SHORT).show()
            launchLoginActivity()
        }

        findViewById<MaterialButton>(R.id.btnOpenOutboundDialer).setOnClickListener {
            showOutboundCallDialog()
        }

        findViewById<MaterialButton>(R.id.btnAddRule).setOnClickListener {
            showEditPersonalityDialog(null)
        }

        findViewById<MaterialButton>(R.id.btnSetDialerRole).setOnClickListener {
            requestDialerRole()
        }

        findViewById<MaterialButton>(R.id.btnRevertDialerRole).setOnClickListener {
            revertDialerRole()
        }
    }

    private fun showOutboundCallDialog() {
        val dialog = Dialog(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_outbound_call, null)
        dialog.setContentView(dialogView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val editPhone = dialogView.findViewById<EditText>(R.id.editOutboundPhone)
        val editName = dialogView.findViewById<EditText>(R.id.editOutboundName)
        val editPrompt = dialogView.findViewById<EditText>(R.id.editOutboundPrompt)
        val btnDelivery = dialogView.findViewById<MaterialButton>(R.id.btnPresetDelivery)
        val btnMeeting = dialogView.findViewById<MaterialButton>(R.id.btnPresetMeeting)
        val btnFollowup = dialogView.findViewById<MaterialButton>(R.id.btnPresetFollowup)
        val btnStartCall = dialogView.findViewById<MaterialButton>(R.id.btnStartOutboundCall)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.btnCancelOutbound)

        btnDelivery.setOnClickListener {
            editPrompt.setText("Ask if my delivery parcel is on the way and confirm the estimated delivery time.")
        }

        btnMeeting.setOnClickListener {
            editPrompt.setText("Confirm if our scheduled meeting is happening today and ask for the time and location.")
        }

        btnFollowup.setOnClickListener {
            editPrompt.setText("Follow up on the pending task status and ask if anything is required from Karamveer.")
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnStartCall.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            val name = editName.text.toString().trim()
            val prompt = editPrompt.text.toString().trim()

            if (phone.isEmpty()) {
                Toast.makeText(this, "Please enter a recipient phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnStartCall.isEnabled = false
            btnStartCall.text = "Initiating..."

            CoroutineScope(Dispatchers.Main).launch {
                val result = apiClient.initiateOutboundCall(
                    phoneNumber = phone,
                    customPrompt = prompt,
                    contactName = name
                )

                btnStartCall.isEnabled = true
                btnStartCall.text = "📞 Dial via SIP"

                result.onSuccess {
                    Toast.makeText(this@MainActivity, "Outbound call dispatched via LiveKit SIP!", Toast.LENGTH_LONG).show()
                    dialog.dismiss()
                    loadDashboardData()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }


    private fun loadDashboardData() {
        swipeRefresh.isRefreshing = true
        CoroutineScope(Dispatchers.Main).launch {
            // Load dashboard summary
            val dashboardResult = apiClient.fetchDashboardSummary()
            dashboardResult.onSuccess { summary ->
                txtMetricTotalCalls.text = summary.optInt("total_calls", 0).toString()
                txtMetricActiveRules.text = summary.optInt("active_personalities_count", 0).toString()

                val sipPhone = summary.optString("sip_phone_number", "+1 (640) 230-3978")
                findViewById<TextView>(R.id.txtSipPhoneDisplay)?.text = "Twilio Line: $sipPhone"

                val latestInsights = summary.optJSONArray("latest_insights") ?: JSONArray()
                renderOverviewInsights(latestInsights)
            }


            // Load full insights
            val insightsResult = apiClient.fetchInsights()
            insightsResult.onSuccess { insights ->
                renderDetailedAnalysis(insights)
            }

            // Load contact personalities
            val personalitiesResult = apiClient.fetchPersonalities()
            personalitiesResult.onSuccess { personalities ->
                renderBehaviorsList(personalities)
                txtMetricActiveRules.text = personalities.length().toString()
            }

            swipeRefresh.isRefreshing = false
        }
    }

    private fun renderOverviewInsights(insights: JSONArray) {
        containerOverviewInsights.removeAllViews()
        if (insights.length() == 0) {
            val emptyTxt = TextView(this).apply {
                text = "No recent call insights yet. Inbound calls via SIP trunk will automatically record and analyze here."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(12, 16, 12, 16)
            }
            containerOverviewInsights.addView(emptyTxt)
            return
        }

        for (i in 0 until insights.length()) {
            val item = insights.optJSONObject(i) ?: continue
            val card = createInsightCardView(item)
            containerOverviewInsights.addView(card)
        }
    }

    private fun renderDetailedAnalysis(insights: JSONArray) {
        containerDetailedAnalysis.removeAllViews()
        if (insights.length() == 0) {
            val emptyTxt = TextView(this).apply {
                text = "No call logs recorded. LiveKit SIP Trunk ST_hTrSXznC7M8r is active and awaiting inbound calls."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(12, 16, 12, 16)
            }
            containerDetailedAnalysis.addView(emptyTxt)
            return
        }

        for (i in 0 until insights.length()) {
            val item = insights.optJSONObject(i) ?: continue
            val card = createDetailedInsightCard(item)
            containerDetailedAnalysis.addView(card)
        }
    }

    private fun renderBehaviorsList(personalities: JSONArray) {
        containerBehaviorCards.removeAllViews()
        if (personalities.length() == 0) {
            val emptyTxt = TextView(this).apply {
                text = "No custom phone rules created yet. Tap '+ Add Rule' above to configure custom prompts and behaviors for specific phone numbers."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(12, 16, 12, 16)
            }
            containerBehaviorCards.addView(emptyTxt)
            return
        }

        for (i in 0 until personalities.length()) {
            val item = personalities.optJSONObject(i) ?: continue
            val card = createBehaviorCardView(item)
            containerBehaviorCards.addView(card)
        }
    }

    private fun createInsightCardView(item: JSONObject): View {
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(32, 28, 32, 28)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 24
            layoutParams = lp
        }

        // Header: Caller & Urgency Badge
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val callerText = TextView(this).apply {
            text = "${item.optString("contact_name", "Unknown")} (${item.optString("caller_number", "Unknown")})"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val urgencyStr = item.optString("urgency_level", "LOW").uppercase()
        val urgencyBadge = TextView(this).apply {
            text = urgencyStr
            textSize = 10f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(16, 6, 16, 6)
            when (urgencyStr) {
                "CRITICAL" -> {
                    setBackgroundResource(R.drawable.bg_badge_critical)
                    setTextColor(getColor(R.color.status_red_text))
                }
                "HIGH" -> {
                    setBackgroundResource(R.drawable.bg_badge_high)
                    setTextColor(getColor(R.color.status_amber_text))
                }
                "MEDIUM" -> {
                    setBackgroundResource(R.drawable.bg_badge_medium)
                    setTextColor(getColor(R.color.status_blue_text))
                }
                else -> {
                    setBackgroundResource(R.drawable.bg_badge_low)
                    setTextColor(getColor(R.color.text_secondary))
                }
            }
        }

        headerLayout.addView(callerText)
        headerLayout.addView(urgencyBadge)
        cardLayout.addView(headerLayout)

        // Call Motive
        val motiveText = TextView(this).apply {
            text = "Motive: ${item.optString("call_motive", "Incoming inquiry")}"
            setTextColor(getColor(R.color.accent_indigo))
            textSize = 13f
            setPadding(0, 8, 0, 4)
        }
        cardLayout.addView(motiveText)

        // Summary
        val summaryText = TextView(this).apply {
            text = item.optString("executive_summary", "")
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }
        cardLayout.addView(summaryText)

        // Action items if any
        val actions = item.optJSONArray("action_items")
        if (actions != null && actions.length() > 0) {
            val actionHeader = TextView(this).apply {
                text = "⚡ ACTION ITEMS FOR YOU:"
                setTextColor(getColor(R.color.status_green_text))
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 4, 0, 4)
            }
            cardLayout.addView(actionHeader)

            for (k in 0 until actions.length()) {
                val actionItem = actions.optString(k, "")
                if (actionItem.isNotBlank()) {
                    val taskText = TextView(this).apply {
                        text = "• $actionItem"
                        setTextColor(getColor(R.color.text_primary))
                        textSize = 12f
                        setPadding(8, 2, 0, 2)
                    }
                    cardLayout.addView(taskText)
                }
            }
        }

        return cardLayout
    }

    private fun createDetailedInsightCard(item: JSONObject): View {
        val card = createInsightCardView(item) as LinearLayout

        // Caller Personality Notes
        val notes = item.optString("caller_personality_notes", "")
        if (notes.isNotBlank()) {
            val notesView = TextView(this).apply {
                text = "Caller Emotional Tone: $notes"
                setTextColor(getColor(R.color.accent_violet))
                textSize = 12f
                setPadding(0, 8, 0, 4)
            }
            card.addView(notesView)
        }

        // Dialogue transcript preview
        val transcript = item.optJSONArray("dialogue_transcript")
        if (transcript != null && transcript.length() > 0) {
            val transcriptHeader = TextView(this).apply {
                text = "💬 TURN-BY-TURN TRANSCRIPT:"
                setTextColor(getColor(R.color.text_tertiary))
                textSize = 10f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(0, 10, 0, 4)
            }
            card.addView(transcriptHeader)

            for (j in 0 until transcript.length()) {
                val turn = transcript.optJSONObject(j) ?: continue
                val speaker = turn.optString("speaker", "caller").capitalize()
                val utterance = turn.optString("text", "")
                val turnText = TextView(this).apply {
                    text = "$speaker: $utterance"
                    setTextColor(if (speaker.lowercase() == "agent") getColor(R.color.accent_indigo) else getColor(R.color.text_primary))
                    textSize = 12f
                    setPadding(12, 4, 0, 4)
                }
                card.addView(turnText)
            }
        }

        return card
    }

    private fun createBehaviorCardView(item: JSONObject): View {
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(32, 28, 32, 28)
            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.bottomMargin = 24
            layoutParams = lp
        }

        // Header: Name, Number & VIP badge
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
        }

        val nameText = TextView(this).apply {
            val isVip = item.optBoolean("is_vip", false)
            val isBlocked = item.optBoolean("is_blocked", false)
            val tag = if (isBlocked) " [BLOCKED]" else if (isVip) " ⭐ [VIP]" else ""
            text = "${item.optString("contact_name", "Contact")} ($tag)"
            setTextColor(if (isBlocked) getColor(R.color.status_red_text) else getColor(R.color.text_primary))
            textSize = 14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }

        val langBadge = TextView(this).apply {
            text = item.optString("preferred_language", "Hindi")
            textSize = 10f
            setTextColor(getColor(R.color.accent_cyan))
            setBackgroundResource(R.drawable.bg_badge_low)
            setPadding(12, 4, 12, 4)
        }

        headerLayout.addView(nameText)
        headerLayout.addView(langBadge)
        cardLayout.addView(headerLayout)

        // Phone Number
        val phoneText = TextView(this).apply {
            text = "Number: ${item.optString("phone_number", "")}"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 4, 0, 4)
        }
        cardLayout.addView(phoneText)

        // Custom Prompt
        val promptText = TextView(this).apply {
            text = "Instructions: \"${item.optString("custom_system_prompt", "")}\""
            setTextColor(getColor(R.color.text_primary))
            textSize = 12f
            setPadding(0, 4, 0, 8)
        }
        cardLayout.addView(promptText)

        // Buttons: Edit & Delete
        val btnRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val btnEdit = MaterialButton(this).apply {
            text = "Edit Rule"
            textSize = 11f
            setTextColor(getColor(R.color.accent_indigo))
            setBackgroundColor(getColor(R.color.bg_surface_elevated))
            cornerRadius = 12
            layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply { marginEnd = 12 }
            setOnClickListener { showEditPersonalityDialog(item) }
        }

        val btnDelete = MaterialButton(this).apply {
            text = "Delete"
            textSize = 11f
            setTextColor(getColor(R.color.status_red_text))
            setBackgroundColor(getColor(R.color.bg_surface_elevated))
            cornerRadius = 12
            layoutParams = LinearLayout.LayoutParams(0, 80, 1f).apply { marginStart = 12 }
            setOnClickListener {
                val ruleId = item.optInt("id", -1)
                if (ruleId > 0) {
                    deletePersonalityRule(ruleId)
                }
            }
        }

        btnRow.addView(btnEdit)
        btnRow.addView(btnDelete)
        cardLayout.addView(btnRow)

        return cardLayout
    }

    private fun showEditPersonalityDialog(existing: JSONObject?) {
        val dialog = Dialog(this)
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_personality, null)
        dialog.setContentView(dialogView)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)

        val editPhone = dialogView.findViewById<EditText>(R.id.dialogEditPhone)
        val editName = dialogView.findViewById<EditText>(R.id.dialogEditName)
        val editPrompt = dialogView.findViewById<EditText>(R.id.dialogEditPrompt)
        val editLang = dialogView.findViewById<EditText>(R.id.dialogEditLanguage)
        val switchVip = dialogView.findViewById<SwitchCompat>(R.id.dialogSwitchVip)
        val switchBlocked = dialogView.findViewById<SwitchCompat>(R.id.dialogSwitchBlocked)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.dialogBtnSave)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.dialogBtnCancel)

        val existingId = existing?.optInt("id", -1)
        if (existing != null) {
            editPhone.setText(existing.optString("phone_number", ""))
            editName.setText(existing.optString("contact_name", ""))
            editPrompt.setText(existing.optString("custom_system_prompt", ""))
            editLang.setText(existing.optString("preferred_language", "Hindi"))
            switchVip.isChecked = existing.optBoolean("is_vip", false)
            switchBlocked.isChecked = existing.optBoolean("is_blocked", false)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            val name = editName.text.toString().trim()
            val prompt = editPrompt.text.toString().trim()
            val lang = editLang.text.toString().trim().ifEmpty { "Hindi" }
            val isVip = switchVip.isChecked
            val isBlocked = switchBlocked.isChecked

            if (phone.isEmpty() || prompt.isEmpty()) {
                Toast.makeText(this, "Phone number and prompt instructions are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnSave.isEnabled = false
            CoroutineScope(Dispatchers.Main).launch {
                val saveResult = apiClient.savePersonality(
                    id = if (existingId != null && existingId > 0) existingId else null,
                    phoneNumber = phone,
                    contactName = name,
                    relationship = "WORK",
                    prompt = prompt,
                    language = lang,
                    isVip = isVip,
                    isBlocked = isBlocked
                )

                btnSave.isEnabled = true
                saveResult.onSuccess {
                    Toast.makeText(this@MainActivity, "Rule saved successfully!", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadDashboardData()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }

    private fun deletePersonalityRule(ruleId: Int) {
        CoroutineScope(Dispatchers.Main).launch {
            val result = apiClient.deletePersonality(ruleId)
            result.onSuccess {
                Toast.makeText(this@MainActivity, "Rule removed", Toast.LENGTH_SHORT).show()
                loadDashboardData()
            }.onFailure { err ->
                Toast.makeText(this@MainActivity, "Delete error: ${err.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchLoginActivity() {
        val intent = Intent(this, LoginActivity::class.java)
        startActivity(intent)
        finish()
    }

    private fun checkAndRequestPermissions() {
        val permissions = mutableListOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.READ_PHONE_STATE,
            android.Manifest.permission.ANSWER_PHONE_CALLS,
            android.Manifest.permission.READ_CALL_LOG,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(android.Manifest.permission.POST_NOTIFICATIONS)
        }

        val needed = permissions.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(this, it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            androidx.core.app.ActivityCompat.requestPermissions(this, needed.toTypedArray(), 2001)
        }
    }

    private fun revertDialerRole() {
        try {
            val settingsIntent = Intent(android.provider.Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            startActivity(settingsIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "Select Default Apps in device settings to revert", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestDialerRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
                    startActivityForResult(intent, REQUEST_DIALER_ROLE)
                    return
                }
            }
        }
        try {
            val telecomIntent = Intent(android.telecom.TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
                putExtra(android.telecom.TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
            }
            startActivity(telecomIntent)
        } catch (e: Exception) {
            revertDialerRole()
        }
    }
}
