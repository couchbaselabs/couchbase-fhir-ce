from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional, Dict, Any, List
from faker import Faker
import uuid


fake = Faker()


# Form tagging (same pattern as auto_refractometer)
FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_IOP = "iop"

# Code systems
LOINC = "http://loinc.org"
SNOMED = "http://snomed.info/sct"
UCUM = "http://unitsofmeasure.org"
OBS_CATEGORY_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/observation-category"

# Eye body sites (SNOMED CT as used in Hasura data)
RIGHT_EYE_SNOMED = "1290032005"
LEFT_EYE_SNOMED = "1290031003"

# IOP LOINC codes (Tonometry IOP by eye)
IOP_RIGHT_LOINC = "8716-3"
IOP_LEFT_LOINC = "8717-1"

# Local codes mirroring Hasura's method/system examined
METHOD_SYSTEM = "http://terminology.medblocks.com/iop/method"
METHOD_NON_CONTACT = "Non-contact tonometry"
METHOD_TONOPEN = "Tono-Pen"
METHOD_ICARE = "Icare (Rebound)"

EXAM_SYSTEM = "http://terminology.medblocks.com/Valueset/examination-findings-system"
EXAM_CODE_DYNAMIC_CONTOUR = "at0051"
EXAM_TEXT_DYNAMIC_CONTOUR = "Dynamic Contour"
EXAM_CODE_GOLDMANN = "at0047"
EXAM_TEXT_GOLDMANN = "Goldmann"

# Local Duct status codes
DUCT_SYSTEM = "http://terminology.medblocks.com/iop/duct"
DUCT_VALUES = ["Patent", "Stenosed", "Blocked"]


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def _iop_observation_template(pid: str, eid: str, eye: str, loinc_code: str, body_site_code: str, method_text: str) -> Dict[str, Any]:
  return {
    "resourceType": "Observation",
    "status": "final",
    "category": [
      {"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": "iop"}]}
    ],
    "code": {"coding": [{"system": LOINC, "code": loinc_code}], "text": f"{eye} IOP"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_site_code}], "text": f"{eye} Eye"},
    "method": {"coding": [{"system": METHOD_SYSTEM, "code": method_text}], "text": method_text},
    "valueQuantity": {"value": fake.random_int(min=10, max=22), "unit": "mmHg", "system": UCUM, "code": "mm[Hg]"},
    "note": [{"text": f"{eye} Eye {method_text}"}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_IOP}]},
  }


def _cct_observation_template(pid: str, eid: str, eye: str, body_site_code: str, method_code: str, method_text: str) -> Dict[str, Any]:
  # Use a generic code with text to avoid incorrect LOINC if unavailable; servers can map as needed
  return {
    "resourceType": "Observation",
    "status": "final",
    "category": [
      {"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": "iop"}]}
    ],
    "code": {"coding": [{"system": LOINC, "code": "1032703"}], "text": f"{eye} Central corneal thickness"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_site_code}], "text": f"{eye} Eye"},
    "method": {
      "coding": [{"system": EXAM_SYSTEM, "code": method_code}],
      "text": method_text,
    },
    "valueQuantity": {"value": round(fake.pyfloat(left_digits=3, right_digits=1, positive=True, min_value=480, max_value=560), 1), "unit": "um", "system": UCUM, "code": "um"},
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_IOP}]},
  }


def _duct_observation_template(pid: str, eid: str, eye: str, body_site_code: str) -> Dict[str, Any]:
  status = fake.random_element(elements=DUCT_VALUES)
  return {
    "resourceType": "Observation",
    "status": "final",
    "category": [
      {"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": "iop"}]}
    ],
    "code": {"text": f"{eye} Lacrimal duct patency"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_site_code}], "text": f"{eye} Eye"},
    "method": {
      "coding": [{"system": EXAM_SYSTEM, "code": EXAM_CODE_DYNAMIC_CONTOUR}],
      "text": EXAM_TEXT_DYNAMIC_CONTOUR,
    },
    "valueCodeableConcept": {"coding": [{"system": DUCT_SYSTEM, "code": status}], "text": status},
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_IOP}]},
  }


def create_iop_form_with_fake_data(client, patient_id: str, encounter_id: str,user_id: str) -> Optional[Dict[str, Any]]:
  """
    - Creates a QuestionnaireResponse anchor
    - Creates multiple IOP Observations for both eyes across methods (Non-contact, Tono-Pen, Icare)
    - Creates duct patency Observations for both eyes (Dynamic Contour)
    - Creates CCT Observations for both eyes (Goldmann context)
    - Packages all into a List for retrieval, tagged with form=iop
    - Uses a single transaction Bundle for maximum atomicity
  Returns a dict of created resource ids if available.
  """
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None
  # Prepare resources with temporary URNs for intra-transaction references
  qr_full_url = "urn:uuid:qr-iop"

  # IOP observations across methods for both eyes
  iop_specs: List[tuple[str, str, str, str]] = [
    ("OD", IOP_RIGHT_LOINC, RIGHT_EYE_SNOMED, METHOD_NON_CONTACT),
    ("OS", IOP_LEFT_LOINC, LEFT_EYE_SNOMED, METHOD_ICARE),
    ("OD", IOP_RIGHT_LOINC, RIGHT_EYE_SNOMED, METHOD_TONOPEN),
    ("OS", IOP_LEFT_LOINC, LEFT_EYE_SNOMED, METHOD_ICARE),
    ("OD", IOP_RIGHT_LOINC, RIGHT_EYE_SNOMED, METHOD_ICARE),
    ("OS", IOP_LEFT_LOINC, LEFT_EYE_SNOMED, METHOD_ICARE),
  ]

  entries: List[Dict[str, Any]] = []

  # 1) QuestionnaireResponse (anchor)
  qr_full_url = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_IOP}]},
    "author": performer_ref,
  }
  entries.append({
    "fullUrl": qr_full_url,
    "resource": qr,
    "request": {"method": "POST", "url": "QuestionnaireResponse"},
  })

  # 2) IOP Observations
  obs_full_urls: List[str] = []
  for idx, (eye, loinc_code, body_site, method_text) in enumerate(iop_specs):
    obs = _iop_observation_template(pid, eid, eye, loinc_code, body_site, method_text)
    # link to QR
    obs["derivedFrom"] = [{"reference": qr_full_url}]
    full_url = f"urn:uuid:{uuid.uuid4()}"
    obs_full_urls.append(full_url)
    entries.append({
      "fullUrl": full_url,
      "resource": obs,
      "request": {"method": "POST", "url": "Observation"},
      "author": performer_ref,
    })

  # 3) Duct patency (Dynamic Contour) for both eyes
  for idx, (eye, body_site) in enumerate([("OD", RIGHT_EYE_SNOMED), ("OS", LEFT_EYE_SNOMED)]):
    obs = _duct_observation_template(pid, eid, eye, body_site)
    obs["derivedFrom"] = [{"reference": qr_full_url}]
    entries.append({
      "fullUrl": f"urn:uuid:{uuid.uuid4()}",
      "resource": obs,
      "request": {"method": "POST", "url": "Observation"},
      "author": performer_ref,
    })

  # 4) CCT (Goldmann context) for both eyes
  for idx, (eye, body_site) in enumerate([("OD", RIGHT_EYE_SNOMED), ("OS", LEFT_EYE_SNOMED)]):
    obs = _cct_observation_template(pid, eid, eye, body_site, EXAM_CODE_GOLDMANN, EXAM_TEXT_GOLDMANN)
    obs["derivedFrom"] = [{"reference": qr_full_url}]
    entries.append({
      "fullUrl": f"urn:uuid:{uuid.uuid4()}",
      "resource": obs,
      "request": {"method": "POST", "url": "Observation"},
      "author": performer_ref,
    })

  # 5) List packaging
  list_entries = (
    [{"item": {"reference": qr_full_url}}]
    + [{"item": {"reference": fu}} for fu in obs_full_urls]
  )
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "IOP Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_IOP}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_IOP}]},
    "entry": list_entries,
    "author": performer_ref,
    }
  entries.append({
    "fullUrl": f"urn:uuid:{uuid.uuid4()}",
    "resource": list_body,
    "request": {"method": "POST", "url": "List"},
  })

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction IOP)")
  resp_bundle = _resp_json(resp)
  return resp_bundle


def fetch_iop(client, patient_id: str, encounter_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
  """
  Fetch IOP package by List anchor (preferred) including all members. Fallback to tag-based fetch.
  """
  pid = str(patient_id)
  params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_IOP}",
    "_include": ["List:item"],
    "_count": 50,
  }
  if encounter_id:
    params["encounter"] = f"Encounter/{encounter_id}"

  list_resp = client.get("List", params=params, name="GET /List?code=iop&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle

  # Fallback: QR and Observations by tag and optional encounter
  qr_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_IOP}",
    "_count": 50,
  }
  if encounter_id:
    qr_params["encounter"] = f"Encounter/{encounter_id}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=iop")
  qr_bundle = _resp_json(qr_resp) or {}

  obs_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_IOP}",
    "_count": 50,
  }
  if encounter_id:
    obs_params["encounter"] = f"Encounter/{encounter_id}"
  obs_resp = client.get("Observation", params=obs_params, name="GET /Observation?_tag=iop")
  obs_bundle = _resp_json(obs_resp) or {}

  entries = []
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



