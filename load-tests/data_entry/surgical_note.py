from typing import Any, Dict, Optional
from faker import Faker
from datetime import datetime, timedelta, timezone
import uuid


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_SURG = "surgical_notes"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def fetch_surgical_notes(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SURG}", "_include": ["List:item"], "_count": 200}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=surgical_notes&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback
  def _fetch(path: str) -> Dict[str, Any]:
    p: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SURG}", "_count": 200}
    if encounter_id is not None:
      p["encounter"] = f"Encounter/{str(encounter_id)}"
    r = client.get(path, params=p, name=f"GET /{path}?_tag=surgical_notes")
    try:
      return r.json() or {}
    except Exception:
      return {}
  qr_bundle = _fetch("QuestionnaireResponse")
  proc_bundle = _fetch("Procedure")
  dev_bundle = _fetch("Device")
  dr_bundle = _fetch("DiagnosticReport")
  cond_bundle = _fetch("Condition")
  comp_bundle = _fetch("Composition")
  entries = []
  for b in [qr_bundle, proc_bundle, dev_bundle, dr_bundle, cond_bundle, comp_bundle]:
    for e in (b.get("entry") or []):
      res = e.get("resource") if isinstance(e, dict) else None
      if res:
        entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or proc_bundle or dev_bundle or dr_bundle or cond_bundle or comp_bundle



def create_surgical_notes_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  surgeon_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  start = datetime.now(timezone.utc)
  end = start + timedelta(hours=2)

  body_site_text = "Right Eye"
  body_site_code = "1290032005"
  body_site_system = "http://snomed.info/sct"

  procedure_name = "Cataract Surgery"
  note = fake.sentence(nb_words=6)
  complaints = "Blurry vision"
  complications = "None"
  outcome = "Stable"

  implant_name = "IOL"
  implant_type = "Acrylic"
  implant_serial = fake.bothify(text="SN-####-####")

  preop_conclusion = "Blood tests within normal limits"
  diagnosis_text = "Cataract"
  discharge_summary = "Discharged in stable condition"

  entries: list[dict] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
  }
  if surgeon_ref:
    qr["author"] = surgeon_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Procedure (report will reference DiagnosticReport created below)
  proc_fu = f"urn:uuid:{uuid.uuid4()}"
  proc = {
    "resourceType": "Procedure",
    "status": "completed",
    "code": {"text": procedure_name},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "performedPeriod": {"start": start.isoformat(), "end": end.isoformat()},
    "reasonCode": [{"text": complaints}],
    "bodySite": [{"coding": [{"system": body_site_system, "code": body_site_code}], "text": body_site_text}],
    "note": [{"text": note}],
    "complication": [{"text": complications}],
    "outcome": {"text": outcome},
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
  }
  if surgeon_ref:
    proc["performer"] = [{"actor": surgeon_ref, "function": {"text": "Surgeon"}}]
  entries.append({"fullUrl": proc_fu, "resource": proc, "request": {"method": "POST", "url": "Procedure"}})

  # Device (implant)
  dev_fu = f"urn:uuid:{uuid.uuid4()}"
  device = {
    "resourceType": "Device",
    "status": "active",
    "deviceName": [{"name": implant_name, "type": "manufacturer-name"}],
    "type": {"text": implant_type},
    "distinctIdentifier": implant_serial,
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
  }
  entries.append({"fullUrl": dev_fu, "resource": device, "request": {"method": "POST", "url": "Device"}})

  # Pre-op DiagnosticReport (simple text result)
  dr_fu = f"urn:uuid:{uuid.uuid4()}"
  dr = {
    "resourceType": "DiagnosticReport",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/v2-0074", "code": "LAB"}]}],
    "code": {"text": "Pre-op investigations"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "effectiveDateTime": _now_iso(),
    "conclusion": preop_conclusion,
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
  }
  entries.append({"fullUrl": dr_fu, "resource": dr, "request": {"method": "POST", "url": "DiagnosticReport"}})
  # Now that DiagnosticReport is defined, add the valid report reference to Procedure
  proc["report"] = [{"reference": dr_fu}]

  # Condition (diagnosis)
  cond_fu = f"urn:uuid:{uuid.uuid4()}"
  cond = {
    "resourceType": "Condition",
    "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
    "code": {"text": diagnosis_text},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "evidence": [{"detail": [{"reference": qr_fu}]}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
  }
  entries.append({"fullUrl": cond_fu, "resource": cond, "request": {"method": "POST", "url": "Condition"}})

  # Discharge summary as Composition
  comp_fu = f"urn:uuid:{uuid.uuid4()}"
  comp = {
    "resourceType": "Composition",
    "status": "final",
    "type": {"text": "Discharge summary"},
    "date": _now_iso(),
    "title": "Surgical Discharge Summary",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "author": [surgeon_ref] if surgeon_ref else [],
    "section": [{"title": "Summary", "text": {"status": "generated", "div": f"<div>{discharge_summary}</div>"}}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
  }
  entries.append({"fullUrl": comp_fu, "resource": comp, "request": {"method": "POST", "url": "Composition"}})

  # Parent List anchor
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Surgical Notes Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SURG}]},
    "entry": (
      [{"item": {"reference": qr_fu}}]
      + [{"item": {"reference": x}} for x in [proc_fu, dev_fu, dr_fu, cond_fu, comp_fu]]
    ),
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction surgical-notes)")
  return _resp_json(resp)


