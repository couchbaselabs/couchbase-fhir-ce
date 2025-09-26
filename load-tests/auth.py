from dotenv import load_dotenv
import os
import requests


# Load environment variables from fhir/.env explicitly
load_dotenv()

# HAPI FHIR configuration - load from .env.hapi
load_dotenv(".env.hapi")
HAPI_FHIR_URL = os.getenv("HAPI_FHIR_URL")

# Medplum FHIR configuration - load from .env.medplum
load_dotenv(".env.medplum")
MEDPLUM_FHIR_URL = os.getenv("MEDPLUM_FHIR_URL")
CLIENT_ID = os.getenv("MEDPLUM_CLIENT_ID")
CLIENT_SECRET = os.getenv("MEDPLUM_CLIENT_SECRET")
TOKEN_URL = os.getenv("MEDPLUM_TOKEN_URL")

# Check if required environment variables are loaded
if not CLIENT_ID or not CLIENT_SECRET:
    print("Warning: CLIENT_ID or CLIENT_SECRET not found in environment variables")
    print("Make sure your .env file exists and contains these variables")

def get_access_token():
    if not CLIENT_ID or not CLIENT_SECRET:
        raise ValueError("Missing CLIENT_ID or CLIENT_SECRET in environment/.env")

    token_url = TOKEN_URL
    headers = {
        'Content-Type': 'application/x-www-form-urlencoded'
    }
    data = {
        'grant_type': 'client_credentials',
        'client_id': CLIENT_ID,
        'client_secret': CLIENT_SECRET,
        'scope': 'system/*.*'
    }

    response = requests.post(token_url, headers=headers, data=data)
    if response.status_code == 200:
        return response.json().get('access_token')
    else:
        raise RuntimeError(f"Failed to get access token: {response.status_code} {response.text}")


def build_optional_auth_headers():
    if CLIENT_ID and CLIENT_SECRET and TOKEN_URL:
        try:
            token = get_access_token()
            return {
                "Content-Type": "application/json",
                "Authorization": f"Bearer {token}",
            }
        except Exception as exc:
            print(f"Warning: failed to obtain access token ({exc}); proceeding without Authorization header")
            return None
    return None
