package com.phoneagent

import android.app.Dialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager
    private lateinit var apiClient: ApiClient

    private lateinit var swipeRefresh: SwipeRefreshLayout

    // 6 Tab Layouts
    private lateinit var layoutOverviewTab: View
    private lateinit var layoutOutboundTab: View
    private lateinit var layoutAgentTab: View
    private lateinit var layoutBehaviorsTab: View
    private lateinit var layoutAnalysisTab: View
    private lateinit var layoutHelpTab: View

    // 6 Tab Buttons
    private lateinit var tabOverview: MaterialButton
    private lateinit var tabOutbound: MaterialButton
    private lateinit var tabAgent: MaterialButton
    private lateinit var tabBehaviors: MaterialButton
    private lateinit var tabAnalysis: MaterialButton
    private lateinit var tabHelp: MaterialButton

    // Overview Containers & Metrics
    private lateinit var containerOverviewInsights: LinearLayout
    private lateinit var containerBehaviorCards: LinearLayout
    private lateinit var containerDetailedAnalysis: LinearLayout
    private lateinit var txtMetricTotalCalls: TextView
    private lateinit var txtMetricActiveRules: TextView
    private lateinit var txtLoggedInUser: TextView
    private lateinit var txtSipTrunkDisplay: TextView
    private lateinit var txtSipPhoneDisplay: TextView

    // Outbound Dialer Controls
    private lateinit var editDirectPhone: EditText
    private lateinit var editDirectName: EditText
    private lateinit var editDirectPrompt: EditText
    private lateinit var btnDirectStartCall: MaterialButton

    // Gemma AI Assistant Chat Controls
    private lateinit var containerChatMessages: LinearLayout
    private lateinit var scrollChat: ScrollView
    private lateinit var editAgentMessage: EditText
    private lateinit var btnSendAgentMessage: MaterialButton
    private val chatHistory = JSONArray()

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
        setupGemmaChat()
        loadDashboardData()
    }

    private fun initViews() {
        swipeRefresh = findViewById(R.id.swipeRefresh)

        layoutOverviewTab = findViewById(R.id.layoutOverviewTab)
        layoutOutboundTab = findViewById(R.id.layoutOutboundTab)
        layoutAgentTab = findViewById(R.id.layoutAgentTab)
        layoutBehaviorsTab = findViewById(R.id.layoutBehaviorsTab)
        layoutAnalysisTab = findViewById(R.id.layoutAnalysisTab)
        layoutHelpTab = findViewById(R.id.layoutHelpTab)

        tabOverview = findViewById(R.id.tabOverview)
        tabOutbound = findViewById(R.id.tabOutbound)
        tabAgent = findViewById(R.id.tabAgent)
        tabBehaviors = findViewById(R.id.tabBehaviors)
        tabAnalysis = findViewById(R.id.tabAnalysis)
        tabHelp = findViewById(R.id.tabHelp)

        containerOverviewInsights = findViewById(R.id.containerOverviewInsights)
        containerBehaviorCards = findViewById(R.id.containerBehaviorCards)
        containerDetailedAnalysis = findViewById(R.id.containerDetailedAnalysis)

        txtMetricTotalCalls = findViewById(R.id.txtMetricTotalCalls)
        txtMetricActiveRules = findViewById(R.id.txtMetricActiveRules)
        txtLoggedInUser = findViewById(R.id.txtLoggedInUser)
        txtSipTrunkDisplay = findViewById(R.id.txtSipTrunkDisplay)
        txtSipPhoneDisplay = findViewById(R.id.txtSipPhoneDisplay)

        // Direct Dialer
        editDirectPhone = findViewById(R.id.editDirectPhone)
        editDirectName = findViewById(R.id.editDirectName)
        editDirectPrompt = findViewById(R.id.editDirectPrompt)
        btnDirectStartCall = findViewById(R.id.btnDirectStartCall)

        // Gemma Assistant Chat
        containerChatMessages = findViewById(R.id.containerChatMessages)
        scrollChat = findViewById(R.id.scrollChat)
        editAgentMessage = findViewById(R.id.editAgentMessage)
        btnSendAgentMessage = findViewById(R.id.btnSendAgentMessage)

        txtLoggedInUser.text = "Signed in as @${authManager.getUsername()}"
        txtSipPhoneDisplay.text = "Twilio Line: +1 (640) 230-3978"
    }

    private fun setupTabs() {
        val allTabs = listOf(tabOverview, tabOutbound, tabAgent, tabBehaviors, tabAnalysis, tabHelp)
        val allLayouts = listOf(layoutOverviewTab, layoutOutboundTab, layoutAgentTab, layoutBehaviorsTab, layoutAnalysisTab, layoutHelpTab)

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
        tabOutbound.setOnClickListener { selectTab(1) }
        tabAgent.setOnClickListener { selectTab(2) }
        tabBehaviors.setOnClickListener { selectTab(3) }
        tabAnalysis.setOnClickListener { selectTab(4) }
        tabHelp.setOnClickListener { selectTab(5) }
    }

    private fun setupListeners() {
        swipeRefresh.setOnRefreshListener {
            loadDashboardData()
        }

        findViewById<MaterialButton>(R.id.btnLogout).setOnClickListener {
            authManager.clearSession()
            Toast.makeText(this, "Signed out successfully", Toast.LENGTH_SHORT).show()
            launchLoginActivity()
        }

        findViewById<MaterialButton>(R.id.btnAddRule).setOnClickListener {
            showEditPersonalityDialog(null)
        }

        // Outbound Preset Chips
        findViewById<MaterialButton>(R.id.btnDirectPresetDelivery).setOnClickListener {
            editDirectPrompt.setText("Ask if my delivery parcel is on the way and confirm the estimated delivery time.")
        }
        findViewById<MaterialButton>(R.id.btnDirectPresetMeeting).setOnClickListener {
            editDirectPrompt.setText("Confirm if our scheduled meeting is happening today and ask for the time and location.")
        }
        findViewById<MaterialButton>(R.id.btnDirectPresetFollowup).setOnClickListener {
            editDirectPrompt.setText("Follow up on the pending task status and ask if anything is required from Karamveer.")
        }

        btnDirectStartCall.setOnClickListener {
            val phone = editDirectPhone.text.toString().trim()
            val prompt = editDirectPrompt.text.toString().trim()
            val name = editDirectName.text.toString().trim()

            if (phone.isEmpty()) {
                Toast.makeText(this, "Please enter a recipient phone number", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            btnDirectStartCall.isEnabled = false
            btnDirectStartCall.text = "Placing Outbound Call..."

            CoroutineScope(Dispatchers.Main).launch {
                val res = apiClient.initiateOutboundCall(phone, prompt, name)
                btnDirectStartCall.isEnabled = true
                btnDirectStartCall.text = "📞 Dial via Twilio SIP Trunk"

                res.onSuccess { receipt ->
                    Toast.makeText(this@MainActivity, "Call Dispatched! Target: $phone", Toast.LENGTH_LONG).show()
                    loadDashboardData()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun setupGemmaChat() {
        // Initial Welcome Message from Gemma
        addChatMessage(
            sender = "🤖 Gemma 4 AI",
            content = "नमस्ते कर्मवीर! मैं आपका Executive AI Assistant हूँ।\n\nमैं सीधे आपके Twilio SIP Trunk और Call Intelligence से जुड़ा हुआ हूँ। आप मुझसे किसी को कॉल करने, नए रूल्स सेट करने, या अपनी पुरानी कॉल्स की समरी जानने के लिए कह सकते हैं!",
            actionReceipt = null,
            isUser = false
        )

        findViewById<MaterialButton>(R.id.btnClearChat).setOnClickListener {
            containerChatMessages.removeAllViews()
            while (chatHistory.length() > 0) {
                chatHistory.remove(0)
            }
            setupGemmaChat()
        }

        // Quick Suggestion Chips
        findViewById<MaterialButton>(R.id.chipSuggestSummary).setOnClickListener {
            sendUserChatMessage("मेरी पिछली कॉल की पूरी समरी और एक्शन आइटम्स बताओ।")
        }
        findViewById<MaterialButton>(R.id.chipSuggestDial).setOnClickListener {
            sendUserChatMessage("Call +919149035089 to ask about delivery package arrival time")
        }
        findViewById<MaterialButton>(R.id.chipSuggestRule).setOnClickListener {
            sendUserChatMessage("Add a VIP rule for Mom with Hindi language and warm polite response.")
        }
        findViewById<MaterialButton>(R.id.chipSuggestUrgent).setOnClickListener {
            sendUserChatMessage("क्या हाल ही में कोई अर्जेंट या हाई-प्रायोरिटी कॉल आई है?")
        }

        btnSendAgentMessage.setOnClickListener {
            val userText = editAgentMessage.text.toString().trim()
            if (userText.isNotEmpty()) {
                editAgentMessage.setText("")
                sendUserChatMessage(userText)
            }
        }
    }

    private fun sendUserChatMessage(messageText: String) {
        addChatMessage("👤 Karamveer", messageText, null, isUser = true)

        val userHistoryItem = JSONObject().apply {
            put("role", "user")
            put("content", messageText)
        }
        chatHistory.put(userHistoryItem)

        // Show thinking indicator
        val thinkingView = addChatMessage("🤖 Gemma 4 AI", "Thinking with Gemma-4 & querying RAG memory...", null, isUser = false)

        CoroutineScope(Dispatchers.Main).launch {
            val result = apiClient.sendAssistantMessage(messageText, chatHistory)
            containerChatMessages.removeView(thinkingView)

            result.onSuccess { responseJson ->
                val replyText = responseJson.optString("reply", "I have processed your request.")
                val executedAction = responseJson.optJSONObject("executed_action")
                var actionLabel: String? = null

                if (executedAction != null) {
                    val actionType = executedAction.optString("type")
                    if (actionType == "outbound_call") {
                        actionLabel = "📞 Call Dispatched to ${executedAction.optString("target")}"
                    } else if (actionType == "save_rule") {
                        actionLabel = "⚙️ Rule Saved for ${executedAction.optString("contact_name")}"
                    }
                }

                addChatMessage("🤖 Gemma 4 AI", replyText, actionLabel, isUser = false)

                val assistantHistoryItem = JSONObject().apply {
                    put("role", "assistant")
                    put("content", replyText)
                }
                chatHistory.put(assistantHistoryItem)

                // Refresh dashboard if an action was executed
                if (executedAction != null) {
                    loadDashboardData()
                }
            }.onFailure { err ->
                addChatMessage("🤖 Gemma 4 AI", "Assistant error: ${err.message}", null, isUser = false)
            }
        }
    }

    private fun addChatMessage(sender: String, content: String, actionReceipt: String?, isUser: Boolean): View {
        val bubbleView = LayoutInflater.from(this).inflate(R.layout.item_chat_message, containerChatMessages, false)
        val txtSender = bubbleView.findViewById<TextView>(R.id.txtChatSender)
        val txtContent = bubbleView.findViewById<TextView>(R.id.txtChatMessageContent)
        val txtAction = bubbleView.findViewById<TextView>(R.id.txtChatActionReceipt)
        val layoutBubble = bubbleView.findViewById<LinearLayout>(R.id.layoutChatBubble)

        txtSender.text = sender
        txtContent.text = content

        if (isUser) {
            txtSender.setTextColor(getColor(R.color.accent_indigo))
            layoutBubble.setBackgroundColor(getColor(R.color.bg_surface_elevated))
        } else {
            txtSender.setTextColor(getColor(R.color.accent_cyan))
            layoutBubble.setBackgroundColor(getColor(R.color.bg_surface))
        }

        if (!actionReceipt.isNullOrEmpty()) {
            txtAction.visibility = View.VISIBLE
            txtAction.text = actionReceipt
        } else {
            txtAction.visibility = View.GONE
        }

        containerChatMessages.addView(bubbleView)
        scrollChat.post { scrollChat.fullScroll(View.FOCUS_DOWN) }
        return bubbleView
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
                txtSipPhoneDisplay.text = "Twilio Line: $sipPhone"

                val trunkId = summary.optString("sip_trunk_id", "ST_LDBvSLZLdKZg")
                txtSipTrunkDisplay.text = "Trunk ID: $trunkId"

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
            val emptyView = TextView(this).apply {
                text = "No recent calls logged yet. Calls will appear here with automated AI summaries."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 12f
                setPadding(0, 16, 0, 16)
            }
            containerOverviewInsights.addView(emptyView)
            return
        }

        for (i in 0 until insights.length()) {
            val insight = insights.optJSONObject(i) ?: continue
            val card = createInsightCardView(insight)
            containerOverviewInsights.addView(card)
        }
    }

    private fun renderDetailedAnalysis(insights: JSONArray) {
        containerDetailedAnalysis.removeAllViews()
        if (insights.length() == 0) {
            val emptyView = TextView(this).apply {
                text = "No analyzed calls available yet. Real-time transcripts and summaries appear here."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, 24, 0, 24)
            }
            containerDetailedAnalysis.addView(emptyView)
            return
        }

        for (i in 0 until insights.length()) {
            val insight = insights.optJSONObject(i) ?: continue
            val card = createInsightCardView(insight)
            containerDetailedAnalysis.addView(card)
        }
    }

    private fun createInsightCardView(insight: JSONObject): View {
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(16, 16, 16, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 14
            }
            layoutParams = params
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 6
            }
            layoutParams = p
        }

        val callerNumber = insight.optString("caller_number", "Unknown Caller")
        val contactName = insight.optString("contact_name", "")
        val titleText = if (contactName.isNotEmpty()) "$contactName ($callerNumber)" else callerNumber

        val txtTitle = TextView(this).apply {
            text = titleText
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        val urgencyLevel = insight.optString("urgency_level", "LOW").uppercase()
        val badge = TextView(this).apply {
            text = urgencyLevel
            textSize = 10f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(8, 3, 8, 3)
            when (urgencyLevel) {
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
                    setTextColor(getColor(R.color.accent_indigo))
                }
                else -> {
                    setBackgroundResource(R.drawable.bg_badge_low)
                    setTextColor(getColor(R.color.text_secondary))
                }
            }
        }

        topRow.addView(txtTitle)
        topRow.addView(badge)
        cardLayout.addView(topRow)

        val summaryText = insight.optString("call_summary", "No summary generated.")
        val txtSummary = TextView(this).apply {
            text = summaryText
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 6)
        }
        cardLayout.addView(txtSummary)

        val actionItems = insight.optString("action_items", "")
        if (actionItems.isNotEmpty()) {
            val txtAction = TextView(this).apply {
                text = "⚡ Action: $actionItems"
                setTextColor(getColor(R.color.accent_cyan))
                textSize = 11f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
            }
            cardLayout.addView(txtAction)
        }

        return cardLayout
    }

    private fun renderBehaviorsList(personalities: JSONArray) {
        containerBehaviorCards.removeAllViews()
        if (personalities.length() == 0) {
            val emptyView = TextView(this).apply {
                text = "No custom per-number rules configured yet. Click '+ Add Rule' to customize how Karamveer's AI talks to specific contacts."
                setTextColor(getColor(R.color.text_secondary))
                textSize = 13f
                setPadding(0, 16, 0, 16)
            }
            containerBehaviorCards.addView(emptyView)
            return
        }

        for (i in 0 until personalities.length()) {
            val item = personalities.optJSONObject(i) ?: continue
            val card = createBehaviorCardView(item)
            containerBehaviorCards.addView(card)
        }
    }

    private fun createBehaviorCardView(personality: JSONObject): View {
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(16, 16, 16, 16)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 12
            }
            layoutParams = params
        }

        val topRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            val p = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 4
            }
            layoutParams = p
        }

        val name = personality.optString("contact_name", "Contact")
        val phone = personality.optString("phone_number", "")
        val isVip = personality.optBoolean("is_vip", false)

        val txtTitle = TextView(this).apply {
            text = "$name ($phone)"
            setTextColor(getColor(R.color.text_primary))
            textSize = 14f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }

        topRow.addView(txtTitle)

        if (isVip) {
            val vipBadge = TextView(this).apply {
                text = "⭐ VIP"
                setTextColor(getColor(R.color.status_amber_text))
                textSize = 10f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setBackgroundResource(R.drawable.bg_badge_high)
                setPadding(8, 2, 8, 2)
            }
            topRow.addView(vipBadge)
        }

        cardLayout.addView(topRow)

        val prompt = personality.optString("custom_system_prompt", "Standard Hindi greeting & message taking.")
        val txtPrompt = TextView(this).apply {
            text = "Instructions: $prompt"
            setTextColor(getColor(R.color.text_secondary))
            textSize = 12f
            setPadding(0, 0, 0, 8)
        }
        cardLayout.addView(txtPrompt)

        val btnEdit = MaterialButton(this).apply {
            text = "Edit Rule"
            setTextColor(getColor(R.color.accent_indigo))
            textSize = 11f
            setBackgroundColor(getColor(R.color.bg_surface_elevated))
            cornerRadius = 6
            setOnClickListener {
                showEditPersonalityDialog(personality)
            }
        }
        cardLayout.addView(btnEdit)

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
        val editLanguage = dialogView.findViewById<EditText>(R.id.dialogEditLanguage)
        val switchVip = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.dialogSwitchVip)
        val switchBlocked = dialogView.findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.dialogSwitchBlocked)
        val btnSave = dialogView.findViewById<MaterialButton>(R.id.dialogBtnSave)
        val btnCancel = dialogView.findViewById<MaterialButton>(R.id.dialogBtnCancel)

        if (existing != null) {
            editPhone.setText(existing.optString("phone_number"))
            editName.setText(existing.optString("contact_name"))
            editPrompt.setText(existing.optString("custom_system_prompt"))
            editLanguage.setText(existing.optString("preferred_language", "Hindi"))
            switchVip.isChecked = existing.optBoolean("is_vip", false)
            switchBlocked.isChecked = existing.optBoolean("is_blocked", false)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSave.setOnClickListener {
            val phone = editPhone.text.toString().trim()
            val name = editName.text.toString().trim()
            val prompt = editPrompt.text.toString().trim()
            val lang = editLanguage.text.toString().trim().ifEmpty { "Hindi" }
            val isVip = switchVip.isChecked
            val isBlocked = switchBlocked.isChecked

            if (phone.isEmpty()) {
                Toast.makeText(this, "Phone number is required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val id = existing?.optInt("id")?.takeIf { it > 0 }
            CoroutineScope(Dispatchers.Main).launch {
                val res = apiClient.savePersonality(
                    id = id,
                    phoneNumber = phone,
                    contactName = name,
                    relationship = "Contact",
                    prompt = prompt,
                    language = lang,
                    isVip = isVip,
                    isBlocked = isBlocked
                )

                res.onSuccess {
                    Toast.makeText(this@MainActivity, "Rule saved successfully", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                    loadDashboardData()
                }.onFailure { err ->
                    Toast.makeText(this@MainActivity, "Error: ${err.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        dialog.show()
    }


    private fun launchLoginActivity() {
        val intent = Intent(this, LoginActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }
}
