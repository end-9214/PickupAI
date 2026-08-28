import os
import json
import logging
import urllib.request
from datetime import datetime
from dotenv import load_dotenv

from livekit import agents
from livekit.agents import (
    Agent,
    AgentServer,
    AgentSession,
    inference,
    TurnHandlingOptions,
)

load_dotenv()
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("phone-pickup-agent")

DJANGO_API_BASE = os.getenv("DJANGO_API_BASE", "http://127.0.0.1:8000/api")
AGENT_AUTH_TOKEN = os.getenv("AGENT_AUTH_TOKEN", "")


def get_caller_personality(caller_number: str) -> dict:
    """
    Queries Django Backend for dynamic per-number instructions and custom persona.
    """
    url = f"{DJANGO_API_BASE}/calls/init/"
    payload = json.dumps({"caller_number": caller_number}).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AGENT_AUTH_TOKEN}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.loads(response.read().decode("utf-8"))
    except Exception as e:
        logger.warning(f"Could not fetch personality from Django backend ({e}). Using fallback persona.")
        return {
            "session_id": None,
            "system_prompt": (
                "You are Karamveer Singh's personal phone assistant answering his calls. "
                "Speak fluent, natural Hindi. Keep replies strictly to 1 or 2 short sentences. "
                "Find out who is calling and why, and take a clear message."
            ),
            "preferred_language": "Hindi",
        }


def finish_call_session(session_id: str, duration_seconds: int, transcript: list):
    """
    Posts the finished call data back to Django for history and LLM insight extraction.
    """
    if not session_id:
        return
    url = f"{DJANGO_API_BASE}/calls/finish/"
    payload = json.dumps({
        "session_id": session_id,
        "duration_seconds": duration_seconds,
        "dialogue_transcript": transcript,
    }).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=payload,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AGENT_AUTH_TOKEN}",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            logger.info("Successfully synced call completion with Django backend.")
    except Exception as e:
        logger.error(f"Failed to post call finish to Django: {e}")


server = AgentServer()


@server.rtc_session(agent_name="phone-pickup-agent")
async def phone_pickup_agent(ctx: agents.JobContext):
    caller_number = ctx.room.name.replace("call-", "")
    logger.info(f"Incoming call connected for caller: {caller_number}")

    # Fetch dynamic personality from Django database
    personality_data = get_caller_personality(caller_number)
    system_prompt = personality_data.get("system_prompt")
    session_id = personality_data.get("session_id")

    class DynamicAssistant(Agent):
        def __init__(self) -> None:
            super().__init__(instructions=system_prompt)

    session = AgentSession(
        stt=inference.STT(model="deepgram/nova-3", language="multi"),
        llm=inference.LLM(model="google/gemma-4-31b-it", temperature=0.6),
        tts=inference.TTS(model="inworld/inworld-tts-2", voice="Ashley"),
        turn_handling=TurnHandlingOptions(
            turn_detection=inference.TurnDetector(),
        ),
    )

    await session.start(
        room=ctx.room,
        agent=DynamicAssistant(),
    )

    # Initial greeting in Hindi
    await session.generate_reply(
        instructions="Greet the caller warmly in natural Hindi: 'नमस्ते! आप करमवीर सिंह की लाइन पर हैं। बताइए, मैं आपकी क्या मदद कर सकता हूँ?'"
    )


if __name__ == "__main__":
    agents.cli.run_app(server)
