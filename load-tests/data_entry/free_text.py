from typing import Any, Dict, Optional
from faker import Faker
from datetime import datetime, timezone
from typing import List
import uuid

fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_FREE_TEXT = "free_text"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_free_text_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  author_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  text = fake.text()

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_FREE_TEXT}]},
  }
  if author_ref:
    qr["author"] = author_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Communication for free text
  comm_fu = f"urn:uuid:{uuid.uuid4()}"
  comm = {
    "resourceType": "Communication",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "payload": [{"contentString": str(text)}],
    "sent": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_FREE_TEXT}]},
    "basedOn": [{"reference": qr_fu}],
  }
  if author_ref:
    comm["sender"] = author_ref
  entries.append({"fullUrl": comm_fu, "resource": comm, "request": {"method": "POST", "url": "Communication"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Free Text Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_FREE_TEXT}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_FREE_TEXT}]},
    "entry": [{"item": {"reference": qr_fu}}, {"item": {"reference": comm_fu}}],
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction free-text)")
  return _resp_json(resp)


def fetch_free_text(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_FREE_TEXT}",
    "_include": ["List:item"],
    "_count": 200,
  }
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=free_text&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle

  # Fallback by tag
  qr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_FREE_TEXT}", "_count": 100}
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=free_text")
  qr_bundle = _resp_json(qr_resp) or {}

  comm_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_FREE_TEXT}", "_count": 100}
  if encounter_id is not None:
    comm_params["encounter"] = f"Encounter/{str(encounter_id)}"
  comm_resp = client.get("Communication", params=comm_params, name="GET /Communication?_tag=free_text")
  comm_bundle = _resp_json(comm_resp) or {}

  entries = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (comm_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or comm_bundle