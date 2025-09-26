from typing import Any, Dict, Optional
from faker import Faker
import random
from datetime import datetime, timezone
from typing import List


fake = Faker()



FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_ASCAN = "a_scan"
SNOMED = "http://snomed.info/sct"
UCUM = "http://unitsofmeasure.org"
OBS_CATEGORY_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/observation-category"
OBS_CATEGORY_ASCAN = "ascan"

RIGHT_EYE_SNOMED = "1290032005"
LEFT_EYE_SNOMED = "1290031003"
RIGHT_EYE_TEXT = "Right Eye"
LEFT_EYE_TEXT = "Left Eye"

ASCAN_CODE_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/ascan-measurement"
ASCAN_METHOD_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/ascan-method"


def _now_iso() -> str:
  return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _resp_json(resp) -> Optional[Dict[str, Any]]:
  if resp is None or not getattr(resp, "ok", False):
    return None
  try:
    return resp.json() or {}
  except Exception:
    return None


def _method_cc(method_text: str) -> Dict[str, Any]:
  code = method_text.strip().lower().replace(" ", "-")
  return {"coding": [{"system": ASCAN_METHOD_SYSTEM, "code": code}], "text": method_text}


def _obs_quantity(pid: str, eid: str, *, code: str, text: str, unit: str, value: float, body_site_code: str, body_site_text: str, method_text: str, performer_ref: Optional[str], qr_full_url: str) -> Dict[str, Any]:
  obs: Dict[str, Any] = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": OBS_CATEGORY_ASCAN}]}],
    "code": {"coding": [{"system": ASCAN_CODE_SYSTEM, "code": code}], "text": text},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_site_code}], "text": body_site_text},
    "method": _method_cc(method_text),
    "valueQuantity": {"value": value, "unit": unit, "system": UCUM, "code": unit},
    "derivedFrom": [{"reference": qr_full_url}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]},
  }
  if performer_ref:
    obs["performer"] = [{"reference": performer_ref}]
  return obs


def _obs_integer(pid: str, eid: str, *, code: str, text: str, value: int, body_site_code: str, body_site_text: str, method_text: str, performer_ref: Optional[str], qr_full_url: str) -> Dict[str, Any]:
  obs: Dict[str, Any] = {
    "resourceType": "Observation",
    "status": "final",
    "category": [{"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": OBS_CATEGORY_ASCAN}]}],
    "code": {"coding": [{"system": ASCAN_CODE_SYSTEM, "code": code}], "text": text},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "bodySite": {"coding": [{"system": SNOMED, "code": body_site_code}], "text": body_site_text},
    "method": _method_cc(method_text),
    "valueInteger": value,
    "derivedFrom": [{"reference": qr_full_url}],
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]},
  }
  if performer_ref:
    obs["performer"] = [{"reference": performer_ref}]
  return obs


def create_ascan_form_with_fake_data(client, patient_id: Any, encounter_id: Any, user_id: Any) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  eid = str(encounter_id)
  performer_ref: Optional[str] = f"Practitioner/{user_id}" if user_id else None

  def rand_len() -> float:
    return round(random.uniform(20.0, 28.0), 2)
  def rand_k() -> float:
    return round(random.uniform(36.0, 48.0), 2)
  def rand_srk() -> float:
    return round(random.uniform(1.0, 3.0), 2)
  def rand_optbio() -> float:
    return round(random.uniform(20.0, 28.0), 2)

  right = {
    "method": random.choice(["Contact", "Immersion", "Optical"]),
    "ax1": rand_len(), "ax2": rand_len(), "ax3": rand_len(),
    "k1_auto": rand_k(), "k1_norm": rand_k(),
    "k2_auto": rand_k(), "k2_norm": rand_k(),
    "preop_astig": round(random.uniform(0.0, 5.0), 2),
    "with_rule": int(random.choice([0, 1])),
    "against_rule": int(random.choice([0, 1])),
    "srk_t1": rand_srk(), "srk_t2": rand_srk(), "srk_t3": rand_srk(),
    "srk_21": rand_srk(), "srk_22": rand_srk(), "srk_23": rand_srk(),
    "opt1": rand_optbio(), "opt2": rand_optbio(), "opt3": rand_optbio(),
    "final_iol": round(random.uniform(10.0, 30.0), 2),
    "iol_brand": random.choice(["Alcon", "Zeiss", "J&J", "Bausch & Lomb"]),
    "body_code": RIGHT_EYE_SNOMED, "body_text": RIGHT_EYE_TEXT,
  }
  left = {
    "method": random.choice(["Contact", "Immersion", "Optical"]),
    "ax1": rand_len(), "ax2": rand_len(), "ax3": rand_len(),
    "k1_auto": rand_k(), "k1_norm": rand_k(),
    "k2_auto": rand_k(), "k2_norm": rand_k(),
    "preop_astig": round(random.uniform(0.0, 5.0), 2),
    "with_rule": int(random.choice([0, 1])),
    "against_rule": int(random.choice([0, 1])),
    "srk_t1": rand_srk(), "srk_t2": rand_srk(), "srk_t3": rand_srk(),
    "srk_21": rand_srk(), "srk_22": rand_srk(), "srk_23": rand_srk(),
    "opt1": rand_optbio(), "opt2": rand_optbio(), "opt3": rand_optbio(),
    "final_iol": round(random.uniform(10.0, 30.0), 2),
    "iol_brand": random.choice(["Alcon", "Zeiss", "J&J", "Bausch & Lomb"]),
    "body_code": LEFT_EYE_SNOMED, "body_text": LEFT_EYE_TEXT,
  }

  entries: List[Dict[str, Any]] = []

  qr_full_url = "urn:uuid:qr-ascan"
  qr = {
    "resourceType": "QuestionnaireResponse",
    "status": "completed",
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "authored": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]},
  }
  if performer_ref:
    qr["author"] = {"reference": performer_ref}
  entries.append({"fullUrl": qr_full_url, "resource": qr, "request": {"method": "POST", "url": "QuestionnaireResponse"}})

  dev_right_fu = "urn:uuid:dev-iol-right"
  dev_left_fu = "urn:uuid:dev-iol-left"
  dev_type = {"text": "Intraocular lens"}
  device_right = {"resourceType": "Device", "status": "active", "type": dev_type, "deviceName": [{"name": right["iol_brand"], "type": "manufacturer-name"}], "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]}}
  device_left = {"resourceType": "Device", "status": "active", "type": dev_type, "deviceName": [{"name": left["iol_brand"], "type": "manufacturer-name"}], "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]}}
  entries.append({"fullUrl": dev_right_fu, "resource": device_right, "request": {"method": "POST", "url": "Device"}})
  entries.append({"fullUrl": dev_left_fu, "resource": device_left, "request": {"method": "POST", "url": "Device"}})

  def add_eye_obs(side: Dict[str, Any], prefix: str, dev_fu: str) -> List[str]:
    created_fus: List[str] = []
    specs_q = [
      (f"{prefix} axial_length_1", "axial_length_1", "mm", side["ax1"]),
      (f"{prefix} axial_length_2", "axial_length_2", "mm", side["ax2"]),
      (f"{prefix} axial_length_3", "axial_length_3", "mm", side["ax3"]),
      (f"{prefix} K1 auto-K", "k1_auto_k", "D", side["k1_auto"]),
      (f"{prefix} K1 normal", "k1_normal", "D", side["k1_norm"]),
      (f"{prefix} K2 auto-K", "k2_auto_k", "D", side["k2_auto"]),
      (f"{prefix} K2 normal", "k2_normal", "D", side["k2_norm"]),
      (f"{prefix} pre-op astigmatism", "pre_op_astigmatism", "D", side["preop_astig"]),
      (f"{prefix} SRK-T1", "srk_t1", "D", side["srk_t1"]),
      (f"{prefix} SRK-T2", "srk_t2", "D", side["srk_t2"]),
      (f"{prefix} SRK-T3", "srk_t3", "D", side["srk_t3"]),
      (f"{prefix} SRK-II 1", "srk_2_1", "D", side["srk_21"]),
      (f"{prefix} SRK-II 2", "srk_2_2", "D", side["srk_22"]),
      (f"{prefix} SRK-II 3", "srk_2_3", "D", side["srk_23"]),
      (f"{prefix} optical biometry 1", "optical_biometry_1", "mm", side["opt1"]),
      (f"{prefix} optical biometry 2", "optical_biometry_2", "mm", side["opt2"]),
      (f"{prefix} optical biometry 3", "optical_biometry_3", "mm", side["opt3"]),
      (f"{prefix} final IOL power", "final_iol_power", "D", side["final_iol"]),
    ]
    for idx, (text, code, unit, value) in enumerate(specs_q):
      obs = _obs_quantity(pid, eid, code=code, text=text, unit=unit, value=value, body_site_code=side["body_code"], body_site_text=side["body_text"], method_text=side["method"], performer_ref=performer_ref, qr_full_url=qr_full_url)
      if code == "final_iol_power":
        obs["device"] = {"reference": dev_fu}
      fu = f"urn:uuid:obs-{prefix.lower().replace(' ', '-')}-{code}-{idx}"
      created_fus.append(fu)
      entries.append({"fullUrl": fu, "resource": obs, "request": {"method": "POST", "url": "Observation"}})

    specs_i = [
      (f"{prefix} with-rule astigmatism", "with_rule", side["with_rule"]),
      (f"{prefix} against-rule astigmatism", "against_rule", side["against_rule"]),
    ]
    for idx, (text, code, value) in enumerate(specs_i):
      obs = _obs_integer(pid, eid, code=code, text=text, value=int(value), body_site_code=side["body_code"], body_site_text=side["body_text"], method_text=side["method"], performer_ref=performer_ref, qr_full_url=qr_full_url)
      fu = f"urn:uuid:obs-{prefix.lower().replace(' ', '-')}-{code}-i-{idx}"
      created_fus.append(fu)
      entries.append({"fullUrl": fu, "resource": obs, "request": {"method": "POST", "url": "Observation"}})
    return created_fus

  obs_fus_right = add_eye_obs(right, "Right Eye", dev_right_fu)
  obs_fus_left = add_eye_obs(left, "Left Eye", dev_left_fu)

  list_entries = (
    [{"item": {"reference": qr_full_url}}]
    + [{"item": {"reference": dev_right_fu}}]
    + [{"item": {"reference": dev_left_fu}}]
    + [{"item": {"reference": fu}} for fu in (obs_fus_right + obs_fus_left)]
  )
  list_body = {
    "resourceType": "List",
    "status": "current",
    "mode": "working",
    "title": "A-Scan Form",
    "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]},
    "subject": {"reference": f"Patient/{pid}"},
    "encounter": {"reference": f"Encounter/{eid}"},
    "date": _now_iso(),
    "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_ASCAN}]},
    "entry": list_entries,
  }
  entries.append({"fullUrl": "urn:uuid:list-ascan", "resource": list_body, "request": {"method": "POST", "url": "List"}})

  bundle = {"resourceType": "Bundle", "type": "transaction", "entry": entries}
  resp = client.post("", json=bundle, name="POST / (transaction a-scan)")
  return _resp_json(resp)


def fetch_ascan(client, patient_id: Any, encounter_id: Optional[Any] = None) -> Optional[Dict[str, Any]]:
  pid = str(patient_id)
  params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ASCAN}",
    "_include": ["List:item"],
    "_count": 200,
  }
  if encounter_id is not None:
    params["encounter"] = f"Encounter/{str(encounter_id)}"

  list_resp = client.get("List", params=params, name="GET /List?code=a_scan&_include=List:item")
  bundle = _resp_json(list_resp)
  if bundle:
    return bundle

  qr_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ASCAN}",
    "_count": 200,
  }
  if encounter_id is not None:
    qr_params["encounter"] = f"Encounter/{str(encounter_id)}"
  qr_resp = client.get("QuestionnaireResponse", params=qr_params, name="GET /QuestionnaireResponse?_tag=a_scan")
  qr_bundle = _resp_json(qr_resp) or {}

  obs_params: Dict[str, Any] = {
    "subject": f"Patient/{pid}",
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ASCAN}",
    "_count": 200,
  }
  if encounter_id is not None:
    obs_params["encounter"] = f"Encounter/{str(encounter_id)}"
  obs_resp = client.get("Observation", params=obs_params, name="GET /Observation?_tag=a_scan")
  obs_bundle = _resp_json(obs_resp) or {}

  dev_params: Dict[str, Any] = {
    "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_ASCAN}",
    "_count": 50,
  }
  dev_resp = client.get("Device", params=dev_params, name="GET /Device?_tag=a_scan")
  dev_bundle = _resp_json(dev_resp) or {}

  entries = []
  for e in (qr_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (obs_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})
  for e in (dev_bundle.get("entry") or []):
    res = e.get("resource") if isinstance(e, dict) else None
    if res:
      entries.append({"resource": res})

  if entries:
    return {"resourceType": "Bundle", "type": "collection", "entry": entries}
  return qr_bundle or obs_bundle or dev_bundle
