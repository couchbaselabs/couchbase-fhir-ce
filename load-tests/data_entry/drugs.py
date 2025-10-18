from typing import Any, Dict, Optional
from faker import Faker
from datetime import datetime, timedelta, timezone
from typing import List
import uuid


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_DRUG = "drug_prescription"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_drug_prescription_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  requester_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  now = datetime.now(timezone.utc)
  start = now
  stop = now + timedelta(days=7)

  diag_eye = "Right eye"
  diag_text = fake.sentence(nb_words=3)
  advice_text = fake.sentence(nb_words=6)
  inv_text = "Dilated fundus exam"
  inv_eye = "Both eyes"
  follow_up_desc = "Review after a week"
  med_eye = "Right eye"
  med_drug = fake.word().title()
  med_qty_opt = "1 pack"
  freq = "1-0-1"
  qty_text = "1 tablet"

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
  }
  if requester_ref:
    qr["author"] = requester_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Condition for diagnosis
  cond_fu = f"urn:uuid:{uuid.uuid4()}"
  cond = {
    "resourceType": "Condition",
    "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
    "code": {"text": diag_text},
    "bodySite": [{"text": diag_eye}],
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "evidence": [{"detail": [{"reference": qr_fu}]}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
  }
  entries.append({"fullUrl": cond_fu, "resource": cond, "request": {"method": "POST", "url": "Condition"}})

  # Communication for recommendation/advice
  comm_fu = f"urn:uuid:{uuid.uuid4()}"
  comm = {
    "resourceType": "Communication",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "payload": [{"contentString": advice_text}],
    "sent": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
    "supportingInformation": [{"reference": qr_fu}],
  }
  if requester_ref:
    comm["sender"] = requester_ref
  entries.append({"fullUrl": comm_fu, "resource": comm, "request": {"method": "POST", "url": "Communication"}})

  # ServiceRequest: Investigation
  sr_inv_fu = f"urn:uuid:{uuid.uuid4()}"
  sr_inv = {
    "resourceType": "ServiceRequest",
    "status": "active",
    "intent": "order",
    "code": {"text": "INVESTIGATION"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "reasonCode": [{"text": inv_eye}],
    "note": [{"text": inv_text}],
    "authoredOn": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
    "supportingInfo": [{"reference": qr_fu}],
  }
  if requester_ref:
    sr_inv["requester"] = requester_ref
  entries.append({"fullUrl": sr_inv_fu, "resource": sr_inv, "request": {"method": "POST", "url": "ServiceRequest"}})

  # ServiceRequest: Follow-up
  sr_fu_fu = f"urn:uuid:{uuid.uuid4()}"
  sr_fu_res = {
    "resourceType": "ServiceRequest",
    "status": "active",
    "intent": "plan",
    "code": {"text": "FOLLOW UP"},
    "occurrenceDateTime": stop.replace(microsecond=0).isoformat(),
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "note": [{"text": follow_up_desc}],
    "authoredOn": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
    "supportingInfo": [{"reference": qr_fu}],
  }
  if requester_ref:
    sr_fu_res["requester"] = requester_ref
  entries.append({"fullUrl": sr_fu_fu, "resource": sr_fu_res, "request": {"method": "POST", "url": "ServiceRequest"}})

  # MedicationRequest
  mr_fu = f"urn:uuid:{uuid.uuid4()}"
  med_req = {
    "resourceType": "MedicationRequest",
    "status": "active",
    "intent": "order",
    "medicationCodeableConcept": {"text": med_drug},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authoredOn": _now_iso(),
    "priority": "routine",
    "reasonReference": [{"reference": cond_fu}],
    "dosageInstruction": [
      {
        "text": qty_text,
        "patientInstruction": med_qty_opt,
        "additionalInstruction": [{"text": med_qty_opt}],
        "timing": {"code": {"text": freq}, "repeat": {"boundsPeriod": {"start": start.isoformat(), "end": stop.isoformat()}}},
        "site": {"text": med_eye},
      }
    ],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
    "supportingInformation": [{"reference": qr_fu}],
  }
  if requester_ref:
    med_req["requester"] = requester_ref
  entries.append({"fullUrl": mr_fu, "resource": med_req, "request": {"method": "POST", "url": "MedicationRequest"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Drug Prescription Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_DRUG}]},
    "entry": [
      {"item": {"reference": qr_fu}},
      {"item": {"reference": cond_fu}},
      {"item": {"reference": comm_fu}},
      {"item": {"reference": sr_inv_fu}},
      {"item": {"reference": sr_fu_fu}},
      {"item": {"reference": mr_fu}},
    ],
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction drug-prescription)")
  return _resp_json(resp)


def fetch_drug_prescription(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_DRUG}", "_include": ["List:item"], "_count": 200}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=drug_prescription&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback by tag
  def _fetch(path: str) -> Dict[str, Any]:
    p: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_DRUG}", "_count": 200}
    if encounter_id is not None:
      p["encounter"] = f"Encounter/{str(encounter_id)}"
    r = client.get(path, params=p, name=f"GET /{path}?_tag=drug_prescription")
    try:
      return r.json() or {}
    except Exception:
      return {}
  qr_bundle = _fetch("QuestionnaireResponse")
  cond_bundle = _fetch("Condition")
  comm_bundle = _fetch("Communication")
  sr_bundle = _fetch("ServiceRequest")
  mr_bundle = _fetch("MedicationRequest")
  entries = []
  for b in [qr_bundle, cond_bundle, comm_bundle, sr_bundle, mr_bundle]:
    for e in (b.get("entry") or []):
      res = e.get("resource") if isinstance(e, dict) else None
      if res:
        entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or cond_bundle or comm_bundle or sr_bundle or mr_bundle


