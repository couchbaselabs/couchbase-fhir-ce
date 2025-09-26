from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional


GET_ENCOUNTER_BY_ID = "GET /Encounter/{id}"

def get_encounters_with_details(client, count: int = 20) -> Optional[dict]:
    params = {
        "_count": int(count),
        "_include": [
            "Encounter:subject",      # include Patient
            "Encounter:participant",  # include Practitioner/PractitionerRole/etc. referenced in participant
            "Encounter:location",     # include Location
        ],
    }
    resp = client.get("Encounter", params=params, name="GET /Encounter?_include=subject,participant,location&_count")
    if not resp.ok:
        return None
    return resp.json()

def create_encounter(client, patient_id: str, practitioner_id: Optional[str], location_id: Optional[str]) -> Optional[str]:
    encounter = {
        "resourceType": "Encounter",
        "status": "in-progress",
        "class": {"system": "http://terminology.hl7.org/CodeSystem/v3-ActCode", "code": "AMB"},
        "subject": {"reference": f"Patient/{patient_id}"},
        "participant": (
            [{"individual": {"reference": f"Practitioner/{practitioner_id}"}}]
            if practitioner_id
            else []
        ),
        "location": (
            [{"location": {"reference": f"Location/{location_id}"}}] if location_id else []
        ),
    }
    resp = client.post("Encounter", json=encounter, name="POST /Encounter")
    if not resp.ok:
        return None
    eid = resp.json().get("id")
    client.get(f"Encounter/{eid}", name=GET_ENCOUNTER_BY_ID)
    return eid


def transfer_encounter_location(client, encounter_id: str, new_location_id: str) -> bool:
    # Read the full resource first, then update and PUT full representation (Medplum requires required fields on PUT)
    current_resp = client.get(f"Encounter/{encounter_id}", name=GET_ENCOUNTER_BY_ID)
    if not getattr(current_resp, "ok", False):
        return False
    try:
        body = current_resp.json() or {}
    except Exception:
        return False

    if not isinstance(body, dict):
        return False

    body["resourceType"] = "Encounter"
    body["id"] = encounter_id
    body["location"] = [{"location": {"reference": f"Location/{new_location_id}"}}]

    resp = client.put(f"Encounter/{encounter_id}", json=body, name="PUT /Encounter/{id}")
    if not getattr(resp, "ok", False):
        return False
    client.get(f"Encounter/{encounter_id}", name=GET_ENCOUNTER_BY_ID)
    return True

def discharge_encounter(client, encounter_id: str) -> bool:
    # Read full resource, update status/period, and PUT full representation
    current_resp = client.get(f"Encounter/{encounter_id}", name=GET_ENCOUNTER_BY_ID)
    if not getattr(current_resp, "ok", False):
        return False
    try:
        body = current_resp.json() or {}
    except Exception:
        return False

    if not isinstance(body, dict):
        return False

    body["resourceType"] = "Encounter"
    body["id"] = encounter_id
    body["status"] = "finished"
    period = body.get("period") or {}
    period["end"] = datetime.now(timezone.utc).replace(microsecond=0).isoformat()
    body["period"] = period

    resp = client.put(f"Encounter/{encounter_id}", json=body, name="PUT /Encounter/{id}")
    if not getattr(resp, "ok", False):
        return False
    client.get(f"Encounter/{encounter_id}", name=GET_ENCOUNTER_BY_ID)
    return True
