from typing import Any, Dict, Optional
from faker import Faker
import random
from datetime import datetime, timezone
from typing import List
import uuid


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_SCREENING = "screening"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_screening_form_with_fake_data(client,  patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  bp_systolic = int(random.randint(100, 140))
  bp_diastolic = int(random.randint(60, 90))
  spo2 = int(random.randint(95, 100))
  pulse_rate = int(random.randint(60, 100))

  allergy_substance = random.choice(["Penicillin", "Dust", "Pollen", "Latex", "Aspirin"])
  allergy_active = random.choice([True, False])

  illness1 = random.choice(["Hypertension", "Diabetes Mellitus", "Asthma", "Hypothyroidism"])
  illness2 = random.choice(["Cataract", "Glaucoma", "Refractive Error", "Dry Eye"])

  education = random.choice(["Primary", "Secondary", "Graduate", "Postgraduate", "Vocational"])  # text
  travel_time = random.choice(["15 minutes", "30 minutes", "45 minutes", "1 hour"])  # text
  job_title = fake.job()

  blood_sugar = random.choice(["Normal", "High", "Low"])  # conclusion text
  ecg = random.choice(["Normal", "Abnormal"])              # conclusion text

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  if performer_ref:
    qr["author"] = performer_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Vitals: Blood Pressure (components), SpO2, Pulse
  bp_fu = f"urn:uuid:{uuid.uuid4()}"
  bp = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs"}]}],
    "code": {"text": "Blood pressure"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "component": [
      {"code": {"text": "Systolic"}, "valueQuantity": {"value": bp_systolic, "unit": "mmHg"}},
      {"code": {"text": "Diastolic"}, "valueQuantity": {"value": bp_diastolic, "unit": "mmHg"}},
    ],
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": bp_fu, "resource": bp, "request": {"method": "POST", "url": "Observation"}})

  spo2_fu = f"urn:uuid:{uuid.uuid4()}"
  spo2_obs = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs"}]}],
    "code": {"text": "SpO2"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueQuantity": {"value": spo2, "unit": "%"},
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": spo2_fu, "resource": spo2_obs, "request": {"method": "POST", "url": "Observation"}})

  pulse_fu = f"urn:uuid:{uuid.uuid4()}"
  pulse_obs = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "vital-signs"}]}],
    "code": {"text": "Pulse rate"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueQuantity": {"value": pulse_rate, "unit": "bpm"},
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": pulse_fu, "resource": pulse_obs, "request": {"method": "POST", "url": "Observation"}})

  # Allergy
  allergy_fu = f"urn:uuid:{uuid.uuid4()}"
  allergy = {
    "resourceType": "AllergyIntolerance",
    "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/allergyintolerance-clinical", "code": ("active" if allergy_active else "inactive")}]},
    "code": {"text": allergy_substance},
    "patient": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "recordedDate": _now_iso(),
    "asserter": performer_ref if performer_ref else None,
    "note": [{"text": "from screening"}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  # Remove None fields
  if allergy.get("asserter") is None:
    allergy.pop("asserter")
  entries.append({"fullUrl": allergy_fu, "resource": allergy, "request": {"method": "POST", "url": "AllergyIntolerance"}})

  # Conditions for pre-existing illnesses
  cond_fus: List[str] = []
  for idx, illness in enumerate([illness1, illness2]):
    c = {
      "resourceType": "Condition",
      "clinicalStatus": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/condition-clinical", "code": "active"}]},
      "code": {"text": illness},
      "category": [{"text": "Pre-existing illness"}],
      "subject": {"reference": f"Patient/{pid}"},
      "encounter": {"reference": f"Encounter/{eid}"},
      "note": [{"text": "timing: previous"}],
      "evidence": [{"detail": [{"reference": qr_fu}]}],
      "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
    }
    fu = f"urn:uuid:{uuid.uuid4()}"
    cond_fus.append(fu)
    entries.append({"fullUrl": fu, "resource": c, "request": {"method": "POST", "url": "Condition"}})

  # Education as Communication
  edu_fu = f"urn:uuid:{uuid.uuid4()}"
  edu = {
    "resourceType": "Communication",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "payload": [{"contentString": education}],
    "sent": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
    "basedOn": [{"reference": qr_fu}],
  }
  if performer_ref:
    edu["sender"] = performer_ref
  entries.append({"fullUrl": edu_fu, "resource": edu, "request": {"method": "POST", "url": "Communication"}})

  # Travel time & Occupation as Observations (social history)
  travel_fu = f"urn:uuid:{uuid.uuid4()}"
  travel = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "social-history"}]}],
    "code": {"text": "Travel time"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueString": travel_time,
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": travel_fu, "resource": travel, "request": {"method": "POST", "url": "Observation"}})

  job_fu = f"urn:uuid:{uuid.uuid4()}"
  job = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "social-history"}]}],
    "code": {"text": "Occupation"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueString": job_title,
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": job_fu, "resource": job, "request": {"method": "POST", "url": "Observation"}})

  # Lab screening observations
  bs_fu = f"urn:uuid:{uuid.uuid4()}"
  bs = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory"}]}],
    "code": {"text": "Blood Sugar"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueCodeableConcept": {"text": blood_sugar},
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": bs_fu, "resource": bs, "request": {"method": "POST", "url": "Observation"}})

  ecg_fu = f"urn:uuid:{uuid.uuid4()}"
  ecg_obs = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": "http://terminology.hl7.org/CodeSystem/observation-category", "code": "laboratory"}]}],
    "code": {"text": "ECG"},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "valueCodeableConcept": {"text": ecg},
    "derivedFrom": [{"reference": qr_fu}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
  }
  entries.append({"fullUrl": ecg_fu, "resource": ecg_obs, "request": {"method": "POST", "url": "Observation"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Screening Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SCREENING}]},
    "entry": (
      [{"item": {"reference": qr_fu}}]
      + [{"item": {"reference": x}} for x in [bp_fu, spo2_fu, pulse_fu, allergy_fu, edu_fu, travel_fu, job_fu, bs_fu, ecg_fu] + cond_fus]
    ),
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction screening)")
  return _resp_json(resp)


def fetch_screening(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SCREENING}", "_include": ["List:item"], "_count": 50}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=screening&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback by tag (fetch core types we created)
  def _fetch(path: str) -> Dict[str, Any]:
    p: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SCREENING}", "_count": 50}
    if encounter_id is not None:
      p["encounter"] = f"Encounter/{str(encounter_id)}"
    r = client.get(path, params=p, name=f"GET /{path}?_tag=screening")
    try:
      return r.json() or {}
    except Exception:
      return {}
  qr_bundle = _fetch("QuestionnaireResponse")
  obs_bundle = _fetch("Observation")
  cond_bundle = _fetch("Condition")
  all_bundle = _fetch("AllergyIntolerance")
  comm_bundle = _fetch("Communication")
  entries = []
  for b in [qr_bundle, obs_bundle, cond_bundle, all_bundle, comm_bundle]:
    for e in (b.get("entry") or []):
      res = e.get("resource") if isinstance(e, dict) else None
      if res:
        entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or obs_bundle or cond_bundle or all_bundle or comm_bundle


