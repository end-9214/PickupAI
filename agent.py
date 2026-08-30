import os
import json
import logging
import urllib.request
from typing import Dict, Any, Optional
from dotenv import load_dotenv

from livekit import agents
from livekit.agents import (
    Agent,
    AgentServer,
    AgentSession,
    TurnHandlingOptions,
)
try:
    from livekit.plugins import openai
except ImportError:
    openai = None

try:
    from livekit.plugins import silero
except ImportError:
    silero = None


load_dotenv()
logger = logging.getLogger("livekit-sip-agent")

DJANGO_API_BASE = os.getenv("DJANGO_API_BASE", "http://127.0.0.1:8000/api")
AGENT_AUTH_TOKEN = os.getenv("AGENT_AUTH_TOKEN", "")
LIVEKIT_SIP_TRUNK_ID = os.getenv("LIVEKIT_SIP_TRUNK_ID", "ST_LDBvSLZLdKZg")
OLLAMA_HOST = os.getenv("OLLAMA_HOST", "http://127.0.0.1:11434").rstrip("/")
LLM_MODEL = os.getenv("LLM_MODEL", "gemma4:e4b")
VOICE_SAMPLE_WAV = os.getenv("XTTS_SPEAKER_WAV", "/home/ubuntu/phone_pickup_agent/voice/karamveer_sample.wav")

BASE_PROMPT_TEMPLATE = (
    "You are Karamveer Singh and you have to talk like me in Hindi always perfectly. "
    "Always speak in natural, fluent, and polite Hindi in first-person as Karamveer Singh. "
    "Keep replies concise and crisp (1 to 2 short sentences per turn). "
    "{custom_prompt}"
)

FALLBACK_SUBJECT_INSTRUCTION = (
    "Find out who is calling, the purpose of their call, and take note of any important details or messages for me."
)


def construct_agent_prompt(custom_prompt_text: Optional[str] = None) -> str:
    cleaned_instruction = (custom_prompt_text or "").strip()
    if cleaned_instruction:
        subject_directive = f"Your specific objective/subject to discuss in this conversation: {cleaned_instruction}"
    else:
        subject_directive = f"Your specific objective/subject to discuss in this conversation: {FALLBACK_SUBJECT_INSTRUCTION}"
    return BASE_PROMPT_TEMPLATE.format(custom_prompt=subject_directive)


def extract_caller_number(room_name: str, participant_identity: str, participant_attributes: Dict[str, str]) -> str:
    if participant_attributes:
        sip_phone = participant_attributes.get("sip.phoneNumber") or participant_attributes.get("sip.callerId")
        if sip_phone:
            return sip_phone.strip()

    if participant_identity and ("+" in participant_identity or participant_identity.replace("-", "").isdigit()):
        return participant_identity.replace("sip-", "").replace("phone-", "").strip()

    if room_name.startswith("sip-") or room_name.startswith("call-") or room_name.startswith("outbound-"):
        parts = room_name.split("-")
        if len(parts) > 1 and len(parts[1]) >= 7:
            return parts[1]

    return "unknown"


def fetch_caller_profile(caller_number: str) -> Dict[str, Any]:
    url = f"{DJANGO_API_BASE.rstrip('/')}/calls/init/"
    payload_bytes = json.dumps({"caller_number": caller_number}).encode("utf-8")
    http_request = urllib.request.Request(
        url,
        data=payload_bytes,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AGENT_AUTH_TOKEN}",
        },
    )
    try:
        with urllib.request.urlopen(http_request, timeout=5) as http_response:
            return json.loads(http_response.read().decode("utf-8"))
    except Exception as exc:
        logger.warning(f"Backend profile lookup failed ({exc}). Using fallback persona.")
        return {
            "session_id": None,
            "system_prompt": construct_agent_prompt(None),
            "preferred_language": "Hindi",
            "contact_name": "Caller",
            "is_blocked": False,
        }


def report_call_completion(session_id: str, duration_seconds: int, transcript: list) -> None:
    if not session_id:
        return
    url = f"{DJANGO_API_BASE.rstrip('/')}/calls/finish/"
    payload_bytes = json.dumps({
        "session_id": session_id,
        "duration_seconds": duration_seconds,
        "dialogue_transcript": transcript,
    }).encode("utf-8")

    http_request = urllib.request.Request(
        url,
        data=payload_bytes,
        headers={
            "Content-Type": "application/json",
            "Authorization": f"Bearer {AGENT_AUTH_TOKEN}",
        },
    )
    try:
        with urllib.request.urlopen(http_request, timeout=5):
            pass
    except Exception as exc:
        logger.warning(f"Failed to post call completion data: {exc}")


class PhoneAssistant(Agent):
    def __init__(self, custom_instructions: str = None) -> None:
        final_prompt = custom_instructions or construct_agent_prompt(None)
        super().__init__(instructions=final_prompt)


server = AgentServer()


@server.rtc_session(agent_name="phone-pickup-agent")
async def phone_pickup_agent(ctx: agents.JobContext):
    remote_participants = list(ctx.room.remote_participants.values())
    primary_participant = remote_participants[0] if remote_participants else None

    caller_identity = primary_participant.identity if primary_participant else ""
    caller_attributes = primary_participant.attributes if primary_participant else {}

    # Check for outbound dispatch metadata
    dispatch_metadata = {}
    if hasattr(ctx, "job") and getattr(ctx.job, "metadata", None):
        try:
            dispatch_metadata = json.loads(ctx.job.metadata)
        except Exception:
            pass

    is_outbound = dispatch_metadata.get("is_outbound", False) or ctx.room.name.startswith("outbound-")
    custom_task_prompt = dispatch_metadata.get("custom_prompt")

    caller_number = dispatch_metadata.get("phone_number") or extract_caller_number(
        room_name=ctx.room.name,
        participant_identity=caller_identity,
        participant_attributes=caller_attributes,
    )

    caller_profile = fetch_caller_profile(caller_number)

    if caller_profile.get("is_blocked") and not is_outbound:
        logger.info(f"Caller {caller_number} is blocked. Disconnecting.")
        await ctx.room.disconnect()
        return

    raw_custom_prompt = custom_task_prompt or caller_profile.get("custom_system_prompt")
    active_prompt = construct_agent_prompt(raw_custom_prompt)
    preferred_lang = caller_profile.get("preferred_language", "Hindi")
    contact_name = dispatch_metadata.get("contact_name") or caller_profile.get("contact_name", "")

    # Local Ollama Gemma-4 LLM & Silero Local VAD
    local_llm = None
    if openai is not None:
        try:
            local_llm = openai.LLM.with_ollama(
                model=LLM_MODEL,
                base_url=f"{OLLAMA_HOST}/v1"
            )
        except Exception:
            local_llm = None

    turn_options = TurnHandlingOptions()
    if silero is not None:
        try:
            turn_options = TurnHandlingOptions(turn_detection=silero.VAD.load())
        except Exception:
            turn_options = TurnHandlingOptions()

    session_kwargs = {"turn_handling": turn_options}
    if local_llm is not None:
        session_kwargs["llm"] = local_llm

    session = AgentSession(**session_kwargs)


    await session.start(
        room=ctx.room,
        agent=PhoneAssistant(custom_instructions=active_prompt),
    )

    if is_outbound:
        greeting_instruction = (
            f"Greet the person politely in natural Hindi as Karamveer Singh: 'नमस्ते! मैं करमवीर सिंह बोल रहा हूँ।' "
            f"Then state what you wanted to ask: {raw_custom_prompt or 'मैं एक जरूरी बात के लिए कॉल कर रहा हूँ।'}"
        )
    elif contact_name and contact_name != "Unknown Caller":
        greeting_instruction = f"Greet {contact_name} warmly in {preferred_lang} as Karamveer Singh: 'नमस्ते {contact_name}! बताइए, क्या बात है?'"
    else:
        greeting_instruction = f"Greet the caller warmly in natural {preferred_lang} as Karamveer Singh: 'नमस्ते! मैं करमवीर सिंह बोल रहा हूँ। बताइए, मैं आपकी क्या मदद कर सकता हूँ?'"

    await session.generate_reply(instructions=greeting_instruction)


if __name__ == "__main__":
    agents.cli.run_app(server)
