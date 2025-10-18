from typing import Any, Dict, Optional
from faker import Faker
import random
from datetime import datetime, timezone
from typing import List
import uuid

fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_PC = "posterior_chamber"
SNOMED = "http://snomed.info/sct"
OBS_CATEGORY_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/observation-category"
OBS_CATEGORY_POSTERIOR = "posterior-segment"
RIGHT_EYE_SNOMED = "1290032005"
LEFT_EYE_SNOMED = "1290031003"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


SYSTEMS = [
  {"system_text": "Structure of fundus of right eye", "system_code": "723298005", "body_text": "Right Eye", "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Structure of fundus of left eye", "system_code": "723299002", "body_text": "Left Eye", "body_code": LEFT_EYE_SNOMED},
  {"system_text": "Structure of macula lutea of right eye", "system_code": "721945009", "body_text": "Right Eye", "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Structure of macula lutea of left eye", "system_code": "721947001", "body_text": "Left Eye", "body_code": LEFT_EYE_SNOMED},
  {"system_text": "Structure of right optic disc", "system_code": "721900005", "body_text": "Right Eye", "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Structure of left optic disc", "system_code": "721899000", "body_text": "Left Eye", "body_code": LEFT_EYE_SNOMED},
  {"system_text": "Vitreous body structure of right eye", "system_code": "721959002", "body_text": "Right Eye", "body_code": RIGHT_EYE_SNOMED},
  {"system_text": "Vitreous body structure of left eye", "system_code": "721960007", "body_text": "Left Eye", "body_code": LEFT_EYE_SNOMED},
]


def _obs(pid: str, eid: str, *, system_text: str, system_code: str, body_text: str, body_code: str, value_text: str, performer_ref: Optional[str], qr_full_url: str) -> Dict[str, Any]:
  obs: Dict[str, Any] = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": OBS_CATEGORY_POSTERIOR}]}],
    "code": {"coding": [{"system": SNOMED, "code": system_code}], "text": system_text},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_code}], "text": body_text},
    "valueCodeableConcept": {"text": value_text},
    "derivedFrom": [{"reference": qr_full_url}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_PC}]},
  }
  if performer_ref:
    obs["performer"] = [{"reference": performer_ref}]
  return obs


def create_posterior_chamber_form_with_fake_data(client,  patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref: Optional[str] = f"Practitioner/{user_id}" if user_id else None
  text_choices = ["Normal", "Mild hyperemia", "Clear", "Pigmented", "Deep and quiet", "Shallow", "Cells and flare present"]
  pick = lambda: random.choice(text_choices)

  entries: List[Dict[str, Any]] = []

  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {"resourceType": "QuestionnaireResponse", "status": "completed", "subject": {"reference": f"Patient/{pid}"}, "encounter": {"reference": f"Encounter/{eid}"}, "authored": _now_iso(), "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_PC}]}}
  if performer_ref:
    qr["author"] = {"reference": performer_ref}
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  obs_fus: List[str] = []
  for idx, s in enumerate(SYSTEMS):
    o = _obs(pid, eid, system_text=s["system_text"], system_code=s["system_code"], body_text=s["body_text"], body_code=s["body_code"], value_text=pick(), performer_ref=performer_ref, qr_full_url=qr_fu)
    fu = f"urn:uuid:{uuid.uuid4()}"
    obs_fus.append(fu)
    entries.append({"fullUrl": fu, "resource": o, "request": {"method": "POST", "url": "Observation"}})

  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Posterior Chamber Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_PC}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_PC}]},
    "entry": ([{"item": {"reference": qr_fu}}] + [{"item": {"reference": fu}} for fu in obs_fus]),
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction posterior)")
  return _resp_json(resp)


def fetch_pc(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_PC}", "_include": ["List:item"], "_count": 200}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=posterior_chamber&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  qr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_PC}", "_count": 200}
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=posterior_chamber")
  qr_bundle = _resp_json(qr_resp) or {}
  obs_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_PC}", "_count": 200}
  if encounter_id is not None:
    obs_params["encounter"] = f"Encounter/{str(encounter_id)}"
  obs_resp = client.get("Observation", params=obs_params, name="GET /Observation?_tag=posterior_chamber")
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

