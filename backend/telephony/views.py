from rest_framework import status, viewsets
from rest_framework.permissions import AllowAny, IsAuthenticated
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import ContactPersonality, CallSession, CallInsight
from .serializers import (
    UserLoginSerializer,
    TokenRefreshSerializer,
    ContactPersonalitySerializer,
    CallInsightSerializer,
    CallSessionDetailSerializer,
    CallSessionInitSerializer,
    CallSessionFinishSerializer,
    SipTrunkStatusSerializer,
    DashboardSummarySerializer,
    OutboundCallSerializer,
)


class OutboundCallView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = OutboundCallSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        response_data = serializer.save()
        return Response(response_data, status=status.HTTP_201_CREATED)



class UserLoginView(APIView):
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = UserLoginSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        return Response(serializer.validated_data, status=status.HTTP_200_OK)


class TokenRefreshView(APIView):
    permission_classes = [AllowAny]

    def post(self, request):
        serializer = TokenRefreshSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        return Response(serializer.validated_data, status=status.HTTP_200_OK)


class UserProfileView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response({
            "id": request.user.id if request.user else None,
            "username": request.user.get_username() if request.user else "Agent Service",
            "is_staff": getattr(request.user, "is_staff", False),
            "is_active": getattr(request.user, "is_active", True),
        }, status=status.HTTP_200_OK)


class ContactPersonalityViewSet(viewsets.ModelViewSet):
    permission_classes = [IsAuthenticated]
    queryset = ContactPersonality.objects.all()
    serializer_class = ContactPersonalitySerializer


class CallInsightListView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        insights_queryset = CallInsight.objects.select_related("call_session", "call_session__contact_personality").all()
        urgency_filter = request.query_params.get("urgency")
        if urgency_filter:
            insights_queryset = insights_queryset.filter(urgency_level=urgency_filter.upper())

        serializer = CallInsightSerializer(insights_queryset, many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)


class CallSessionListView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        sessions_queryset = CallSession.objects.select_related("contact_personality").prefetch_related("insight").all()
        serializer = CallSessionDetailSerializer(sessions_queryset, many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)


class CallSessionDetailView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request, session_id):
        try:
            session_obj = CallSession.objects.select_related("contact_personality").get(session_id=session_id)
        except CallSession.DoesNotExist:
            return Response({"detail": "Call session not found."}, status=status.HTTP_404_NOT_FOUND)

        serializer = CallSessionDetailSerializer(session_obj)
        return Response(serializer.data, status=status.HTTP_200_OK)


class CallSessionInitView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = CallSessionInitSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        created_session_payload = serializer.save()
        return Response(created_session_payload, status=status.HTTP_201_CREATED)


class CallSessionFinishView(APIView):
    permission_classes = [IsAuthenticated]

    def post(self, request):
        serializer = CallSessionFinishSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        updated_session_payload = serializer.save()
        return Response(updated_session_payload, status=status.HTTP_200_OK)


class SipTrunkStatusView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        serializer = SipTrunkStatusSerializer({})
        return Response(serializer.data, status=status.HTTP_200_OK)


class DashboardSummaryView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        serializer = DashboardSummarySerializer({})
        return Response(serializer.data, status=status.HTTP_200_OK)


class PingView(APIView):
    permission_classes = [IsAuthenticated]

    def get(self, request):
        return Response({
            "status": "connected",
            "message": "PickupAI Backend & JWT Authentication verified successfully!",
            "user": request.user.get_username() if request.user else "service",
        }, status=status.HTTP_200_OK)
