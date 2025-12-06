from typing import Any, Dict, Optional
from faker import Faker
import random
from datetime import datetime, timezone
from typing import List
import uuid


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_LAB = "lab"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_lab_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  biochem_analyte_name = random.choice(["Glucose (Fasting)", "Glucose (PP)", "HbA1c"])  # example values
  biochem_analyte_result = random.choice(["Normal", "High", "Low"])  # example values
  serology_method = random.choice(["HIV", "HBsAg", "HCV"])  # example values
  serology_conclusion = random.choice(["Reactive", "Non-reactive"])  # example values

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_LAB}]},
  }
  if performer_ref:
    qr["author"] = performer_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Biochemistry Observation
  obs_biochem_fu = f"urn:uuid:{uuid.uuid4()}"
  obs_biochem = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory"}]}],
    "code": {"text": biochem_analyte_name},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueCodeableConcept": {"text": biochem_analyte_result},
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_LAB}]},
  }
  if performer_ref:
    obs_biochem["performer"] = [performer_ref]
  entries.append({"fullUrl": obs_biochem_fu, "resource": obs_biochem, "request": {"method": "POST", "url": "Observation"}})

  # Serology Observation
  obs_ser_fu = f"urn:uuid:{uuid.uuid4()}"
  obs_ser = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory"}]}],
    "code": {"text": serology_method},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueCodeableConcept": {"text": serology_conclusion},
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_LAB}]},
  }
  if performer_ref:
    obs_ser["performer"] = [performer_ref]
  entries.append({"fullUrl": obs_ser_fu, "resource": obs_ser, "request": {"method": "POST", "url": "Observation"}})

  # DiagnosticReport referencing both observations
  dr_fu = f"urn:uuid:{uuid.uuid4()}"
  dr = {
    "resourceType": "DiagnosticReport",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0074", "code": "LAB"}]}],
    "code": {"text": "Laboratory report"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "effectiveDateTime": _now_iso(),
    "issued": _now_iso(),
    "result": [{"reference": obs_biochem_fu}, {"reference": obs_ser_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_LAB}]},
  }
  entries.append({"fullUrl": dr_fu, "resource": dr, "request": {"method": "POST", "url": "DiagnosticReport"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Lab Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_LAB}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_LAB}]},
    "entry": [{"item": {"reference": qr_fu}}, {"item": {"reference": dr_fu}}, {"item": {"reference": obs_biochem_fu}}, {"item": {"reference": obs_ser_fu}}],
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction lab)")
  return _resp_json(resp)


def fetch_lab(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_LAB}", "_include": ["List:item"], "_count": 50}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=lab&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback by tag
  qr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_LAB}", "_count": 50}
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=lab")
  qr_bundle = _resp_json(qr_resp) or {}
  obs_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_LAB}", "_count": 50}
  if encounter_id is not None:
    obs_params["encounter"] = f"Encounter/{str(encounter_id)}"
  obs_resp = client.get("Observation", params=obs_params, name="GET /Observation?_tag=lab")
  obs_bundle = _resp_json(obs_resp) or {}
  dr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_LAB}", "_count": 50}
  if encounter_id is not None:
    dr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  dr_resp = client.get("DiagnosticReport", params=dr_params, name="GET /DiagnosticReport?_tag=lab")
  dr_bundle = _resp_json(dr_resp) or {}
  entries = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (obs_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (dr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or obs_bundle or dr_bundle


