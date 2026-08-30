import os
import django

os.environ.setdefault("DJANGO_SETTINGS_MODULE", "config.settings")
django.setup()

from django.contrib.auth import get_user_model
from rest_framework.test import APITestCase
from rest_framework import status
from telephony.models import ContactPersonality, RelationshipType
from telephony.auth import generate_user_tokens, decode_jwt_token

User = get_user_model()


class TelephonyAPITestCase(APITestCase):
    def setUp(self):
        self.user = User.objects.filter(username="admin").first()
        if not self.user:
            self.user = User.objects.create_superuser(
                username="admin",
                password="TestAdminPassword123!",
                email="admin@example.com"
            )

    def test_jwt_token_generation_and_decoding(self):
        tokens = generate_user_tokens(self.user)
        assert "access_token" in tokens
        assert "refresh_token" in tokens
        
        decoded = decode_jwt_token(tokens["access_token"])
        assert decoded["username"] == "admin"
        assert decoded["token_type"] == "access"

    def test_login_api_success(self):
        url = "/api/auth/login/"
        data = {"username": "admin", "password": "TestAdminPassword123!"}
        response = self.client.post(url, data, format="json")
        assert response.status_code == status.HTTP_200_OK
        assert "access_token" in response.data

    def test_contact_personality_crud(self):
        tokens = generate_user_tokens(self.user)
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {tokens['access_token']}")

        url = "/api/personalities/"
        data = {
            "phone_number": "+919876543210",
            "contact_name": "Rohan Boss",
            "relationship": RelationshipType.WORK,
            "custom_system_prompt": "Tell Rohan I am reviewing the Q3 metrics.",
            "preferred_language": "English",
            "is_vip": True,
        }
        response = self.client.post(url, data, format="json")
        assert response.status_code in [status.HTTP_201_CREATED, status.HTTP_200_OK]
        assert response.data["contact_name"] == "Rohan Boss"

    def test_outbound_call_api(self):
        tokens = generate_user_tokens(self.user)
        self.client.credentials(HTTP_AUTHORIZATION=f"Bearer {tokens['access_token']}")

        url = "/api/calls/outbound/"
        data = {
            "phone_number": "+919876543210",
            "contact_name": "Rohan Boss",
            "custom_prompt": "Ask Rohan if the budget proposal was approved.",
        }
        response = self.client.post(url, data, format="json")
        assert response.status_code == status.HTTP_201_CREATED
        assert response.data["status"] == "initiated"
        assert "You are Karamveer Singh" in response.data["custom_prompt"]
        assert "budget proposal" in response.data["custom_prompt"]

