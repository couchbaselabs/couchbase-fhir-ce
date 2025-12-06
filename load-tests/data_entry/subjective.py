from typing import Any, Dict, Optional
from faker import Faker
import random
from datetime import datetime, timezone
from typing import List
import uuid

fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_SUBJ = "subjective_refraction"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def create_subjective_refraction_form_with_fake_data(client,  patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref = {"reference": f"Practitioner/{user_id}"} if user_id else None

  is_dilated = random.choice([True, False])
  confounding = "Dilated" if is_dilated else "Not Dilated"

  def fake_ucva() -> str:
    return random.choice(["6/6", "6/9", "6/12", "6/18", "CF 3m"])  # comment string
  def fake_bcva() -> str:
    return random.choice(["6/6", "6/9", "6/12"])  # comment string
  def fake_nv_bcva() -> str:
    return random.choice(["N6", "N8", "N10"])  # comment string
  def fake_sph() -> float:
    return round(random.uniform(-6.0, 6.0), 2)
  def fake_cyl() -> float:
    return round(random.uniform(-3.0, 3.0), 2)
  def fake_axis() -> int:
    return int(random.randint(0, 180))
  def fake_add() -> float:
    return round(random.uniform(0.75, 3.0), 2)

  entries: List[Dict[str, Any]] = []

  # QuestionnaireResponse anchor
  qr_fu = f"urn:uuid:{uuid.uuid4()}"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
  }
  if performer_ref:
    qr["author"] = performer_ref
  entries.append({"fullUrl": qr_fu, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  # Observations for DV UCVA, DV BCVA (with lens), NV BCVA (with lens) per eye
  obs_fus: List[str] = []
  for side in ["right", "left"]:
    # DV UCVA
    o1 = {
      "resourceType": "Observation",
      "status": "final",
      "code": {"text": f"DV UCVA {side}"},
      "subject": {"reference": f"Patient/{pid}"},
      "encounter": {"reference": f"Encounter/{eid}"},
      "valueString": fake_ucva(),
      "note": [{"text": confounding}],
      "derivedFrom": [{"reference": qr_fu}],
      "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
      "bodySite": {"text": side},
    }
    fu1 = f"urn:uuid:{uuid.uuid4()}"
    obs_fus.append(fu1)
    entries.append({"fullUrl": fu1, "resource": o1, "request": {"method": "POST", "url": "Observation"}})

    # DV BCVA with lens
    sph = fake_sph(); cyl = fake_cyl(); axis = fake_axis()
    o2 = {
      "resourceType": "Observation",
      "status": "final",
      "code": {"text": f"DV BCVA {side}"},
      "subject": {"reference": f"Patient/{pid}"},
      "encounter": {"reference": f"Encounter/{eid}"},
      "valueString": fake_bcva(),
      "note": [{"text": confounding}],
      "derivedFrom": [{"reference": qr_fu}],
      "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
      "bodySite": {"text": side},
    }
    # Generate UUIDs for lens components
    lens_dv_sphere_id = f"urn:uuid:{uuid.uuid4()}"
    lens_dv_cylinder_id = f"urn:uuid:{uuid.uuid4()}"
    lens_dv_axis_id = f"urn:uuid:{uuid.uuid4()}"
    o2["hasMember"] = [
      {"reference": lens_dv_sphere_id},
      {"reference": lens_dv_cylinder_id},
      {"reference": lens_dv_axis_id},
    ]
    fu2 = f"urn:uuid:{uuid.uuid4()}"
    obs_fus.append(fu2)
    entries.append({"fullUrl": fu2, "resource": o2, "request": {"method": "POST", "url": "Observation"}})
    # lens components as Observations
    lens_specs = [
      ("sphere", sph, "D", lens_dv_sphere_id),
      ("cylinder", cyl, "D", lens_dv_cylinder_id),
      ("axis", axis, "deg", lens_dv_axis_id),
    ]
    for label, value, unit, lid in lens_specs:
      lo = {
        "resourceType": "Observation",
        "status": "final",
        "code": {"text": f"Lens {label} {side}"},
        "subject": {"reference": f"Patient/{pid}"},
        "encounter": {"reference": f"Encounter/{eid}"},
        "valueQuantity": {"value": value, "unit": unit},
        "derivedFrom": [{"reference": qr_fu}],
        "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
        "bodySite": {"text": side},
      }
      entries.append({"fullUrl": lid, "resource": lo, "request": {"method": "POST", "url": "Observation"}})

    # NV BCVA with lens (add)
    add = fake_add(); total = round(sph + add, 2)
    o3 = {
      "resourceType": "Observation",
      "status": "final",
      "code": {"text": f"NV BCVA {side}"},
      "subject": {"reference": f"Patient/{pid}"},
      "encounter": {"reference": f"Encounter/{eid}"},
      "valueString": fake_nv_bcva(),
      "note": [{"text": confounding,}],
      "derivedFrom": [{"reference": qr_fu}],
      "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
      "bodySite": {"text": side},
    }
    # Generate UUIDs for NV lens components
    lens_nv_sphere_id = f"urn:uuid:{uuid.uuid4()}"
    lens_nv_cylinder_id = f"urn:uuid:{uuid.uuid4()}"
    lens_nv_axis_id = f"urn:uuid:{uuid.uuid4()}"
    lens_nv_add_id = f"urn:uuid:{uuid.uuid4()}"
    o3["hasMember"] = [
      {"reference": lens_nv_sphere_id},
      {"reference": lens_nv_cylinder_id},
      {"reference": lens_nv_axis_id},
      {"reference": lens_nv_add_id},
    ]
    fu3 = f"urn:uuid:{uuid.uuid4()}"
    obs_fus.append(fu3)
    entries.append({"fullUrl": fu3, "resource": o3, "request": {"method": "POST", "url": "Observation"}})
    lens_specs_nv = [
      ("sphere", total, "D", lens_nv_sphere_id),
      ("cylinder", cyl, "D", lens_nv_cylinder_id),
      ("axis", axis, "deg", lens_nv_axis_id),
      ("add", add, "D", lens_nv_add_id),
    ]
    for label, value, unit, lid in lens_specs_nv:
      lo = {
        "resourceType": "Observation",
        "status": "final",
        "code": {"text": f"Lens {label} {side}"},
        "subject": {"reference": f"Patient/{pid}"},
        "encounter": {"reference": f"Encounter/{eid}"},
        "valueQuantity": {"value": value, "unit": unit},
        "derivedFrom": [{"reference": qr_fu}],
        "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
        "bodySite": {"text": side},
      }
      entries.append({"fullUrl": lid, "resource": lo, "request": {"method": "POST", "url": "Observation"}})

  # List packaging
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "Subjective Refraction Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_SUBJ}]},
    "entry": ([{"item": {"reference": qr_fu}}] + [{"item": {"reference": fu}} for fu in obs_fus]),
  }
  entries.append({"fullUrl": f"urn:uuid:{uuid.uuid4()}", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction subjective-refraction)")
  return _resp_json(resp)


def fetch_subjective_refraction(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {"subject": f"Patient/{pid}", "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SUBJ}", "_include": ["List:item"], "_count": 50}
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"
  list_resp = client.get("List", params=params, name="GET /List?code=subjective_refraction&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle
  # Fallback by tag
  qr_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SUBJ}", "_count": 50}
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=subjective_refraction")
  qr_bundle = _resp_json(qr_resp) or {}
  obs_params: Dict[str, Any] = {"subject": f"Patient/{pid}", "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_SUBJ}", "_count": 50}
  if encounter_id is not None:
    obs_params["encounter"] = f"Encounter/{str(encounter_id)}"
  obs_resp = client.get("Observation", params=obs_params, name="GET /Observation?_tag=subjective_refraction")
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

