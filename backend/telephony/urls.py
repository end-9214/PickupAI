from django.urls import path, include
from rest_framework.routers import DefaultRouter

from .views import (
    UserLoginView,
    TokenRefreshView,
    UserProfileView,
    ContactPersonalityViewSet,
    CallInsightListView,
    CallSessionListView,
    CallSessionDetailView,
    CallSessionInitView,
    CallSessionFinishView,
    SipTrunkStatusView,
    DashboardSummaryView,
    OutboundCallView,
    AssistantChatView,
    PingView,
)


router = DefaultRouter()
router.register(r"personalities", ContactPersonalityViewSet, basename="contact-personality")

urlpatterns = [
    # Authentication endpoints
    path("auth/login/", UserLoginView.as_view(), name="auth-login"),
    path("auth/refresh/", TokenRefreshView.as_view(), name="auth-refresh"),
    path("auth/me/", UserProfileView.as_view(), name="auth-profile"),

    # Assistant & Agent endpoints
    path("assistant/chat/", AssistantChatView.as_view(), name="assistant-chat"),

    # Health & System Status
    path("ping/", PingView.as_view(), name="ping"),
    path("telephony/sip-trunk/", SipTrunkStatusView.as_view(), name="sip-trunk-status"),
    path("telephony/dashboard/", DashboardSummaryView.as_view(), name="dashboard-summary"),


    # Call Sessions & Insights
    path("calls/", CallSessionListView.as_view(), name="call-list"),
    path("calls/outbound/", OutboundCallView.as_view(), name="call-outbound"),
    path("calls/<uuid:session_id>/", CallSessionDetailView.as_view(), name="call-detail"),
    path("calls/init/", CallSessionInitView.as_view(), name="call-init"),
    path("calls/finish/", CallSessionFinishView.as_view(), name="call-finish"),
    path("insights/", CallInsightListView.as_view(), name="call-insights"),

    # Router endpoints (ContactPersonality CRUD)
    path("", include(router.urls)),
]
