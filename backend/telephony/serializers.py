import os
from django.utils import timezone
from rest_framework import serializers
from rest_framework.exceptions import AuthenticationFailed, ValidationError

from .models import ContactPersonality, CallSession, CallInsight, CallStatus


class AuthTokenValidationMixin:
    """
    Validates that the incoming request contains the configured AGENT_AUTH_TOKEN.
    """
    def validate_auth(self, request):
        auth_header = request.headers.get("Authorization", "")
        expected_token = os.getenv("AGENT_AUTH_TOKEN", "")

        if not expected_token:
            return True

        if not auth_header.startswith("Bearer "):
            raise AuthenticationFailed("Missing or invalid Authorization Bearer header.")

        token = auth_header.split(" ", 1)[1].strip()
        if token != expected_token:
            raise AuthenticationFailed("Unauthorized: Invalid agent auth token.")
        return True


class ContactPersonalitySerializer(serializers.ModelSerializer):
    class Meta:
        model = ContactPersonality
        fields = [
            "id",
            "phone_number",
            "contact_name",
            "relationship",
            "custom_system_prompt",
            "preferred_language",
            "is_vip",
            "is_blocked",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["id", "created_at", "updated_at"]

    def validate_phone_number(self, value):
        cleaned = value.strip().replace(" ", "").replace("-", "")
        if len(cleaned) < 5:
            raise ValidationError("Phone number must contain at least 5 digits.")
        return cleaned


class CallSessionInitSerializer(serializers.Serializer):
    """
    Called when an incoming call arrives to get the caller personality & LiveKit room credentials.
    """
    caller_number = serializers.CharField(max_length=32)
    session_id = serializers.UUIDField(required=False)

    def create(self, validated_data):
        caller_number = validated_data["caller_number"].strip().replace(" ", "")
        
        # Match personality profile
        personality = ContactPersonality.objects.filter(
            phone_number__endswith=caller_number[-10:] if len(caller_number) >= 10 else caller_number
        ).first()

        session = CallSession.objects.create(
            caller_number=caller_number,
            contact_personality=personality,
            status=CallStatus.RINGING,
            started_at=timezone.now(),
        )

        # Fallback default instructions if no specific contact personality exists
        default_instructions = (
            "You are Karamveer Singh's personal phone assistant answering his calls. "
            "Be warm, polite, and strictly keep responses to 1 or 2 short sentences in natural Hindi. "
            "Find out the purpose of the call and take a clear message."
        )

        effective_prompt = personality.custom_system_prompt if personality else default_instructions
        preferred_lang = personality.preferred_language if personality else "Hindi"

        return {
            "session_id": session.session_id,
            "caller_number": session.caller_number,
            "contact_name": personality.contact_name if personality else "Unknown Caller",
            "relationship": personality.relationship if personality else "UNKNOWN",
            "is_blocked": personality.is_blocked if personality else False,
            "is_vip": personality.is_vip if personality else False,
            "preferred_language": preferred_lang,
            "system_prompt": effective_prompt,
            "livekit_url": os.getenv("LIVEKIT_URL", "wss://your-project.livekit.cloud"),
        }


class CallSessionFinishSerializer(serializers.Serializer):
    """
    Called when call hangs up to persist duration and complete dialogue transcript.
    """
    session_id = serializers.UUIDField()
    answered_at = serializers.DateTimeField(required=False, allow_null=True)
    ended_at = serializers.DateTimeField(required=False, allow_null=True)
    duration_seconds = serializers.IntegerField(min_value=0, default=0)
    dialogue_transcript = serializers.ListField(
        child=serializers.DictField(),
        required=False,
        default=list
    )

    def create(self, validated_data):
        session_id = validated_data["session_id"]
        try:
            session = CallSession.objects.get(session_id=session_id)
        except CallSession.DoesNotExist:
            raise ValidationError(f"CallSession with id {session_id} not found.")

        session.status = CallStatus.COMPLETED
        session.answered_at = validated_data.get("answered_at") or session.started_at
        session.ended_at = validated_data.get("ended_at") or timezone.now()
        session.duration_seconds = validated_data.get("duration_seconds", 0)
        session.dialogue_transcript = validated_data.get("dialogue_transcript", [])
        session.save(update_fields=["status", "answered_at", "ended_at", "duration_seconds", "dialogue_transcript"])

        return {
            "session_id": session.session_id,
            "status": session.status,
            "duration_seconds": session.duration_seconds,
            "is_processed": session.is_processed,
        }


class CallInsightSerializer(serializers.ModelSerializer):
    caller_number = serializers.CharField(source="call_session.caller_number", read_only=True)
    duration_seconds = serializers.IntegerField(source="call_session.duration_seconds", read_only=True)

    class Meta:
        model = CallInsight
        fields = [
            "id",
            "call_session",
            "caller_number",
            "duration_seconds",
            "call_motive",
            "executive_summary",
            "caller_personality_notes",
            "urgency_level",
            "action_items",
            "analyzed_at",
        ]
        read_only_fields = ["id", "analyzed_at"]
