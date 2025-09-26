from typing import Any, Dict, Optional

def _resp_json(resp) -> Optional[Dict[str, Any]]:
    if resp is None or not getattr(resp, "ok", False):
        return None
    try:
        return resp.json() or {}
    except Exception:
        return None


def _bundle_total(bundle: Optional[Dict[str, Any]]) -> int:
    if not isinstance(bundle, dict):
        return 0
    # Many servers support _count=0 with total present. Fallback to len(entry)
    total = bundle.get("total")
    if isinstance(total, int):
        return total
    entries = bundle.get("entry") or []
    return len(entries)


def _ref_id(reference: Any) -> Optional[str]:
    if not isinstance(reference, str):
        return None
    parts = reference.split("/")
    if len(parts) >= 2 and parts[-1]:
        return parts[-1]
    return None


def fhir_patient_sidebar(client, patient_id: Any, encounter_id: Any | None = None) -> Optional[Dict[str, Any]]:
    pid = str(patient_id)

    # Patient
    patient_resp = client.get(f"Patient/{pid}", name="GET /Patient/{id}")
    patient_json = _resp_json(patient_resp)
    if not patient_json:
        return None

    # Encounter count for patient
    enc_count_resp = client.get(
        "Encounter",
        params={
            "subject": f"Patient/{pid}",
            "_count": 0,
            "_total": "accurate",
        },
        name="GET /Encounter?subject=&_count=0&_total=accurate",
    )
    enc_count_json = _resp_json(enc_count_resp)
    encounter_total = _bundle_total(enc_count_json)

    # Questionnaires as form templates
    questionnaires_resp = client.get("Questionnaire", params={"_count": 200}, name="GET /Questionnaire")
    questionnaires_json = _resp_json(questionnaires_resp) or {}

    # Locations
    locations_resp = client.get("Location", params={"_count": 200}, name="GET /Location")
    locations_json = _resp_json(locations_resp) or {}

    # Practitioners
    practitioners_resp = client.get("Practitioner", params={"_count": 200}, name="GET /Practitioner")
    practitioners_json = _resp_json(practitioners_resp) or {}

    # Encounter detail (optional): resolve referenced Patient/Practitioner(s)/Location(s)
    encounter_detail: Optional[Dict[str, Any]] = None
    if encounter_id:
        eid = str(encounter_id)
        # Read Encounter by id
        encounter_resp = client.get(
            f"Encounter/{eid}",
            name="GET /Encounter/{id}",
        )
        encounter_res_json = _resp_json(encounter_resp) or {}

        if encounter_res_json:
            # Resolve subject (Patient)
            subject_ref = ((encounter_res_json.get("subject") or {}).get("reference"))
            subject_json: Optional[Dict[str, Any]] = None
            if subject_ref:
                subj_resp = client.get(subject_ref, name=f"GET /{subject_ref}")
                subject_json = _resp_json(subj_resp)

            # Resolve participants → Practitioner/PractitionerRole (and nested Practitioner if present)
            practitioner_jsons: list[Dict[str, Any]] = []
            seen_refs: set[str] = set()
            practitioner_ids: set[str] = set()
            for part in (encounter_res_json.get("participant") or []):
                individual_ref = ((part or {}).get("individual") or {}).get("reference")
                if individual_ref and individual_ref not in seen_refs:
                    seen_refs.add(individual_ref)
                    # Track practitioner id(s) from the reference directly when available
                    if "Practitioner/" in individual_ref:
                        pid_only = _ref_id(individual_ref)
                        if pid_only:
                            practitioner_ids.add(pid_only)
                    ind_resp = client.get(individual_ref, name=f"GET /{individual_ref}")
                    ind_json = _resp_json(ind_resp)
                    if ind_json:
                        practitioner_jsons.append(ind_json)
                        # If PractitionerRole, try to resolve the underlying Practitioner
                        if ind_json.get("resourceType") == "PractitionerRole":
                            prac_ref = ((ind_json.get("practitioner") or {}).get("reference"))
                            if prac_ref and prac_ref not in seen_refs:
                                seen_refs.add(prac_ref)
                                prac_resp = client.get(prac_ref, name=f"GET /{prac_ref}")
                                prac_json = _resp_json(prac_resp)
                                if prac_json:
                                    practitioner_jsons.append(prac_json)
                                    # Record underlying Practitioner id
                                    pid_only = prac_json.get("id")
                                    if isinstance(pid_only, str) and pid_only:
                                        practitioner_ids.add(pid_only)

            # Resolve locations
            location_jsons: list[Dict[str, Any]] = []
            location_ids: set[str] = set()
            for loc in (encounter_res_json.get("location") or []):
                loc_ref = ((loc or {}).get("location") or {}).get("reference")
                if loc_ref:
                    lid_only = _ref_id(loc_ref)
                    if lid_only:
                        location_ids.add(lid_only)
                    lresp = client.get(loc_ref, name=f"GET /{loc_ref}")
                    ljson = _resp_json(lresp)
                    if ljson:
                        location_jsons.append(ljson)

            encounter_detail = {
                "resource": encounter_res_json,
                "patient": subject_json,
                "practitioners": practitioner_jsons,
                "locations": location_jsons,
                "practitioner_ids": sorted(practitioner_ids),
                "location_ids": sorted(location_ids),
            }

    return {
        "patient": patient_json,
        "encounter_aggregate": {"aggregate": {"count": encounter_total}},
        "questionnaires": questionnaires_json,
        "locations": locations_json,
        "practitioners": practitioners_json,
        "encounter": encounter_detail,
    }


def fhir_patient_summary(client, patient_id: Any) -> Optional[Dict[str, Any]]:
    pid = str(patient_id)

    # Guard patient existence
    patient_resp = client.get(f"Patient/{pid}", name="GET /Patient/{id}")
    if not getattr(patient_resp, "ok", False):
        return None

    # Encounters for patient
    encounters_resp = client.get(
        "Encounter",
        params={"subject": f"Patient/{pid}", "_count": 200},
        name="GET /Encounter?subject=",
    )
    encounters_json = _resp_json(encounters_resp) or {}

    # Notes -> Communication (patient subject)
    comms_resp = client.get(
        "Communication",
        params={"subject": f"Patient/{pid}", "_count": 200},
        name="GET /Communication?subject=",
    )
    communications_json = _resp_json(comms_resp) or {}

    # QuestionnaireResponses (forms) including Questionnaire and Encounter
    qnr_resp = client.get(
        "QuestionnaireResponse",
        params={
            "subject": f"Patient/{pid}",
            "_count": 200,
            "_include": [
                "QuestionnaireResponse:questionnaire",
                "QuestionnaireResponse:encounter",
            ],
        },
        name="GET /QuestionnaireResponse?subject=&_include=questionnaire,encounter",
    )
    qnr_json = _resp_json(qnr_resp) or {}

    # Observations: refraction related
    refraction_codes = ",".join(["9780-8", "9781-6", "17634-2", "17635-9", "9827-0", "9828-8"])
    obs_refraction_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "code": refraction_codes, "_count": 200},
        name="GET /Observation?subject=&code=(refraction)",
    )
    obs_refraction_json = _resp_json(obs_refraction_resp) or {}

    # Observations: IOP
    iop_codes = ",".join(["8716-3", "8717-1"])
    obs_iop_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "code": iop_codes, "_count": 200},
        name="GET /Observation?subject=&code=(iop)",
    )
    obs_iop_json = _resp_json(obs_iop_resp) or {}

    # Conditions (problem/diagnoses)
    conditions_resp = client.get(
        "Condition",
        params={"subject": f"Patient/{pid}", "_count": 200},
        name="GET /Condition?subject=",
    )
    conditions_json = _resp_json(conditions_resp) or {}

    # Procedures
    procedures_resp = client.get(
        "Procedure",
        params={"subject": f"Patient/{pid}", "_count": 200},
        name="GET /Procedure?subject=",
    )
    procedures_json = _resp_json(procedures_resp) or {}

    # CarePlans (recommendations)
    careplans_resp = client.get(
        "CarePlan",
        params={"subject": f"Patient/{pid}", "_count": 100},
        name="GET /CarePlan?subject=",
    )
    careplans_json = _resp_json(careplans_resp) or {}

    # ServiceRequests
    sr_resp = client.get(
        "ServiceRequest",
        params={"subject": f"Patient/{pid}", "_count": 200},
        name="GET /ServiceRequest?subject=",
    )
    servicerequests_json = _resp_json(sr_resp) or {}

    # MedicationRequests (medication orders)
    medreq_resp = client.get(
        "MedicationRequest",
        params={"subject": f"Patient/{pid}", "_count": 200, "_include": ["MedicationRequest:medication"]},
        name="GET /MedicationRequest?subject=&_include=medication",
    )
    medreq_json = _resp_json(medreq_resp) or {}

    # Free text + media -> DocumentReference
    docref_resp = client.get(
        "DocumentReference",
        params={"subject": f"Patient/{pid}", "_count": 100},
        name="GET /DocumentReference?subject=",
    )
    docref_json = _resp_json(docref_resp) or {}

    # VisionPrescription (prescription glasses)
    vision_resp = client.get(
        "VisionPrescription",
        params={"patient": f"Patient/{pid}", "_count": 100},
        name="GET /VisionPrescription?patient=",
    )
    vision_json = _resp_json(vision_resp) or {}

    # DiagnosticReport with included analyte Observations
    diag_resp = client.get(
        "DiagnosticReport",
        params={"subject": f"Patient/{pid}", "_count": 200, "_include": ["DiagnosticReport:result"]},
        name="GET /DiagnosticReport?subject=&_include=result",
    )
    diag_json = _resp_json(diag_resp) or {}

    # AllergyIntolerance
    allergy_resp = client.get(
        "AllergyIntolerance",
        params={"patient": f"Patient/{pid}", "_count": 100},
        name="GET /AllergyIntolerance?patient=",
    )
    allergy_json = _resp_json(allergy_resp) or {}

    # Screening/social history Observations
    social_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "category": "social-history", "_count": 200},
        name="GET /Observation?subject=&category=social-history",
    )
    social_json = _resp_json(social_resp) or {}

    survey_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "category": "survey", "_count": 200},
        name="GET /Observation?subject=&category=survey",
    )
    survey_json = _resp_json(survey_resp) or {}

    # Vital signs subsets
    spo2_codes = ",".join(["59408-5", "2708-6"])  # SpO2
    spo2_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "code": spo2_codes, "_count": 100},
        name="GET /Observation?subject=&code=(spo2)",
    )
    spo2_json = _resp_json(spo2_resp) or {}

    hr_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "code": "8867-4", "_count": 100},
        name="GET /Observation?subject=&code=(heart-rate)",
    )
    hr_json = _resp_json(hr_resp) or {}

    bp_resp = client.get(
        "Observation",
        params={"subject": f"Patient/{pid}", "code": "85354-9", "_count": 100},
        name="GET /Observation?subject=&code=(bp)",
    )
    bp_json = _resp_json(bp_resp) or {}

    return {
        "communications": communications_json,
        "encounters": encounters_json,
        "questionnaire_responses": qnr_json,
        "observations_refraction": obs_refraction_json,
        "observations_iop": obs_iop_json,
        "conditions": conditions_json,
        "procedures": procedures_json,
        "careplans": careplans_json,
        "service_requests": servicerequests_json,
        "medication_requests": medreq_json,
        "document_references": docref_json,
        "vision_prescriptions": vision_json,
        "diagnostic_reports": diag_json,
        "allergies": allergy_json,
        "observations_social_history": social_json,
        "observations_survey": survey_json,
        "vitals_spo2": spo2_json,
        "vitals_heart_rate": hr_json,
        "vitals_blood_pressure": bp_json,
    }