from __future__ import annotations

from datetime import datetime, timezone
from typing import Optional, Dict, Any, List
from faker import Faker


fake = Faker()


FORM_TAG_SYSTEM = "https://medblocks.dev/fhir/form"
FORM_CODE_AUTOREF = "auto-refractometer"

OBS_CATEGORY_SYSTEM = "https://medblocks.dev/fhir/CodeSystem/observation-category"
OBS_CATEGORY_REFRACTION = "refraction"
LOINC = "http://loinc.org"
SNOMED = "http://snomed.info/sct"

# Eye body sites
RIGHT_EYE_SNOMED = "1290032005"
LEFT_EYE_SNOMED = "1290031003"

# General method (local valueset mirrored from Hasura)
GENERAL_METHOD_SYSTEM = "http://terminology.medblocks.com/Valueset/refraction-general-method"
GENERAL_METHOD_CODE = "at0227"
GENERAL_METHOD_TEXT = "Laser Interferometer"
CONFOUNDING_FACTORS_TEXT = "Not Dilated"


def _resp_json(resp) -> Optional[Dict[str, Any]]:
    if resp is None or not getattr(resp, "ok", False):
        return None
    try:
        return resp.json() or {}
    except Exception:
        return None


def _now_iso() -> str:
    return datetime.now(timezone.utc).replace(microsecond=0).isoformat()


def _build_refraction_measurements() -> Dict[str, Dict[str, float]]:
    # Generate OD/OS sphere, cylinder, axis values similar to Hasura
    return {
        "OD": {
            "sphere": round(fake.pyfloat(left_digits=1, right_digits=2, positive=False), 2),
            "cylinder": round(fake.pyfloat(left_digits=1, right_digits=2, positive=False), 2),
            "axis": int(fake.random_int(min=0, max=180)),
        },
        "OS": {
            "sphere": round(fake.pyfloat(left_digits=1, right_digits=2, positive=False), 2),
            "cylinder": round(fake.pyfloat(left_digits=1, right_digits=2, positive=False), 2),
            "axis": int(fake.random_int(min=0, max=180)),
        },
    }


def create_auto_refractometer_form_with_fake_data(client, patient_id: str, encounter_id: str, user_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """
    Creates a QuestionnaireResponse anchor and refraction Observations derived from it,
    then packages all into a List for single-call retrieval.
    Returns a dict with identifiers: { "questionnaireResponseId", "listId", "observationIds": [...] }.
    """
    pid = str(patient_id)
    eid = str(encounter_id)

    # 1) QuestionnaireResponse (anchor)
    qr = {
        "resourceType": "QuestionnaireResponse",
        "status": "completed",
        "subject": {"reference": f"Patient/{pid}"},
        "encounter": {"reference": f"Encounter/{eid}"},
        "authored": _now_iso(),
        "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_AUTOREF}]},
    }
    qr_resp = client.post("QuestionnaireResponse", json=qr, name="POST /QuestionnaireResponse (auto-refractometer)")
    if not getattr(qr_resp, "ok", False):
        return None
    qr_id = (qr_resp.json() or {}).get("id")

    # 2) Observations linked to QR, with method, bodySite, confounding note
    values = _build_refraction_measurements()
    observation_ids: List[str] = []
    # Build OD and OS observations for sphere, cylinder, axis
    def make_obs(eye_label: str, loinc_code: str, text: str, value: Any, unit: str) -> Dict[str, Any]:
        body_site_code = RIGHT_EYE_SNOMED if eye_label == "OD" else LEFT_EYE_SNOMED
        obs = {
            "resourceType": "Observation",
            "status": "final",
            "code": {"coding": [{"system": LOINC, "code": loinc_code}], "text": text},
            "subject": {"reference": f"Patient/{pid}"},
            "encounter": {"reference": f"Encounter/{eid}"},
            "derivedFrom": [{"reference": f"QuestionnaireResponse/{qr_id}"}],
            "category": [{"coding": [{"system": OBS_CATEGORY_SYSTEM, "code": OBS_CATEGORY_REFRACTION}]}],
            "method": {"coding": [{"system": GENERAL_METHOD_SYSTEM, "code": GENERAL_METHOD_CODE}], "text": GENERAL_METHOD_TEXT},
            "bodySite": {"coding": [{"system": SNOMED, "code": body_site_code}], "text": ("Right Eye" if eye_label == "OD" else "Left Eye")},
            "note": [{"text": CONFOUNDING_FACTORS_TEXT}],
            "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_AUTOREF}]},
        }
        qty = {"valueQuantity": {"value": value, "unit": unit}}
        obs.update(qty)
        return obs

    mapping = [
        ("OD", "9780-8", "OD Sphere", values["OD"]["sphere"], "D"),
        ("OS", "9781-6", "OS Sphere", values["OS"]["sphere"], "D"),
        ("OD", "17634-2", "OD Cylinder", values["OD"]["cylinder"], "D"),
        ("OS", "17635-9", "OS Cylinder", values["OS"]["cylinder"], "D"),
        ("OD", "9827-0", "OD Axis", values["OD"]["axis"], "deg"),
        ("OS", "9828-8", "OS Axis", values["OS"]["axis"], "deg"),
    ]
    for eye, code, text, val, unit in mapping:
        obs = make_obs(eye, code, text, val, unit)
        oresp = client.post("Observation", json=obs, name="POST /Observation (auto-refractometer)")
        if getattr(oresp, "ok", False):
            oid = (oresp.json() or {}).get("id")
            if oid:
                observation_ids.append(oid)

    # 2b) Optional VisionPrescription derived from measurements
    vision_prescription_id: Optional[str] = None
    if user_id:
        vp = {
            "resourceType": "VisionPrescription",
            "status": "active",
            "dateWritten": _now_iso(),
            "created": _now_iso(),
            "patient": {"reference": f"Patient/{pid}"},
            "encounter": {"reference": f"Encounter/{eid}"},
            "prescriber": {"reference": f"Practitioner/{user_id}"},
            "lensSpecification": [
                {
                    "product": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct", "code": "lens"}]},
                    "eye": "right",
                    "sphere": values["OD"]["sphere"],
                    "cylinder": values["OD"]["cylinder"],
                    "axis": values["OD"]["axis"],
                },
                {
                    "product": {"coding": [{"system": "http://terminology.hl7.org/CodeSystem/ex-visionprescriptionproduct", "code": "lens"}]},
                    "eye": "left",
                    "sphere": values["OS"]["sphere"],
                    "cylinder": values["OS"]["cylinder"],
                    "axis": values["OS"]["axis"],
                },
            ],
            "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_AUTOREF}]},
        }
        vp_resp = client.post("VisionPrescription", json=vp, name="POST /VisionPrescription (auto-refractometer)")
        if getattr(vp_resp, "ok", False):
            vision_prescription_id = (vp_resp.json() or {}).get("id")

    # 3) List packaging QR + Observations (+ VisionPrescription if created)
    list_body = {
        "resourceType": "List",
        "status": "current",
        "mode": "working",
        "title": "Auto Refractometer Form",
        "code": {"coding": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_AUTOREF}]},
        "subject": {"reference": f"Patient/{pid}"},
        "encounter": {"reference": f"Encounter/{eid}"},
        "date": _now_iso(),
        "meta": {"tag": [{"system": FORM_TAG_SYSTEM, "code": FORM_CODE_AUTOREF}]},
        "entry": (
            ([{"item": {"reference": f"QuestionnaireResponse/{qr_id}"}}]
            + ([{"item": {"reference": f"VisionPrescription/{vision_prescription_id}"}}] if vision_prescription_id else [])
            + [{"item": {"reference": f"Observation/{oid}"}} for oid in observation_ids])
        ),
    }
    list_resp = client.post("List", json=list_body, name="POST /List (auto-refractometer)")
    list_id = (list_resp.json() or {}).get("id") if getattr(list_resp, "ok", False) else None

    result: Dict[str, Any] = {"questionnaireResponseId": qr_id, "listId": list_id, "observationIds": observation_ids}
    if vision_prescription_id:
        result["visionPrescriptionId"] = vision_prescription_id
    return result


def fetch_auto_refractometer(client, patient_id: str, encounter_id: Optional[str] = None) -> Optional[Dict[str, Any]]:
    """
    Retrieves auto-refractometer package via List anchor and includes all member resources.
    """
    pid = str(patient_id)
    params: Dict[str, Any] = {
        "subject": f"Patient/{pid}",
        "code": f"{FORM_TAG_SYSTEM}|{FORM_CODE_AUTOREF}",
        "_include": ["List:item"],
        "_count": 50,
    }
    if encounter_id:
        params["encounter"] = f"Encounter/{encounter_id}"

    resp = client.get("List", params=params, name="GET /List?code=auto-refractometer&_include=List:item")
    bundle = _resp_json(resp)
    if bundle:
        return bundle

    # Fallback: fetch QuestionnaireResponses, Observations, and VisionPrescriptions by tag (and encounter if provided)
    qr_params: Dict[str, Any] = {
        "subject": f"Patient/{pid}",
        "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_AUTOREF}",
        "_count": 50,
    }
    if encounter_id:
        qr_params["encounter"] = f"Encounter/{encounter_id}"
    qr_resp = client.get(
        "QuestionnaireResponse",
        params=qr_params,
        name="GET /QuestionnaireResponse?_tag=auto-refractometer",
    )
    qr_bundle = _resp_json(qr_resp) or {}

    obs_params: Dict[str, Any] = {
        "subject": f"Patient/{pid}",
        "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_AUTOREF}",
        "_count": 50,
    }
    if encounter_id:
        obs_params["encounter"] = f"Encounter/{encounter_id}"
    obs_resp = client.get(
        "Observation",
        params=obs_params,
        name="GET /Observation?_tag=auto-refractometer",
    )
    obs_bundle = _resp_json(obs_resp) or {}

    # VisionPrescription
    vp_params: Dict[str, Any] = {
        "patient": f"Patient/{pid}",
        "_tag": f"{FORM_TAG_SYSTEM}|{FORM_CODE_AUTOREF}",
        "_count": 50,
    }
    if encounter_id:
        vp_params["encounter"] = f"Encounter/{encounter_id}"
    vp_resp = client.get(
        "VisionPrescription",
        params=vp_params,
        name="GET /VisionPrescription?_tag=auto-refractometer",
    )
    vp_bundle = _resp_json(vp_resp) or {}

    # Combine into a single FHIR Bundle for convenience
    entries = []
    for e in (qr_bundle.get("entry") or []):
        res = e.get("resource") if isinstance(e, dict) else None
        if res:
            entries.append({"resource": res})
    for e in (obs_bundle.get("entry") or []):
        res = e.get("resource") if isinstance(e, dict) else None
        if res:
            entries.append({"resource": res})
    for e in (vp_bundle.get("entry") or []):
        res = e.get("resource") if isinstance(e, dict) else None
        if res:
            entries.append({"resource": res})

    if entries:
        return {"resourceType": "Bundle", "type": "collection", "entry": entries}

    return qr_bundle or obs_bundle or vp_bundle


