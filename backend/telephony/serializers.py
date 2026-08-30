import os
import time
import json
import urllib.request

from django.contrib.auth import authenticate, get_user_model
from django.utils import timezone
from rest_framework import serializers
from rest_framework.exceptions import AuthenticationFailed, ValidationError

from .auth import generate_user_tokens, decode_jwt_token, generate_jwt_token
from .models import ContactPersonality, CallSession, CallInsight, CallStatus, UrgencyLevel, RelationshipType

User = get_user_model()


class UserLoginSerializer(serializers.Serializer):
    username = serializers.CharField(max_length=150, write_only=True)
    password = serializers.CharField(max_length=128, write_only=True, style={"input_type": "password"})

    def validate(self, attrs):
        username_input = attrs.get("username", "").strip()
        password_input = attrs.get("password", "")

        if not username_input or not password_input:
            raise ValidationError("Both username and password are required.")

        lookup_username = username_input
        if "@" in username_input:
            matched_user = User.objects.filter(email__iexact=username_input).first()
            if matched_user:
                lookup_username = matched_user.get_username()

        authenticated_user = authenticate(username=lookup_username, password=password_input)
        if not authenticated_user:
            raise AuthenticationFailed("Invalid username or password.")


        if not authenticated_user.is_active:
            raise AuthenticationFailed("User account is inactive or disabled.")

        token_data = generate_user_tokens(authenticated_user)
        return token_data


class TokenRefreshSerializer(serializers.Serializer):
    refresh_token = serializers.CharField()

    def validate(self, attrs):
        token_str = attrs.get("refresh_token")
        try:
            payload = decode_jwt_token(token_str)
        except AuthenticationFailed as err:
            raise ValidationError(str(err))

        if payload.get("token_type") != "refresh":
            raise ValidationError("Supplied token is not a valid refresh token.")

        user_id = payload.get("user_id")
        try:
            user = User.objects.get(id=user_id, is_active=True)
        except User.DoesNotExist:
            raise ValidationError("Associated user no longer exists or is inactive.")

        now_epoch = int(time.time())
        access_payload = {
            "user_id": user.id,
            "username": user.get_username(),
            "token_type": "access",
            "iat": now_epoch,
            "exp": now_epoch + (24 * 3600),
        }

        return {
            "access_token": generate_jwt_token(access_payload),
            "expires_in": 24 * 3600,
            "token_type": "Bearer",
        }


class ContactPersonalitySerializer(serializers.ModelSerializer):
    relationship_display = serializers.CharField(source="get_relationship_display", read_only=True)
    total_calls_count = serializers.SerializerMethodField()

    class Meta:
        model = ContactPersonality
        fields = [
            "id",
            "phone_number",
            "contact_name",
            "relationship",
            "relationship_display",
            "custom_system_prompt",
            "preferred_language",
            "is_vip",
            "is_blocked",
            "total_calls_count",
            "created_at",
            "updated_at",
        ]
        read_only_fields = ["id", "relationship_display", "total_calls_count", "created_at", "updated_at"]

    def get_total_calls_count(self, obj) -> int:
        return obj.calls.count()

    def validate_phone_number(self, raw_value: str) -> str:
        sanitized_digits = "".join(char for char in raw_value if char.isdigit() or char == "+")
        if len(sanitized_digits.replace("+", "")) < 4:
            raise ValidationError("Phone number must contain at least 4 digits.")
        return sanitized_digits

    def validate_custom_system_prompt(self, raw_value: str) -> str:
        trimmed = raw_value.strip()
        if not trimmed:
            raise ValidationError("Custom agent prompt instructions cannot be empty.")
        return trimmed

    def create(self, validated_data):
        return super().create(validated_data)

    def update(self, instance, validated_data):
        return super().update(instance, validated_data)


class CallInsightSerializer(serializers.ModelSerializer):
    caller_number = serializers.CharField(source="call_session.caller_number", read_only=True)
    contact_name = serializers.SerializerMethodField()
    relationship = serializers.SerializerMethodField()
    duration_seconds = serializers.IntegerField(source="call_session.duration_seconds", read_only=True)
    started_at = serializers.DateTimeField(source="call_session.started_at", read_only=True)
    dialogue_transcript = serializers.JSONField(source="call_session.dialogue_transcript", read_only=True)
    urgency_level_display = serializers.CharField(source="get_urgency_level_display", read_only=True)

    class Meta:
        model = CallInsight
        fields = [
            "id",
            "call_session",
            "caller_number",
            "contact_name",
            "relationship",
            "duration_seconds",
            "started_at",
            "call_motive",
            "executive_summary",
            "caller_personality_notes",
            "urgency_level",
            "urgency_level_display",
            "action_items",
            "dialogue_transcript",
            "analyzed_at",
        ]
        read_only_fields = fields

    def get_contact_name(self, obj) -> str:
        if obj.call_session and obj.call_session.contact_personality:
            return obj.call_session.contact_personality.contact_name or "Known Contact"
        return "Unknown Caller"

    def get_relationship(self, obj) -> str:
        if obj.call_session and obj.call_session.contact_personality:
            return obj.call_session.contact_personality.relationship
        return RelationshipType.UNKNOWN


class CallSessionDetailSerializer(serializers.ModelSerializer):
    insight = CallInsightSerializer(read_only=True)
    status_display = serializers.CharField(source="get_status_display", read_only=True)
    contact_name = serializers.SerializerMethodField()

    class Meta:
        model = CallSession
        fields = [
            "session_id",
            "caller_number",
            "contact_name",
            "status",
            "status_display",
            "started_at",
            "answered_at",
            "ended_at",
            "duration_seconds",
            "dialogue_transcript",
            "is_processed",
            "insight",
        ]
        read_only_fields = fields

    def get_contact_name(self, obj) -> str:
        if obj.contact_personality:
            return obj.contact_personality.contact_name or "Known Contact"
        return "Unknown Caller"


class CallSessionInitSerializer(serializers.Serializer):
    caller_number = serializers.CharField(max_length=64)
    session_id = serializers.UUIDField(required=False)

    def create(self, validated_data):
        normalized_caller = validated_data["caller_number"].strip().replace(" ", "")

        matched_profile = ContactPersonality.objects.filter(
            phone_number__endswith=normalized_caller[-10:] if len(normalized_caller) >= 10 else normalized_caller
        ).first()

        session_record = CallSession.objects.create(
            caller_number=normalized_caller,
            contact_personality=matched_profile,
            status=CallStatus.RINGING,
            started_at=timezone.now(),
        )

        fallback_prompt = (
            "You are Karamveer Singh's personal phone assistant answering his phone calls. "
            "Always speak natural, polite, and fluent Hindi. Keep replies very brief (1 or 2 short sentences). "
            "Find out who is calling, why they are calling, and take a clear message for Karamveer."
        )

        effective_prompt = matched_profile.custom_system_prompt if matched_profile else fallback_prompt
        selected_language = matched_profile.preferred_language if matched_profile else "Hindi"

        room_identifier = f"call-{session_record.session_id}"
        livekit_token_str = ""

        try:
            from livekit import api
            livekit_api_key = os.getenv("LIVEKIT_API_KEY", "")
            livekit_api_secret = os.getenv("LIVEKIT_API_SECRET", "")
            livekit_cloud_url = os.getenv("LIVEKIT_URL", "wss://testing-ep1sew1f.livekit.cloud")

            if livekit_api_key and livekit_api_secret:
                token_builder = api.AccessToken(livekit_api_key, livekit_api_secret) \
                    .with_identity(f"phone-{normalized_caller}") \
                    .with_name(matched_profile.contact_name if matched_profile else "Phone Caller") \
                    .with_grants(api.VideoGrants(
                        room_join=True,
                        room=room_identifier,
                        can_publish=True,
                        can_subscribe=True,
                    ))
                livekit_token_str = token_builder.to_jwt()

                import asyncio
                async def dispatch_worker():
                    async with api.LiveKitAPI(livekit_cloud_url, livekit_api_key, livekit_api_secret) as lk_client:
                        await lk_client.agent_dispatch.create_dispatch(
                            api.CreateAgentDispatchRequest(
                                agent_name="phone-pickup-agent",
                                room=room_identifier,
                            )
                        )

                try:
                    asyncio.run(dispatch_worker())
                except Exception:
                    pass
        except Exception:
            pass

        return {
            "session_id": session_record.session_id,
            "caller_number": session_record.caller_number,
            "contact_name": matched_profile.contact_name if matched_profile else "Unknown Caller",
            "relationship": matched_profile.relationship if matched_profile else "UNKNOWN",
            "is_blocked": matched_profile.is_blocked if matched_profile else False,
            "is_vip": matched_profile.is_vip if matched_profile else False,
            "preferred_language": selected_language,
            "system_prompt": effective_prompt,
            "livekit_url": os.getenv("LIVEKIT_URL", ""),
            "livekit_token": livekit_token_str,
            "room_name": room_identifier,
        }


class CallSessionFinishSerializer(serializers.Serializer):
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
        target_uuid = validated_data["session_id"]
        try:
            session_obj = CallSession.objects.get(session_id=target_uuid)
        except CallSession.DoesNotExist:
            raise ValidationError(f"CallSession with id {target_uuid} does not exist.")

        session_obj.status = CallStatus.COMPLETED
        session_obj.answered_at = validated_data.get("answered_at") or session_obj.started_at
        session_obj.ended_at = validated_data.get("ended_at") or timezone.now()
        session_obj.duration_seconds = validated_data.get("duration_seconds", 0)
        session_obj.dialogue_transcript = validated_data.get("dialogue_transcript", [])
        session_obj.save(update_fields=["status", "answered_at", "ended_at", "duration_seconds", "dialogue_transcript"])

        return {
            "session_id": session_obj.session_id,
            "status": session_obj.status,
            "duration_seconds": session_obj.duration_seconds,
            "is_processed": session_obj.is_processed,
        }


class SipTrunkStatusSerializer(serializers.Serializer):
    sip_trunk_id = serializers.SerializerMethodField()
    sip_phone_number = serializers.SerializerMethodField()
    livekit_url = serializers.SerializerMethodField()
    is_configured = serializers.SerializerMethodField()
    active_agent_rules_count = serializers.SerializerMethodField()
    total_calls_handled = serializers.SerializerMethodField()

    def get_sip_trunk_id(self, _) -> str:
        return os.getenv("LIVEKIT_SIP_TRUNK_ID", "ST_hTrSXznC7M8r")

    def get_sip_phone_number(self, _) -> str:
        return os.getenv("LIVEKIT_SIP_OUTBOUND_NUMBER", "+1 (640) 230-3978")

    def get_livekit_url(self, _) -> str:
        raw_url = os.getenv("LIVEKIT_URL", "wss://testing-ep1sew1f.livekit.cloud")
        return raw_url

    def get_is_configured(self, _) -> bool:
        trunk_id = os.getenv("LIVEKIT_SIP_TRUNK_ID", "")
        return bool(trunk_id and trunk_id.startswith("ST_"))

    def get_active_agent_rules_count(self, _) -> int:
        return ContactPersonality.objects.count()

    def get_total_calls_handled(self, _) -> int:
        return CallSession.objects.filter(status=CallStatus.COMPLETED).count()


class DashboardSummarySerializer(serializers.Serializer):
    total_calls = serializers.SerializerMethodField()
    vip_calls_count = serializers.SerializerMethodField()
    high_urgency_count = serializers.SerializerMethodField()
    active_personalities_count = serializers.SerializerMethodField()
    sip_trunk_id = serializers.SerializerMethodField()
    sip_phone_number = serializers.SerializerMethodField()
    latest_insights = serializers.SerializerMethodField()

    def get_total_calls(self, _) -> int:
        return CallSession.objects.count()

    def get_vip_calls_count(self, _) -> int:
        return CallSession.objects.filter(contact_personality__is_vip=True).count()

    def get_high_urgency_count(self, _) -> int:
        return CallInsight.objects.filter(urgency_level__in=[UrgencyLevel.HIGH, UrgencyLevel.CRITICAL]).count()

    def get_active_personalities_count(self, _) -> int:
        return ContactPersonality.objects.count()

    def get_sip_trunk_id(self, _) -> str:
        return os.getenv("LIVEKIT_SIP_TRUNK_ID", "ST_hTrSXznC7M8r")

    def get_sip_phone_number(self, _) -> str:
        return os.getenv("LIVEKIT_SIP_OUTBOUND_NUMBER", "+1 (640) 230-3978")

    def get_latest_insights(self, _) -> list:
        recent_records = CallInsight.objects.select_related("call_session", "call_session__contact_personality")[:5]
        return CallInsightSerializer(recent_records, many=True).data


class OutboundCallSerializer(serializers.Serializer):
    phone_number = serializers.CharField(max_length=64)
    custom_prompt = serializers.CharField(required=False, allow_blank=True, default="")
    contact_name = serializers.CharField(max_length=120, required=False, allow_blank=True, default="")

    def validate_phone_number(self, raw_number: str) -> str:
        sanitized = "".join(ch for ch in raw_number if ch.isdigit() or ch == "+")
        if len(sanitized.replace("+", "")) < 4:
            raise ValidationError("Please provide a valid phone number with at least 4 digits.")
        return sanitized

    def create(self, validated_data):
        target_phone = validated_data["phone_number"]
        custom_task_prompt = validated_data.get("custom_prompt", "").strip()
        target_name = validated_data.get("contact_name", "").strip()

        matched_personality = ContactPersonality.objects.filter(
            phone_number__endswith=target_phone[-10:] if len(target_phone) >= 10 else target_phone
        ).first()

        effective_name = target_name or (matched_personality.contact_name if matched_personality else "Recipient")

        session_record = CallSession.objects.create(
            caller_number=target_phone,
            contact_personality=matched_personality,
            status=CallStatus.IN_PROGRESS,
            started_at=timezone.now(),
        )

        base_template = (
            "You are Karamveer Singh and you have to talk like me in Hindi always perfectly. "
            "Speak in first-person as Karamveer Singh in natural, fluent, and polite Hindi. "
            "Keep responses concise, clear, and direct (1 to 2 short sentences per turn). "
            "{custom_prompt}"
        )

        subject_objective = custom_task_prompt or (
            matched_personality.custom_system_prompt if matched_personality else "Inquire with the recipient, discuss the intended subject, and take note of their response."
        )

        constructed_prompt = base_template.format(
            custom_prompt=f"Your specific objective in this call: {subject_objective}"
        )

        room_identifier = f"outbound-call-{session_record.session_id}"
        sip_trunk_id = os.getenv("LIVEKIT_SIP_TRUNK_ID", "ST_hTrSXznC7M8r")
        sip_outbound_caller_id = os.getenv("LIVEKIT_SIP_OUTBOUND_NUMBER", "+16402303978")
        livekit_url = os.getenv("LIVEKIT_URL", "wss://testing-ep1sew1f.livekit.cloud")
        api_key = os.getenv("LIVEKIT_API_KEY", "")
        api_secret = os.getenv("LIVEKIT_API_SECRET", "")

        dispatch_success = False
        sip_participant_id = ""

        try:
            from livekit import api
            if api_key and api_secret:
                import asyncio
                import json

                async def initiate_outbound():
                    nonlocal dispatch_success, sip_participant_id
                    async with api.LiveKitAPI(livekit_url, api_key, api_secret) as lk_client:
                        # 1. Dispatch Voice Agent worker
                        dispatch_metadata = json.dumps({
                            "is_outbound": True,
                            "session_id": str(session_record.session_id),
                            "phone_number": target_phone,
                            "contact_name": effective_name,
                            "custom_prompt": constructed_prompt,
                        })
                        await lk_client.agent_dispatch.create_dispatch(
                            api.CreateAgentDispatchRequest(
                                agent_name="phone-pickup-agent",
                                room=room_identifier,
                                metadata=dispatch_metadata,
                            )
                        )
                        dispatch_success = True

                        # 2. Trigger SIP Outbound Participant dial
                        if sip_trunk_id:
                            try:
                                sip_req = api.CreateSIPParticipantRequest(
                                    sip_trunk_id=sip_trunk_id,
                                    sip_call_to=target_phone,
                                    sip_number=sip_outbound_caller_id,
                                    room_name=room_identifier,
                                    participant_identity=f"sip-{target_phone}",
                                    participant_name=effective_name,
                                )
                                sip_res = await lk_client.sip.create_sip_participant(sip_req)
                                sip_participant_id = getattr(sip_res, "participant_id", "")
                            except Exception:
                                pass

                try:
                    asyncio.run(initiate_outbound())
                except Exception:
                    pass
        except Exception:
            pass


        return {
            "session_id": session_record.session_id,
            "status": "initiated",
            "phone_number": target_phone,
            "contact_name": effective_name,
            "room_name": room_identifier,
            "custom_prompt": constructed_prompt,
            "sip_trunk_id": sip_trunk_id,
            "dispatch_active": dispatch_success,
        }


class AssistantChatSerializer(serializers.Serializer):
    message = serializers.CharField(max_length=4000)
    history = serializers.ListField(child=serializers.DictField(), required=False, default=list)

    def validate_message(self, val: str) -> str:
        cleaned = val.strip()
        if not cleaned:
            raise ValidationError("Message cannot be empty.")
        return cleaned

    def create(self, validated_data):
        user_message = validated_data["message"]
        chat_history = validated_data.get("history", [])

        # 1. RAG Context: Retrieve Recent Call Insights & Transcripts
        recent_insights = CallInsight.objects.select_related("call_session", "call_session__contact_personality").order_by("-analyzed_at")[:8]
        rag_context_lines = []
        for ins in recent_insights:
            caller = ins.call_session.caller_number if ins.call_session else "Unknown"
            contact_label = ins.call_session.contact_personality.contact_name if ins.call_session and ins.call_session.contact_personality else caller
            rag_context_lines.append(
                f"- Call with {contact_label} ({caller}): Summary: \"{ins.call_summary}\", Urgency: {ins.urgency_level}, Action Items: {ins.action_items or 'None'}"
            )
        rag_text = "\n".join(rag_context_lines) if rag_context_lines else "No recent call history recorded yet."

        # 2. Known Contacts Context
        contacts_list = ContactPersonality.objects.all()[:15]
        contacts_summary = ", ".join([f"{c.contact_name} ({c.phone_number})" for c in contacts_list]) or "No custom contact rules yet."

        sip_trunk = os.getenv("LIVEKIT_SIP_TRUNK_ID", "ST_LDBvSLZLdKZg")
        sip_phone = os.getenv("LIVEKIT_SIP_OUTBOUND_NUMBER", "+1 (640) 230-3978")

        system_instruction = (
            "You are the intelligent Executive Telephony AI Assistant for Karamveer Singh in PickupAI. "
            "You speak politely and smartly in Hindi / Hinglish / English. "
            "You have full command over Karamveer's SIP Phone Agent system, telephony, contacts, and call records.\n\n"
            f"Active SIP Line: {sip_phone} (Trunk: {sip_trunk})\n"
            f"Known Contacts: {contacts_summary}\n\n"
            f"RECENT CALL INSIGHTS & RAG MEMORY:\n{rag_text}\n\n"
            "YOU HAVE ACTIVE TOOLS & CAN EXECUTE ACTIONS:\n"
            "1. When the user asks you to call or dial anyone (e.g. 'Call +91... to ask ...' or 'Call mom'), output an action block:\n"
            "```action\n{\"action\": \"call\", \"phone_number\": \"+...\", \"contact_name\": \"...\", \"prompt\": \"...\"}\n```\n"
            "2. When the user asks to save, create, or update a rule/personality for a contact, output:\n"
            "```action\n{\"action\": \"save_rule\", \"phone_number\": \"+...\", \"contact_name\": \"...\", \"custom_prompt\": \"...\", \"is_vip\": true/false, \"preferred_language\": \"Hindi\"}\n```\n"
            "3. Answer questions about recent calls, summaries, urgent items, or system status clearly based on the RAG context.\n"
            "Always be helpful, crisp, and direct."
        )

        ollama_url = f"{os.getenv('OLLAMA_HOST', 'http://127.0.0.1:11434').rstrip('/')}/api/chat"
        llm_model = os.getenv("LLM_MODEL", "gemma4:e4b")

        messages_payload = [{"role": "system", "content": system_instruction}]
        for h in chat_history[-6:]:
            if isinstance(h, dict) and "role" in h and "content" in h:
                messages_payload.append({"role": h["role"], "content": str(h["content"])})
        messages_payload.append({"role": "user", "content": user_message})

        raw_response_text = ""
        try:
            req = urllib.request.Request(
                ollama_url,
                data=json.dumps({"model": llm_model, "messages": messages_payload, "stream": False}).encode("utf-8"),
                headers={"Content-Type": "application/json"}
            )
            with urllib.request.urlopen(req, timeout=20) as resp:
                resp_json = json.loads(resp.read().decode("utf-8"))
                raw_response_text = resp_json.get("message", {}).get("content", "")
        except Exception as err:
            raw_response_text = f"Main response generated with fallback: {err}. How can I assist you with your SIP calls or rules?"

        # 3. Check for and execute any requested Tool Action
        executed_action = None
        cleaned_reply = raw_response_text

        if "```action" in raw_response_text:
            try:
                action_part = raw_response_text.split("```action")[1].split("```")[0].strip()
                action_data = json.loads(action_part)
                cleaned_reply = raw_response_text.split("```action")[0].strip() or "Done! I have executed the requested action."

                if action_data.get("action") == "call":
                    target_phone = action_data.get("phone_number", "")
                    prompt = action_data.get("prompt", "")
                    contact_name = action_data.get("contact_name", "")
                    if target_phone:
                        dial_serializer = OutboundCallSerializer(data={
                            "phone_number": target_phone,
                            "custom_prompt": prompt,
                            "contact_name": contact_name,
                        })
                        if dial_serializer.is_valid():
                            call_receipt = dial_serializer.save()
                            executed_action = {
                                "type": "outbound_call",
                                "status": "initiated",
                                "target": target_phone,
                                "contact_name": contact_name or "Recipient",
                                "session_id": str(call_receipt.get("session_id")),
                            }
                            cleaned_reply += f"\n\n📞 **Outbound AI Call Dispatched**: Calling `{target_phone}` via Twilio Line `{sip_phone}`!"

                elif action_data.get("action") == "save_rule":
                    phone = action_data.get("phone_number", "")
                    name = action_data.get("contact_name", "Contact")
                    custom_prompt = action_data.get("custom_prompt", "")
                    is_vip = bool(action_data.get("is_vip", False))
                    lang = action_data.get("preferred_language", "Hindi")
                    if phone:
                        rule, _ = ContactPersonality.objects.update_or_create(
                            phone_number=phone,
                            defaults={
                                "contact_name": name,
                                "custom_system_prompt": custom_prompt,
                                "is_vip": is_vip,
                                "preferred_language": lang,
                            }
                        )
                        executed_action = {
                            "type": "save_rule",
                            "status": "saved",
                            "contact_name": rule.contact_name,
                            "phone_number": rule.phone_number,
                            "is_vip": rule.is_vip,
                        }
                        cleaned_reply += f"\n\n⚙️ **Rule Saved**: Successfully updated personality rule for `{rule.contact_name}` ({rule.phone_number})!"
            except Exception:
                pass

        return {
            "reply": cleaned_reply,
            "executed_action": executed_action,
            "model": llm_model,
        }


