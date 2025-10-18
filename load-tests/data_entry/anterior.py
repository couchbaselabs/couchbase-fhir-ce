from __future__ import annotations

from datetime import datetime, timezone
from typing import Any, Dict, Optional, List
from faker import Faker
import random
import uuid

fake = Faker()

# Pure FHIR implementation for Anterior Chamber (and related anterior segment) exam

# Tags and code systems
FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_ANTERIOR = "anterior_chamber"
SNOMED = "http://snomed.info/sct"
OBS_CATEGORY_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/observation-category"
OBS_CATEGORY_ANTERIOR = "anterior-segment"

# Eye body sites
RIGHT_EYE_SNOMED = "1290032005"
LEFT_EYE_SNOMED = "1290031003"
RIGHT_EYE_TEXT = "Right Eye"
LEFT_EYE_TEXT = "Left Eye"

# Systems examined (mirrors Hasura logic and SNOMED codes)
SYSTEMS: List[Dict[str, str]] = [
  {"system_text": "Structure of right eyelid", "system_code": "721066003", "body_text": RIGHT_EYE_TEXT, "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Structure of left eyelid", "system_code": "721065004", "body_text": LEFT_EYE_TEXT, "body_code": LEFT_EYE_SNOMED},
  {"system_text": "Structure of conjunctiva of right eye", "system_code": "13014005", "body_text": RIGHT_EYE_TEXT, "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Structure of conjunctiva of left eye", "system_code": "67548002", "body_text": LEFT_EYE_TEXT, "body_code": LEFT_EYE_SNOMED},
  {"system_text": "Entire cornea of right eye", "system_code": "368573002", "body_text": RIGHT_EYE_TEXT, "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Entire cornea of left eye", "system_code": "368595003", "body_text": LEFT_EYE_TEXT, "body_code": LEFT_EYE_SNOMED},
  {"system_text": "Structure of anterior chamber of right eye", "system_code": "721987006", "body_text": RIGHT_EYE_TEXT, "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Structure of anterior chamber of left eye", "system_code": "721986002", "body_text": LEFT_EYE_TEXT, "body_code": LEFT_EYE_SNOMED},
]


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def _observation_template(pid: str, eid: str, *, system_text: str, system_code: str, body_text: str, body_code: str, value_text: str, performer_ref: Optional[str], qr_full_url: Optional[str]) -> Dict[str, Any]:
  obs: Dict[str, Any] = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": OBS_CATEGORY_ANTERIOR}]}],
    "code": {"coding": [{"system": SNOMED, "code": system_code}], "text": system_text},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_code}], "text": body_text},
    "valueCodeableConcept": {"text": value_text},
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ANTERIOR}]},
  }
  if performer_ref:
    obs["performer"] = [{"reference": performer_ref}]
  if qr_full_url:
    obs["derivedFrom"] = [{"reference": qr_full_url}]
  return obs


def create_anterior_chamber_form_with_fake_data(client,  patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  """
  Creates a QuestionnaireResponse anchor, anterior segment Observations for both eyes
  (lids, conjunctiva, cornea, anterior chamber), and a List packaging for retrieval.
  Uses a transaction Bundle for atomicity. "user_id" is used as Practitioner author/performer if provided.
  Returns the transaction response bundle.
  """
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref: Optional[str] = f"Practitioner/{user_id}" if user_id else None

  text_choices = [
    "Normal",
    "Mild hyperemia",
    "Clear",
    "Pigmented",
    "Deep and quiet",
    "Shallow",
    "Cells and flare present",
  ]

  def pick() -> str:
    return random.choice(text_choices)

  entries: List[Dict[str, Any]] = []

  # 1) QuestionnaireResponse anchor
  qr_full_url = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ANTERIOR}]},
  }
  if performer_ref:
    qr["author"] = {"reference": performer_ref}
  entries.append({
    "fullUrl": qr_full_url,
    "resource": qr,
    "request": {"method": "POST", "url": "QuestionnaireResponse"},
  })

  # 2) Observations for each system and eye
  obs_full_urls: List[str] = []
  for idx, s in enumerate(SYSTEMS):
    obs = _observation_template(
      pid, eid,
      system_text=s["system_text"], system_code=s["system_code"],
      body_text=s["body_text"], body_code=s["body_code"],
      value_text=pick(), performer_ref=performer_ref, qr_full_url=qr_full_url,
    )
    full_url = f"urn:uuid:{uuid.uuid4()}"
    obs_full_urls.append(full_url)
    entries.append({
      "fullUrl": full_url,
      "resource": obs,
      "request": {"method": "POST", "url": "Observation"},
    })

  # 3) List packaging (QR + Observations)
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Anterior Chamber Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ANTERIOR}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ANTERIOR}]},
    "entry": (
      [{"item": {"reference": qr_full_url}}]
      + [{"item": {"reference": fu}} for fu in obs_full_urls]
    ),
  }
  entries.append({
    "fullUrl": f"urn:uuid:{uuid.uuid4()}",
    "resource": list_body,
    "request": {"method": "POST", "url": "List"},
  })

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction anterior-chamber)")
  return _resp_json(resp)


def fetch_ac(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  """
  FHIR fetch for anterior chamber. Prefer List anchor with code tag; fallback to tag-based
  fetch of QuestionnaireResponses and Observations.
  """
  pid = str(patient_id)
  params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ANTERIOR}",
    "_include": ["List:item"],
    "_count": 200,
  }
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"

  list_resp = client.get("List", params=params, name="GET /List?code=anterior_chamber&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle

  # Fallback: fetch QR and Observations by tag
  qr_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ANTERIOR}",
    "_count": 200,
  }
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=anterior_chamber")
  qr_bundle = _resp_json(qr_resp) or {}

  obs_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ANTERIOR}",
    "_count": 200,
  }
  if encounter_id is not None:
    obs_params["encounter"] = f"Encounter/{str(encounter_id)}"
  obs_resp = client.get("Observation", params=obs_params, name="GET /Observation?_tag=anterior_chamber")
  obs_bundle = _resp_json(obs_resp) or {}

  entries: List[Dict[str, Any]] = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (obs_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})

  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or obs_bundle

