from django.contrib import admin
from django.utils.html import format_html
from .models import ContactPersonality, CallSession, CallInsight


@admin.register(ContactPersonality)
class ContactPersonalityAdmin(admin.ModelAdmin):
    list_display = (
        "phone_number",
        "contact_name",
        "relationship_badge",
        "preferred_language",
        "is_vip",
        "is_blocked",
        "updated_at",
    )
    list_filter = ("relationship", "preferred_language", "is_vip", "is_blocked")
    search_fields = ("phone_number", "contact_name", "custom_system_prompt")
    fieldsets = (
        ("Caller Information", {
            "fields": ("phone_number", "contact_name", "relationship", "preferred_language")
        }),
        ("Custom Agent Persona & Instructions", {
            "fields": ("custom_system_prompt",),
            "description": "Specify exact guidelines for how your voice agent answers when this specific person calls."
        }),
        ("Flags & Routing", {
            "fields": ("is_vip", "is_blocked")
        }),
    )

    def relationship_badge(self, obj):
        colors = {
            "FAMILY": "#2e7d32",
            "WORK": "#1565c0",
            "FRIEND": "#6a1b9a",
            "DELIVERY": "#ef6c00",
            "SPAM": "#c62828",
            "UNKNOWN": "#616161",
        }
        color = colors.get(obj.relationship, "#616161")
        return format_html(
            f'<span style="background-color: {color}; color: white; padding: 3px 8px; border-radius: 4px; font-weight: bold;">{obj.get_relationship_display()}</span>'
        )
    relationship_badge.short_description = "Relationship"


@admin.register(CallSession)
class CallSessionAdmin(admin.ModelAdmin):
    list_display = (
        "session_id",
        "caller_number",
        "matched_contact",
        "status_badge",
        "duration_display",
        "started_at",
        "is_processed",
    )
    list_filter = ("status", "is_processed", "started_at")
    search_fields = ("session_id", "caller_number", "contact_personality__contact_name")
    readonly_fields = ("session_id", "started_at", "answered_at", "ended_at", "duration_seconds", "dialogue_transcript")

    def matched_contact(self, obj):
        if obj.contact_personality:
            return f"{obj.contact_personality.contact_name} ({obj.contact_personality.get_relationship_display()})"
        return "Unknown"
    matched_contact.short_description = "Contact"

    def duration_display(self, obj):
        return f"{obj.duration_seconds} sec"
    duration_display.short_description = "Duration"

    def status_badge(self, obj):
        colors = {
            "COMPLETED": "#2e7d32",
            "IN_PROGRESS": "#0288d1",
            "RINGING": "#f57c00",
            "REJECTED": "#d32f2f",
            "FAILED": "#757575",
        }
        color = colors.get(obj.status, "#757575")
        return format_html(
            f'<span style="background-color: {color}; color: white; padding: 3px 8px; border-radius: 4px;">{obj.get_status_display()}</span>'
        )
    status_badge.short_description = "Status"


@admin.register(CallInsight)
class CallInsightAdmin(admin.ModelAdmin):
    list_display = (
        "call_motive",
        "caller_number_display",
        "urgency_badge",
        "caller_personality_notes",
        "analyzed_at",
    )
    list_filter = ("urgency_level", "analyzed_at")
    search_fields = ("call_motive", "executive_summary", "caller_personality_notes", "call_session__caller_number")
    readonly_fields = ("call_session", "analyzed_at")

    def caller_number_display(self, obj):
        return obj.call_session.caller_number
    caller_number_display.short_description = "Caller Number"

    def urgency_badge(self, obj):
        colors = {
            "CRITICAL": "#b71c1c",
            "HIGH": "#e65100",
            "MEDIUM": "#f57f17",
            "LOW": "#33691e",
        }
        color = colors.get(obj.urgency_level, "#33691e")
        return format_html(
            f'<span style="background-color: {color}; color: white; padding: 3px 8px; border-radius: 4px; font-weight: bold;">{obj.get_urgency_level_display()}</span>'
        )
    urgency_badge.short_description = "Urgency"
