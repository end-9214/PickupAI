# PickupAI — Intelligent Real-Time Voice Agent & Android Auto-Dialer

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Python 3.11+](https://img.shields.io/badge/python-3.11+-blue.svg)](https://www.python.org/downloads/)
[![LiveKit Agents](https://img.shields.io/badge/LiveKit-Agents-purple.svg)](https://livekit.io)
[![Django](https://img.shields.io/badge/Django-5.0+-green.svg)](https://www.djangoproject.com/)
[![Android](https://img.shields.io/badge/Android-SDK%2026+-brightgreen.svg)](https://developer.android.com)

**PickupAI** is an autonomous personal telephone assistant ecosystem. It intercepts cellular SIM phone calls directly on an Android device, auto-answers them, and pipes bidirectional real-time audio over WebRTC to an ultra-low-latency voice agent speaking in fluent conversational Hindi (or Hinglish) with your cloned voice.

---

## 🌟 Key Highlights & Capabilities

- ⚡ **Sub-Second Conversational Response**: Measured **~520ms Time-To-First-Token (TTFT)** using local LLM inference on GPU.
- 🗣️ **Cloned Voice Speech**: Uses zero-shot TTS cloning with Hindi phonetic accuracy.
- 🧠 **Dynamic Per-Number Personalities**: Configure custom rules in Django Admin so the agent greets your boss, mom, friends, delivery agents, or telemarketers with customized prompts and behavioral guidelines.
- 📊 **Post-Call LLM Insight Extraction**: Automated management command that transcribes the call, extracts the call motive, caller personality notes, urgency score (`LOW`, `MEDIUM`, `HIGH`, `CRITICAL`), and follow-up action items.
- 📱 **Native Android Integration**: Built with `InCallService` and `ROLE_DIALER` APIs to auto-answer incoming calls without touching standard telephony hardware.

---

## 🏗 System Architecture

```
                    ┌────────────────────────────────────────────────────────┐
                    │               Android Device (Anywhere)                │
                    │                                                        │
                    │  1. Incoming Cellular Call (SIM / GSM)                 │
                    │  2. InCallService (ROLE_DIALER) Auto-Answers Call      │
                    │  3. Authenticates with Django API (Bearer Token)       │
                    │  4. AudioBridge: InCall PCM Stream ◄──► LiveKit WebRTC │
                    └───────────────────────────┬────────────────────────────┘
                                                │
                     Bearer Token Authentication│ Audio Stream (WebRTC Port 7880 / 50000-60000 UDP)
                                                ▼
┌────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 Backend & Cloud Infrastructure                             │
│                                                                                            │
│   ┌───────────────────────────────────┐        ┌───────────────────────────────────────┐   │
│   │ 🐍 Django Backend API (Port 8000) │        │ ⚡ LiveKit Server / Cloud             │   │
│   │  - Dynamic Personality Lookup     │        │  - Low-Latency WebRTC SFU             │   │
│   │  - Call Sessions & Dialogue Logs  │        │  - Audio Track Publishing & Subscribing│  │
│   │  - Django Admin Dashboard         │        └───────────────────┬───────────────────┘   │
│   └─────────────────┬─────────────────┘                            │                       │
│                     │                                              ▼                       │
│                     │                       ┌──────────────────────────────────────────┐   │
│                     │                       │ 🤖 Voice Agent Worker (agent.py)         │   │
│                     │                       │  - STT: Deepgram nova-3 / Whisper Turbo  │   │
│                     │                       │  - Brain: Local Gemma-4 / Llama-3.2      │   │
│                     │                       │  - TTS: Cloned Voice Synthesis           │   │
│                     │                       │  - Noise Enhancement: ai_coustics        │   │
│                     │                       └──────────────────────────────────────────┘   │
│                     ▼                                                                      │
│   ┌────────────────────────────────────────────────────────────────────────────────────┐   │
│   │ 🔍 Post-Call Analytics Engine (`python backend/manage.py process_call_insights`)  │   │
│   │  - Extracts Motive, Urgency, Action Items, and Caller Sentiment Analysis           │   │
│   └────────────────────────────────────────────────────────────────────────────────────┘   │
└────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## ⏱️ Benchmark & Latency Verification

Tested across a 4-turn multi-turn Hindi phone conversation using `gemma4:e4b` on an NVIDIA L40S GPU:

| Turn | 📞 Caller Input (Hindi) | 🤖 Agent Spoken Output (Hindi) | ⏱️ Time-To-First-Token | ⚡ Total Turn Time |
|:---:|:---|:---|:---:|:---:|
| **1** | *"नमस्ते, क्या मैं करमवीर जी से बात कर सकता हूँ?"* | *"जी, मैं करमवीर सिंह का असिस्टेंट हूँ। आप क्या जानना चाहते हैं?"* | **510.6 ms** | **633.7 ms** |
| **2** | *"हाँ, मुझे एक ज़रूरी मीटिंग के बारे में बात करनी थी। क्या वो अभी फ़्री हैं?"* | *"जी, सर अभी एक ज़रूरी कॉल पर हैं। आप थोड़ी देर बाद कोशिश कर सकते हैं।"* | **534.5 ms** | **672.4 ms** |
| **3** | *"मैं बैंक से बात कर रहा हूँ, आपके लिए एक स्पेशल लोन ऑफर है।"* | *"सर, कृपया मीटिंग के लिए अपॉइंटमेंट बुक करने पर ध्यान दें। मैं लोन ऑफर्स की बात नहीं कर सकता।"* | **539.0 ms** | **709.5 ms** |
| **4** | *"ठीक है, उन्हें बोल दीजिएगा कि अमित ने कॉल किया था और मुझे वापस कॉल करें।"* | *"जी, मैं उन्हें बता दूंगा कि अमित ने कॉल किया था। मैं आपको समय मिलते ही वापस कॉल करवा देता हूँ।"* | **501.4 ms** | **674.4 ms** |

- **Average Time-to-First-Token (TTFT)**: **~521 ms**
- **Token Generation Speed**: **~132+ tokens/sec**
- **Result**: Fits within standard human phone response cadences (<800ms total audio delivery).

---

## 🚀 Quick Setup & Installation

### 1. Clone Repository & Setup Environment
```bash
git clone https://github.com/end-9214/PickupAI.git
cd PickupAI

# Copy environment template
cp .env.example .env
```

### 2. Configure Credentials in `.env`
```dotenv
DJANGO_SECRET_KEY=your_generated_secret_key
AGENT_AUTH_TOKEN=your_secure_random_hex_token

LIVEKIT_URL=wss://your-project.livekit.cloud
LIVEKIT_API_KEY=your_livekit_api_key
LIVEKIT_API_SECRET=your_livekit_api_secret

OLLAMA_HOST=http://127.0.0.1:11434
LLM_MODEL=gemma4:e4b
```

### 3. Start Backend & Agent with Docker Compose
```bash
docker compose up --build -d
```

### 4. Create Admin User for the Dashboard
```bash
docker exec -it phone_agent_backend python backend/manage.py createsuperuser
```
Access the Django Admin panel at: **`http://<YOUR_SERVER_IP>:8000/admin/`**

---

## 📱 Android App Setup

1. Open the `/android_app` directory in **Android Studio**.
2. Build and install the debug APK to your Android device (`Build > Build APK(s)`).
3. Open the app on your phone:
   - Enter your Django Server URL (`http://<YOUR_SERVER_IP>:8000`).
   - Enter your `AGENT_AUTH_TOKEN`.
   - Tap **"Save Server Settings"**.
   - Tap **"Set as Default Phone Dialer"** and grant role permissions.

---

## 🛠 Django Management Commands

### Analyze Completed Calls for Insights:
```bash
docker exec -it phone_agent_backend python backend/manage.py process_call_insights
```
This inspects unprocessed call transcripts and fills in:
- Call motive
- Executive summary
- Caller mood & personality assessment
- Urgency status & Action items

---

## 🔒 Security & Privacy Notice
- No voice audio or authentication tokens are committed to this repository.
- Sensitive environment variables are managed via `.env` and kept ignored in version control.

---

## 📄 License
This project is open-source under the [MIT License](LICENSE).
