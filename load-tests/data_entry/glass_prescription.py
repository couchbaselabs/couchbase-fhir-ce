from typing import Any, Dict, Optional
from faker import Faker
import random
from datetime import datetime, timezone
from typing import List
import uuid


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_GLASSES = "glass_prescription"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_glass_prescription_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  prescriber_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  # DV specs
  r_sph_dv = round(random.uniform(-6.0, 6.0), 2)
  r_cyl_dv = round(random.uniform(-3.0, 3.0), 2)
  r_axis_dv = int(random.randint(0, 180))
  l_sph_dv = round(random.uniform(-6.0, 6.0), 2)
  l_cyl_dv = round(random.uniform(-3.0, 3.0), 2)
  l_axis_dv = int(random.randint(0, 180))

  # NV add (near addition power)
  r_add_nv = round(random.uniform(0.5, 3.0), 2)
  l_add_nv = round(random.uniform(0.5, 3.0), 2)

  # NV sphere is DV sphere + add
  r_sph_nv = round(r_sph_dv + r_add_nv, 2)
  l_sph_nv = round(l_sph_dv + l_add_nv, 2)

  # Visual acuity strings
  snellen_options = ["6/6", "6/9", "6/12", "6/18", "6/24", "6/36"]
  near_options = ["N5", "N6", "N8", "N10"]

  dv_right_ucva = random.choice(snellen_options)
  dv_left_ucva = random.choice(snellen_options)
  dv_right_bcva = random.choice(snellen_options)
  dv_left_bcva = random.choice(snellen_options)
  nv_right_bcva = random.choice(near_options)
  nv_left_bcva = random.choice(near_options)

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_GLASSES}]},
  }
  if prescriber_ref:
    qr["author"] = prescriber_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # VisionPrescription DV
  vp_dv_fu = f"urn:uuid:{uuid.uuid4()}"
  vp_dv = {
    "resourceType": "VisionPrescription",
    "status": "active",
    "dateWritten": _now_iso(),
    "created": _now_iso(),
    "patient": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "lensSpecification": [
      {"product": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct", "code": "lens"}]}, "eye": "right", "sphere": r_sph_dv, "cylinder": r_cyl_dv, "axis": r_axis_dv, "add": 0},
      {"product": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct", "code": "lens"}]}, "eye": "left", "sphere": l_sph_dv, "cylinder": l_cyl_dv, "axis": l_axis_dv, "add": 0},
    ],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_GLASSES}]},
  }
  if prescriber_ref:
    vp_dv["prescriber"] = prescriber_ref
  entries.append({"fullUrl": vp_dv_fu, "resource": vp_dv, "request": {"method": "POST", "url": "VisionPrescription"}})

  # VisionPrescription NV
  vp_nv_fu = f"urn:uuid:{uuid.uuid4()}"
  vp_nv = {
    "resourceType": "VisionPrescription",
    "status": "active",
    "dateWritten": _now_iso(),
    "created": _now_iso(),
    "patient": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "lensSpecification": [
      {"product": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct", "code": "lens"}]}, "eye": "right", "sphere": r_sph_nv, "cylinder": r_cyl_dv, "axis": r_axis_dv, "add": r_add_nv},
      {"product": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct", "code": "lens"}]}, "eye": "left", "sphere": l_sph_nv, "cylinder": l_cyl_dv, "axis": l_axis_dv, "add": l_add_nv},
    ],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_GLASSES}]},
  }
  if prescriber_ref:
    vp_nv["prescriber"] = prescriber_ref
  entries.append({"fullUrl": vp_nv_fu, "resource": vp_nv, "request": {"method": "POST", "url": "VisionPrescription"}})

  # Visual acuity Observations (DV UCVA/BCVA; NV BCVA) per eye
  def va_obs(code_text: str, value: str, eye_text: str) -> Dict[str, Any]:
    return {
      "resourceType": "Observation",
      "status": "final",
      "code": {"text": code_text},
      "subject": {"reference": f"Patient/{pid}"},
      "encounter": {"reference": f"Encounter/{eid}"},
      "valueString": str(value),
      "derivedFrom": [{"reference": qr_fu}],
      "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_GLASSES}]},
      "bodySite": {"text": eye_text},
    }

  va_map = [
    ("DV UCVA", dv_right_ucva, "right"),
    ("DV UCVA", dv_left_ucva, "left"),
    ("DV BCVA", dv_right_bcva, "right"),
    ("DV BCVA", dv_left_bcva, "left"),
    ("NV BCVA", nv_right_bcva, "right"),
    ("NV BCVA", nv_left_bcva, "left"),
  ]
  va_fus: List[str] = []
  for idx, (label, value, side) in enumerate(va_map):
    o = va_obs(f"{label}", str(value), side)
    fu = f"urn:uuid:{uuid.uuid4()}"
    va_fus.append(fu)
    entries.append({"fullUrl": fu, "resource": o, "request": {"method": "POST", "url": "Observation"}})

  # List as parent anchor, coupling DV and NV prescriptions and VA together
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Glass Prescription Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_GLASSES}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_GLASSES}]},
    "entry": (
      [{"item": {"reference": qr_fu}}]
      + [{"item": {"reference": vp_dv_fu}}, {"item": {"reference": vp_nv_fu}}]
      + [{"item": {"reference": fu}} for fu in va_fus]
    ),
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction glass-prescription)")
  return _resp_json(resp)


def fetch_glass_prescription(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_GLASSES}",
    "_include": ["List:item"],
    "_count": 50,
  }
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=glass_prescription&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle

  # Fallback by tag
  def _fetch(path: str) -> Dict[str, Any]:
    p: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_GLASSES}", "_count": 50}
    if encounter_id is not None:
      p["encounter"] = f"Encounter/{str(encounter_id)}"
    r = client.get(path, params=p, name=f"GET /{path}?_tag=glass_prescription")
    try:
      return r.json() or {}
    except Exception:
      return {}

  qr_bundle = _fetch("QuestionnaireResponse")
  vp_bundle = _fetch("VisionPrescription")
  obs_bundle = _fetch("Observation")

  entries = []
  for b in [qr_bundle, vp_bundle, obs_bundle]:
    for e in (b.get("entry") or []):
      res = e.get("resource") if isinstance(e, dict) else None
      if res:
        entries.append({"resource": res})
  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or vp_bundle or obs_bundle


