from __future__ import annotations

from typing import Optional
import random
from faker import Faker


fake = Faker()


def choose_existing_practitioner(client) -> Optional[str]:
    resp = client.get("Practitioner", params={"_count": 50}, name="GET /Practitioner")
    if resp.ok:
        bundle = resp.json()
        entries = bundle.get("entry", [])
        if entries:
            return random.choice(entries)["resource"].get("id")
    return None


def get_or_create_practitioner(client) -> Optional[str]:
    existing = choose_existing_practitioner(client)
    if existing:
        return existing

    # Fallback: create a Practitioner
    practitioner = {
        "resourceType": "Practitioner",
        "name": [
            {
                "use": "official",
                "family": fake.last_name(),
                "given": [fake.first_name()],
            }
        ],
        "telecom": [{"system": "phone", "value": fake.msisdn()}],
    }
    resp = client.post("Practitioner", json=practitioner, name="POST /Practitioner")
    if resp.ok:
        return resp.json().get("id")
    return None


