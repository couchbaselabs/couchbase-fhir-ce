from __future__ import annotations

from typing import Any, Dict, Optional
from faker import Faker


fake = Faker()


def _create_batch_bundle(pattern: str, count_each: int) -> Dict[str, Any]:
    return {
        "resourceType": "Bundle",
        "type": "batch",
        "entry": [
            {"request": {"method": "GET", "url": f"Patient?identifier={pattern}&_count={count_each}"}},
            {"request": {"method": "GET", "url": f"Patient?name={pattern}&_count={count_each}"}},
            {"request": {"method": "GET", "url": f"Patient?telecom={pattern}&_count={count_each}"}},
        ],
    }


def _get_batch_response(client, batch_bundle: Dict[str, Any]) -> Optional[Dict[str, Any]]:
    resp = client.post("", json=batch_bundle, name="POST / (batch Patient search)")
    if not resp.ok:
        return None
    try:
        return resp.json() or {}
    except Exception:
        return None


def _extract_patient_entries(batch_response: Dict[str, Any]) -> list[Dict[str, Any]]:
    merged_entries: list[Dict[str, Any]] = []
    seen_ids: set[str] = set()

    for entry in (batch_response.get("entry") or []):
        res = (entry or {}).get("resource") or {}
        if res.get("resourceType") == "Bundle":
            _process_bundle_entries(res, merged_entries, seen_ids)

    return merged_entries


def _process_bundle_entries(res: Dict[str, Any], merged_entries: list[Dict[str, Any]], seen_ids: set[str]) -> None:
    for e in (res.get("entry") or []):
        res_patient = (e or {}).get("resource") or {}
        if res_patient.get("resourceType") == "Patient":
            pid = res_patient.get("id")
            if pid and pid not in seen_ids:
                merged_entries.append(e)
                seen_ids.add(pid)


def _create_search_result_bundle(merged_entries: list[Dict[str, Any]]) -> Dict[str, Any]:
    if not merged_entries:
        return {"resourceType": "Bundle", "type": "searchset", "total": 0, "entry": []}

    return {
        "resourceType": "Bundle",
        "type": "searchset",
        "total": len(merged_entries),
        "entry": merged_entries,
    }


def search_patient_batch(client, *, pattern: str, count_each: int = 20) -> Optional[Dict[str, Any]]:
    batch_bundle = _create_batch_bundle(pattern, count_each)
    batch_response = _get_batch_response(client, batch_bundle)
    if batch_response is None:
        return None

    merged_entries = _extract_patient_entries(batch_response)
    return _create_search_result_bundle(merged_entries)


def get_or_create_patient_by_identifier(client, identifier: str) -> Optional[str]:
    # First try a single batch search using identifier, name, or phone (telecom)
    batch_bundle = search_patient_batch(client, pattern=identifier, count_each=20)
    if batch_bundle and batch_bundle.get("entry"):
        try:
            pid = batch_bundle["entry"][0]["resource"]["id"]
            client.get("Patient/" + pid, name="GET /Patient/{id}")
            return pid
        except Exception:
            pass

    # Create Patient if not found
    patient = {
        "resourceType": "Patient",
        "identifier": [
            {"system": "urn:medblocks:loadtests:identifier", "value": identifier}
        ],
        "name": [
            {
                "use": "official",
                "family": fake.last_name(),
                "given": [fake.first_name()],
            }
        ],
        "gender": fake.random_element(["male", "female", "other", "unknown"]),
        "birthDate": fake.date_of_birth(minimum_age=1, maximum_age=95).isoformat(),
        "telecom": [{"system": "phone", "value": fake.msisdn()}],
        "address": [
            {
                "use": "home",
                "line": [fake.street_address()],
                "city": fake.city(),
                "postalCode": fake.postcode(),
                "country": fake.country_code(),
            }
        ],
    }
    create = client.post("Patient", json=patient, name="POST /Patient")
    if create.ok:
        pid = create.json().get("id")
        # Read-back created
        client.get("Patient/" + pid, name="GET /Patient/{id}")
        return pid
    return None


