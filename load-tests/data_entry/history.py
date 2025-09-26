from typing import Any, Dict, Optional
from faker import Faker
from datetime import datetime, timezone
from typing import List

fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_HISTORY = "treatment_history"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_history_form_with_fake_data(client,  patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  procedure_name = fake.word()
  problem_diagnosis_name = fake.word()

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = "urn:uuid:qr-history"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_HISTORY}]},
  }
  if performer_ref:
    qr["author"] = performer_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Procedure (past procedure)
  proc_fu = "urn:uuid:proc-history"
  proc = {
    "resourceType": "Procedure",
    "status": "completed",
    "code": {"text": procedure_name},
    "category": {"text": "Past Procedure from Treatment History"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "performedDateTime": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_HISTORY}]},
  }
  if performer_ref:
    proc["performer"] = [{"actor": performer_ref}]
  entries.append({"fullUrl": proc_fu, "resource": proc, "request": {"method": "POST", "url": "Procedure"}})

  # Condition (past diagnosis)
  cond_fu = "urn:uuid:cond-history"
  cond = {
    "resourceType": "Condition",
    "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
    "code": {"text": problem_diagnosis_name},
    "category": [{"text": "Past Diagnosis from Treatment History"}],
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "evidence": [{"detail": [{"reference": qr_fu}]}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_HISTORY}]},
  }
  if performer_ref:
    cond["asserter"] = performer_ref
  entries.append({"fullUrl": cond_fu, "resource": cond, "request": {"method": "POST", "url": "Condition"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Treatment History Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_HISTORY}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_HISTORY}]},
    "entry": [{"item": {"reference": qr_fu}}, {"item": {"reference": proc_fu}}, {"item": {"reference": cond_fu}}],
  }
  entries.append({"fullUrl": "urn:uuid:list-history", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction treatment-history)")
  return _resp_json(resp)


def fetch_history(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_HISTORY}", "_include": ["List:item"], "_count": 200}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=treatment_history&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback by tag
  qr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_HISTORY}", "_count": 100}
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=treatment_history")
  qr_bundle = _resp_json(qr_resp) or {}
  proc_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_HISTORY}", "_count": 100}
  if encounter_id is not None:
    proc_params["encounter"] = f"Encounter/{str(encounter_id)}"
  proc_resp = client.get("Procedure", params=proc_params, name="GET /Procedure?_tag=treatment_history")
  proc_bundle = _resp_json(proc_resp) or {}
  cond_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_HISTORY}", "_count": 100}
  if encounter_id is not None:
    cond_params["encounter"] = f"Encounter/{str(encounter_id)}"
  cond_resp = client.get("Condition", params=cond_params, name="GET /Condition?_tag=treatment_history")
  cond_bundle = _resp_json(cond_resp) or {}
  entries = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (proc_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (cond_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or proc_bundle or cond_bundle


