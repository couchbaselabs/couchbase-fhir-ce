from __future__ import annotations

from typing import Any, Dict, Optional, List
from faker import Faker
from datetime import datetime, timezone
import uuid

fake = Faker()


# FHIR implementation for Complaints
FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_COMPLAINTS = "complaints"
SNOMED = "http://snomed.info/sct"

# Eye body sites
RIGHT_EYE_SNOMED = "1290032005"
LEFT_EYE_SNOMED = "1290031003"
BOTH_EYES_SNOMED = "1290040004"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def _body_site_cc(bs_text: str) -> Dict[str, Any]:
  code = RIGHT_EYE_SNOMED if bs_text == "Right Eye" else LEFT_EYE_SNOMED if bs_text == "Left Eye" else BOTH_EYES_SNOMED
  return {"coding": [{"system": SNOMED, "code": code}], "text": bs_text}


def create_complaints_form_with_fake_data(client,  patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref: Optional[str] = {"reference": f"Practitioner/{user_id}"} if user_id else None

  possible_symptoms = ["Blurred vision", "Eye pain", "Headache", "Photophobia", "Redness"]

  def random_body_site() -> Optional[str]:
    return fake.random_element(["Right Eye", "Left Eye", "Both Eyes"]) if fake.boolean(50) else None

  entries: List[Dict[str, Any]] = []

  # 1) QuestionnaireResponse anchor
  qr_full_url = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_COMPLAINTS}]},
  }
  if performer_ref:
    qr["author"] = performer_ref
  entries.append({"fullUrl": qr_full_url, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # 2) Conditions representing each complaint (problem_diagnosis equivalent)
  num = fake.random_int(min=1, max=3)
  condition_full_urls: List[str] = []
  for i in range(num):
    name = fake.random_element(possible_symptoms)
    duration_days = str(fake.random_int(min=1, max=30))
    bs_txt = random_body_site()
    condition: Dict[str, Any] = {
      "resourceType": "Condition",
      "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
      "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-category", "code": "complaint"}]}],
      "code": {"text": name},
      "subject": {"reference": f"Patient/{pid}"},
      "encounter": {"reference": f"Encounter/{eid}"},
      "note": [{"text": f"Duration (days): {duration_days}"}],
      "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_COMPLAINTS}]},
      "evidence": [{"detail": [{"reference": qr_full_url}]}],
    }
    if bs_txt:
      condition["bodySite"] = [_body_site_cc(bs_txt)]
    if performer_ref:
      condition["asserter"] = performer_ref
    fu = f"urn:uuid:{uuid.uuid4()}"
    condition_full_urls.append(fu)
    entries.append({"fullUrl": fu, "resource": condition, "request": {"method": "POST", "url": "Condition"}})

  # 3) List packaging (QR + Conditions)
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Complaints Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_COMPLAINTS}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_COMPLAINTS}]},
    "entry": ([{"item": {"reference": qr_full_url}}] + [{"item": {"reference": fu}} for fu in condition_full_urls]),
    "author": performer_ref,
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction complaints)")
  return _resp_json(resp)


def fetch_complaints(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_COMPLAINTS}",
    "_include": ["List:item"],
    "_count": 200,
  }
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"

  list_resp = client.get("List", params=params, name="GET /List?code=complaints&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle

  # Fallback by tag
  qr_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_COMPLAINTS}",
    "_count": 200,
  }
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=complaints")
  qr_bundle = _resp_json(qr_resp) or {}

  cond_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_COMPLAINTS}",
    "_count": 200,
  }
  if encounter_id is not None:
    cond_params["encounter"] = f"Encounter/{str(encounter_id)}"
  cond_resp = client.get("Condition", params=cond_params, name="GET /Condition?_tag=complaints")
  cond_bundle = _resp_json(cond_resp) or {}

  entries = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (cond_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})

  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or cond_bundle

