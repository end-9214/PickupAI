from django.urls import path, include
from rest_framework.routers import DefaultRouter
from .views import (
    ContactPersonalityViewSet,
    CallSessionInitView,
    CallSessionFinishView,
    CallInsightListView,
)

router = DefaultRouter()
router.register(r"personalities", ContactPersonalityViewSet, basename="contact-personality")

urlpatterns = [
    path("", include(router.urls)),
    path("calls/init/", CallSessionInitView.as_view(), name="call-init"),
    path("calls/finish/", CallSessionFinishView.as_view(), name="call-finish"),
    path("insights/", CallInsightListView.as_view(), name="call-insights"),
]
