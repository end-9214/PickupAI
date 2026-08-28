from rest_framework import status, viewsets
from rest_framework.response import Response
from rest_framework.views import APIView

from .models import ContactPersonality, CallSession, CallInsight
from .serializers import (
    AuthTokenValidationMixin,
    ContactPersonalitySerializer,
    CallSessionInitSerializer,
    CallSessionFinishSerializer,
    CallInsightSerializer,
)


class ContactPersonalityViewSet(viewsets.ModelViewSet, AuthTokenValidationMixin):
    """
    CRUD ViewSet to manage per-number personalities via API.
    """
    queryset = ContactPersonality.objects.all()
    serializer_class = ContactPersonalitySerializer

    def initial(self, request, *args, **kwargs):
        super().initial(request, *args, **kwargs)
        self.validate_auth(request)


class CallSessionInitView(APIView, AuthTokenValidationMixin):
    """
    Endpoint called by Android App / Agent when an incoming call starts.
    POST /api/calls/init/
    """
    def post(self, request):
        self.validate_auth(request)
        serializer = CallSessionInitSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        response_data = serializer.save()
        return Response(response_data, status=status.HTTP_201_CREATED)


class CallSessionFinishView(APIView, AuthTokenValidationMixin):
    """
    Endpoint called when a call completes to store duration and transcript.
    POST /api/calls/finish/
    """
    def post(self, request):
        self.validate_auth(request)
        serializer = CallSessionFinishSerializer(data=request.data)
        serializer.is_valid(raise_exception=True)
        response_data = serializer.save()
        return Response(response_data, status=status.HTTP_200_OK)


class CallInsightListView(APIView, AuthTokenValidationMixin):
    """
    Endpoint to retrieve processed analytical insights of calls.
    GET /api/insights/
    """
    def get(self, request):
        self.validate_auth(request)
        insights = CallInsight.objects.select_related("call_session").all()
        serializer = CallInsightSerializer(insights, many=True)
        return Response(serializer.data, status=status.HTTP_200_OK)
