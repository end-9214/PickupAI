import uuid
from django.db import models
from django.utils import timezone


class RelationshipType(models.TextChoices):
    FAMILY = "FAMILY", "Family Member"
    WORK = "WORK", "Work / Business Colleague"
    FRIEND = "FRIEND", "Friend"
    DELIVERY_SERVICE = "DELIVERY", "Delivery / Service Agent"
    UNKNOWN = "UNKNOWN", "Unknown Caller"
    SPAM = "SPAM", "Spam / Telemarketer"


class CallStatus(models.TextChoices):
    RINGING = "RINGING", "Ringing"
    IN_PROGRESS = "IN_PROGRESS", "In Progress"
    COMPLETED = "COMPLETED", "Completed"
    REJECTED = "REJECTED", "Rejected"
    FAILED = "FAILED", "Failed"


class UrgencyLevel(models.TextChoices):
    LOW = "LOW", "Low / Casual"
    MEDIUM = "MEDIUM", "Medium / Informational"
    HIGH = "HIGH", "High / Important"
    CRITICAL = "CRITICAL", "Critical / Emergency"


class ContactPersonality(models.Model):
    """
    Per-number custom personality profile.
    If an incoming phone number matches this entry, the agent dynamically adopts
    this custom system prompt, tone, and relationship context.
    """
    phone_number = models.CharField(
        max_length=32,
        unique=True,
        help_text="Phone number in standard international/local format (e.g. +919876543210)"
    )
    contact_name = models.CharField(
        max_length=120,
        blank=True,
        help_text="Name of the person or organization (e.g. Mom, Boss Rohan, Amazon Delivery)"
    )
    relationship = models.CharField(
        max_length=20,
        choices=RelationshipType.choices,
        default=RelationshipType.UNKNOWN,
        help_text="Categorized relationship to Karamveer"
    )
    custom_system_prompt = models.TextField(
        help_text="Special instructions for how the AI agent must speak to this specific person (e.g. 'Speak with high respect and address as Sir', 'Tell Mom I am in a meeting and will reach home by 8 PM')."
    )
    preferred_language = models.CharField(
        max_length=30,
        default="Hindi",
        help_text="Preferred speaking language (e.g. Hindi, English, Hinglish)"
    )
    is_vip = models.BooleanField(
        default=False,
        help_text="If true, marks call insights as high priority"
    )
    is_blocked = models.BooleanField(
        default=False,
        help_text="If true, agent rejects or immediately dismisses the call"
    )
    created_at = models.DateTimeField(auto_now_add=True)
    updated_at = models.DateTimeField(auto_now=True)

    class Meta:
        verbose_name = "Contact Personality Rule"
        verbose_name_plural = "Contact Personality Rules"
        ordering = ["-updated_at"]

    def __str__(self):
        return f"{self.contact_name or 'Unnamed'} ({self.phone_number}) - {self.get_relationship_display()}"


class CallSession(models.Model):
    """
    A single phone call event picked up and handled by the AI voice agent.
    """
    session_id = models.UUIDField(
        primary_key=True,
        default=uuid.uuid4,
        editable=False,
        help_text="Unique session identifier / LiveKit Room ID"
    )
    caller_number = models.CharField(
        max_length=32,
        help_text="Caller phone number"
    )
    contact_personality = models.ForeignKey(
        ContactPersonality,
        on_delete=models.SET_NULL,
        null=True,
        blank=True,
        related_name="calls",
        help_text="Matched personality profile if number was registered"
    )
    status = models.CharField(
        max_length=20,
        choices=CallStatus.choices,
        default=CallStatus.RINGING
    )
    started_at = models.DateTimeField(
        default=timezone.now,
        help_text="Timestamp when the incoming call was detected"
    )
    answered_at = models.DateTimeField(
        null=True,
        blank=True,
        help_text="Timestamp when the call was auto-answered"
    )
    ended_at = models.DateTimeField(
        null=True,
        blank=True,
        help_text="Timestamp when the call ended"
    )
    duration_seconds = models.PositiveIntegerField(
        default=0,
        help_text="Total active call duration in seconds"
    )
    dialogue_transcript = models.JSONField(
        default=list,
        blank=True,
        help_text="Turn-by-turn transcribed dialogue list [{'speaker': 'caller'|'agent', 'text': '...', 'timestamp': '...'}]"
    )
    is_processed = models.BooleanField(
        default=False,
        help_text="Flag indicating whether post-call LLM analysis has run"
    )

    class Meta:
        verbose_name = "Call Session"
        verbose_name_plural = "Call Sessions"
        ordering = ["-started_at"]

    def __str__(self):
        return f"Call from {self.caller_number} at {self.started_at.strftime('%Y-%m-%d %H:%M:%S')} ({self.duration_seconds}s)"


class CallInsight(models.Model):
    """
    Deep analytical insights extracted from the conversation by the local LLM.
    """
    call_session = models.OneToOneField(
        CallSession,
        on_delete=models.CASCADE,
        related_name="insight"
    )
    call_motive = models.CharField(
        max_length=255,
        help_text="Core purpose of the call (e.g. 'Asking for meeting reschedule', 'Delivery OTP verification')"
    )
    executive_summary = models.TextField(
        help_text="Clean concise summary of the entire call for quick reading"
    )
    caller_personality_notes = models.TextField(
        blank=True,
        help_text="Observed personality traits, emotional state, or urgency of the caller (e.g. Calm, Hurried, Anxious, Angry, Polite)"
    )
    urgency_level = models.CharField(
        max_length=20,
        choices=UrgencyLevel.choices,
        default=UrgencyLevel.LOW,
        help_text="Assessed urgency level"
    )
    action_items = models.JSONField(
        default=list,
        blank=True,
        help_text="List of follow-up tasks for Karamveer extracted from the call"
    )
    analyzed_at = models.DateTimeField(
        auto_now_add=True
    )

    class Meta:
        verbose_name = "Call Insight & Analysis"
        verbose_name_plural = "Call Insights & Analyses"
        ordering = ["-analyzed_at"]

    def __str__(self):
        return f"Insight: {self.call_motive} [{self.get_urgency_level_display()}]"
