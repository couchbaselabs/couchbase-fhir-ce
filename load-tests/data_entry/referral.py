from typing import Any, Dict, Optional
from faker import Faker
from datetime import datetime, timezone
from typing import List
import uuid


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_REFERRAL = "referral"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_referral_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  referred_to = fake.company()
  reason = fake.sentence(nb_words=6)

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_REFERRAL}]},
  }
  if performer_ref:
    qr["author"] = performer_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # ServiceRequest for referral
  sr_fu = f"urn:uuid:{uuid.uuid4()}"
  sr = {
    "resourceType": "ServiceRequest",
    "status": "active",
    "intent": "order",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/service-category", "code": "referral"}]}],
    "code": {"text": "REFERRAL"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authoredOn": _now_iso(),
    "reasonCode": [{"text": reason}],
    "note": [{"text": f"Referred to: {referred_to}"}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_REFERRAL}]},
    "supportingInfo": [{"reference": qr_fu}],
  }
  if performer_ref:
    sr["requester"] = performer_ref
  entries.append({"fullUrl": sr_fu, "resource": sr, "request": {"method": "POST", "url": "ServiceRequest"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Referral Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_REFERRAL}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_REFERRAL}]},
    "entry": [{"item": {"reference": qr_fu}}, {"item": {"reference": sr_fu}}],
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction referral)")
  return _resp_json(resp)


def fetch_referral(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_REFERRAL}", "_include": ["List:item"], "_count": 200}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=referral&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback by tag
  qr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_REFERRAL}", "_count": 100}
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=referral")
  qr_bundle = _resp_json(qr_resp) or {}
  sr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_REFERRAL}", "_count": 100}
  if encounter_id is not None:
    sr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  sr_resp = client.get("ServiceRequest", params=sr_params, name="GET /ServiceRequest?_tag=referral")
  sr_bundle = _resp_json(sr_resp) or {}
  entries = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (sr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or sr_bundle


