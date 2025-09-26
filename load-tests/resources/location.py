from __future__ import annotations

from typing import Optional
import random
from faker import Faker


fake = Faker()


def choose_existing_location(client) -> Optional[str]:
    # Prefer existing seeded locations
    resp = client.get("Location", params={"status": "active", "_count": 50}, name="GET /Location?status=")
    if resp.ok:
        bundle = resp.json()
        entries = bundle.get("entry", [])
        if entries:
            return random.choice(entries)["resource"].get("id")
    return None


def get_or_create_location(client) -> Optional[str]:
    # Try to choose an existing seeded Location
    existing = choose_existing_location(client)
    if existing:
        return existing

    # Fallback: create a simple Location
    location = {
        "resourceType": "Location",
        "name": f"Room {fake.random_int(min=1, max=50)}",
        "status": "active",
    }
    resp = client.post("Location", json=location, name="POST /Location")
    if resp.ok:
        return resp.json().get("id")
    return None


