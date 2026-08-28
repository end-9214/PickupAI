import json
import logging
import os
import urllib.request
import urllib.error
from django.core.management.base import BaseCommand
from telephony.models import CallSession, CallInsight, UrgencyLevel

logger = logging.getLogger(__name__)


class Command(BaseCommand):
    help = "Processes completed phone calls with local Ollama LLM to extract motive, insights, urgency, and action items."

    def add_arguments(self, parser):
        parser.add_argument(
            "--session-id",
            type=str,
            help="Specific CallSession UUID to process. If omitted, all unprocessed calls will be processed."
        )

    def handle(self, *args, **options):
        session_id = options.get("session_id")
        ollama_host = os.getenv("OLLAMA_HOST", "http://localhost:11434")
        model_name = os.getenv("LLM_MODEL", "qwen3.8-27b-uncensored:latest")

        queryset = CallSession.objects.filter(is_processed=False, status="COMPLETED")
        if session_id:
            queryset = queryset.filter(session_id=session_id)

        count = queryset.count()
        self.stdout.write(self.style.NOTICE(f"Found {count} unprocessed call(s)..."))

        for session in queryset:
            self.stdout.write(f"Analyzing call {session.session_id} from {session.caller_number}...")
            transcript_text = self._format_transcript(session.dialogue_transcript)

            if not transcript_text.strip():
                self.stdout.write(self.style.WARNING(f"Call {session.session_id} has an empty transcript. Generating placeholder insight."))
                CallInsight.objects.create(
                    call_session=session,
                    call_motive="Missed / Blank call",
                    executive_summary="The call was connected but no audible dialogue was recorded.",
                    caller_personality_notes="Not applicable",
                    urgency_level=UrgencyLevel.LOW,
                    action_items=[]
                )
                session.is_processed = True
                session.save(update_fields=["is_processed"])
                continue

            analysis_json = self._query_ollama_for_insights(
                ollama_host=ollama_host,
                model_name=model_name,
                caller_number=session.caller_number,
                duration=session.duration_seconds,
                transcript=transcript_text
            )

            if analysis_json:
                CallInsight.objects.update_or_create(
                    call_session=session,
                    defaults={
                        "call_motive": analysis_json.get("call_motive", "General Conversation")[:255],
                        "executive_summary": analysis_json.get("executive_summary", ""),
                        "caller_personality_notes": analysis_json.get("caller_personality_notes", ""),
                        "urgency_level": analysis_json.get("urgency_level", "LOW"),
                        "action_items": analysis_json.get("action_items", []),
                    }
                )
                session.is_processed = True
                session.save(update_fields=["is_processed"])
                self.stdout.write(self.style.SUCCESS(f"Successfully processed insights for call {session.session_id}!"))
            else:
                self.stdout.write(self.style.ERROR(f"Failed to extract insights for call {session.session_id}."))

    def _format_transcript(self, transcript_list):
        if not isinstance(transcript_list, list):
            return str(transcript_list)
        lines = []
        for item in transcript_list:
            speaker = item.get("speaker", "Unknown").capitalize()
            text = item.get("text", "")
            lines.append(f"{speaker}: {text}")
        return "\n".join(lines)

    def _query_ollama_for_insights(self, ollama_host, model_name, caller_number, duration, transcript):
        system_prompt = (
            "You are an expert conversation analyst. Analyze the following telephone transcript where Karamveer's AI assistant "
            "answered a phone call. Return your response ONLY as valid, raw JSON (no markdown formatting, no codeblocks)."
        )

        user_prompt = f"""
Caller Number: {caller_number}
Call Duration: {duration} seconds
Transcript:
\"\"\"
{transcript}
\"\"\"

Extract the following in strictly valid JSON format:
{{
  "call_motive": "Short 1-sentence motive of why the caller phoned",
  "executive_summary": "Clean 2-3 sentence summary of what was discussed",
  "caller_personality_notes": "Personality traits, mood, and tone of the caller (e.g. Friendly, Rushed, Urgent, Formal)",
  "urgency_level": "LOW" | "MEDIUM" | "HIGH" | "CRITICAL",
  "action_items": ["List of follow-up tasks or notes for Karamveer"]
}}
"""

        payload = {
            "model": model_name,
            "messages": [
                {"role": "system", "content": system_prompt},
                {"role": "user", "content": user_prompt}
            ],
            "stream": False,
            "format": "json"
        }

        req = urllib.request.Request(
            f"{ollama_host}/api/chat",
            data=json.dumps(payload).encode("utf-8"),
            headers={"Content-Type": "application/json"}
        )

        try:
            with urllib.request.urlopen(req, timeout=45) as response:
                result = json.loads(response.read().decode("utf-8"))
                content = result.get("message", {}).get("content", "{}")
                return json.loads(content)
        except Exception as e:
            logger.error(f"Error querying Ollama: {e}")
            # Fallback heuristic if LLM is offline
            return {
                "call_motive": "General Incoming Call",
                "executive_summary": f"Call from {caller_number} lasted {duration} seconds.",
                "caller_personality_notes": "Pending LLM analysis",
                "urgency_level": "MEDIUM",
                "action_items": ["Review call transcript in admin panel"]
            }
