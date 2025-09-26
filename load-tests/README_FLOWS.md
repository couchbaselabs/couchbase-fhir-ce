## Overview

This document explains the FHIR flows exercised by the load tests, the sequence of API calls in each flow, and the resources created. All flows assume a current `Patient` and active `Encounter` already exist (created by Registration).

### Conventions used across flows

- **Create strategy**: Sequential POSTs (no transaction Bundles, no List aggregations)
- **IDs**: Client generates UUIDs in `id` and captures server-assigned `id` if returned
- **Linking**: Child resources reference the anchor `QuestionnaireResponse` via `derivedFrom` (or purpose-appropriate linkage; see specific notes)
- **Tagging**: All resources created by a form are tagged `meta.tag = { system: "https://medblocks.dev/fhir/form", code: <form_code> }`
- **Fetching**: Tag-only searches; returns a plain object with arrays per resource type (not a FHIR Bundle)
- **Search params**: Most resources filter by `subject=Patient/{id}`, but some use `patient=` instead:
  - Use `patient=` for `VisionPrescription`, `Device`, and `AllergyIntolerance`
  - Use `subject=` for `QuestionnaireResponse`, `Observation`, `Procedure`, `Condition`, `DiagnosticReport`, `Communication`, `ServiceRequest`, `Composition`

## Registration Flow (`flows/registration.py`)

### Purpose

Seed core actors and an `Encounter` for the patient.

### Steps and requests

- GET encounter drill-down: `Encounter` + related
- GET or create `Patient` by identifier
- GET or create `Practitioner`
- GET or create `Location`
- POST `Encounter` (links `patient`, `participant` as practitioner, and `location`)

## Refraction Flow (`flows/refraction.py`)

### Purpose

Run dashboard reads and create clinical data for refraction workup.

### Steps and requests

1. Dashboard reads

- GET encounters detail
- GET patient sidebar summary
- GET patient summary

2. Auto-refractometer (code: `auto-refractometer`)

- GET by tag (QR/Observation/VisionPrescription)
- POST `QuestionnaireResponse`
- POST `Observation` (OD/OS sphere, cylinder, axis; `derivedFrom` QR)
- Optional POST `VisionPrescription` from measurements
- GET by tag again

3. IOP (code: `iop`)

- GET by tag (QR/Observation)
- POST `QuestionnaireResponse`
- POST `Observation` for IOP methods; also duct patency and CCT; all `derivedFrom` QR
- GET by tag again

4. Screening (code: `screening`)

- GET by tag (QR/Observation/Condition/AllergyIntolerance/Communication)
- POST `QuestionnaireResponse`
- POST vitals `Observation` (BP components, SpO2, Pulse)
- POST `AllergyIntolerance`
- POST `Condition` (pre-existing illnesses)
- POST social history `Observation` (travel time, occupation)
- POST lab-like `Observation` (blood sugar, ECG)
- POST `Communication` (education); link to QR
- GET by tag again

5. Anterior chamber (code: `anterior_chamber`)

- GET by tag (QR/Observation)
- POST `QuestionnaireResponse`
- POST multiple surface/anterior segment `Observation` entries; `derivedFrom` QR
- GET by tag again

6. Posterior chamber (code: `posterior_chamber`)

- GET by tag (QR/Observation)
- POST `QuestionnaireResponse`
- POST posterior segment `Observation` entries; `derivedFrom` QR
- GET by tag again

7. Transfer encounter location

- GET a new `Location`, then POST encounter transfer to subjective_refraction flow

## Subjective Refraction Flow (`flows/subjective_refraction.py`)

### Purpose

Capture subjective refraction, prior treatment, and complaints.

### Steps and requests

1. Dashboard reads: encounters, sidebar, patient summary

2. Subjective refraction (code: `subjective_refraction`)

- GET by tag (QR/Observation)
- POST `QuestionnaireResponse`
- POST DV UCVA `Observation` per eye
- POST DV BCVA `Observation` per eye with lens sub-observations (sphere/cyl/axis)
- POST NV BCVA `Observation` per eye with lens sub-observations (sphere/cyl/axis/add)
- All `derivedFrom` QR; GET by tag again

3. Treatment history (code: `treatment_history`)

- GET by tag (QR/Procedure/Condition)
- POST `QuestionnaireResponse`
- POST `Procedure` (past procedure)
- POST `Condition` (past diagnosis)
- All linked to QR; GET by tag again

4. Complaints (code: `complaints`)

- GET by tag (QR/Condition)
- POST `QuestionnaireResponse`
- POST `Condition` per complaint; optional `bodySite`
- All linked to QR; GET by tag again

5. Transfer encounter location to pgp flow

## PGP Flow (`flows/pgp.py`)

### Purpose

Capture prior glasses and near addition (PGP) details.

### Steps and requests

- Dashboard reads: encounters, sidebar, patient summary
- GET by tag (QR/VisionPrescription/Observation)

1. PGP (code: `subjective_refraction`)

- POST `QuestionnaireResponse`
- POST two `VisionPrescription` (distance and near)
- POST visual acuity `Observation` values; `derivedFrom` QR
- GET by tag again;
- transfer location to Lab flow

## Lab Flow (`flows/lab.py`)

### Purpose

Collect lab results, free text notes, and A-scan measurements.

### Steps and requests

1. Dashboard reads: encounters, sidebar, patient summary

2. Lab (code: `lab`)

- GET by tag (QR/Observation/DiagnosticReport)
- POST `QuestionnaireResponse`
- POST lab `Observation` (biochemistry, serology)
- POST `DiagnosticReport` referencing the observations
- GET by tag again

3. Free text (code: `free_text`)

- GET by tag (QR/Communication)
- POST `QuestionnaireResponse`
- POST `Communication` with `payload.contentString`; link to QR (about/derivedFrom)
- GET by tag again

4. A-scan (code: `a_scan`)

- GET by tag (QR/Observation/Device)
- POST `QuestionnaireResponse`
- POST `Device` for right and left IOL
- POST multiple metric `Observation` per eye; `final_iol_power` references the `Device`
- All `derivedFrom` QR; GET by tag again

5. Transfer encounter location to prescription flow

## Prescription Flow (`flows/prescription.py`)

### Purpose

Enter prescriptions, surgical data, and referrals; discharge the encounter.

### Steps and requests

1. Dashboard reads: encounters, sidebar, patient summary

2. Glass prescription (code: `glass_prescription`)

- GET by tag (QR/VisionPrescription/Observation)
- POST `QuestionnaireResponse`
- POST two `VisionPrescription` (distance and near)
- POST visual acuity `Observation` values; `derivedFrom` QR
- GET by tag again

3. Drug prescription (code: `drug_prescription`)

- GET by tag (QR/Condition/Communication/ServiceRequest/MedicationRequest)
- POST `QuestionnaireResponse`
- POST `Condition` (diagnosis)
- POST `Communication` for advice (linked to QR; server-compatible field)
- POST investigation `ServiceRequest`
- POST follow-up `ServiceRequest`
- POST `MedicationRequest` referencing the `Condition`
- GET by tag again

4. Surgical notes (code: `surgical_notes`)

- GET by tag (QR/Procedure/Device/DiagnosticReport/Condition/Composition)
- POST `QuestionnaireResponse`
- POST `Procedure` (bodySite, timing, outcome)
- POST `Device` (implant)
- POST pre-op `DiagnosticReport`; then update `Procedure.report` reference
- POST `Condition` (diagnosis)
- POST discharge summary `Composition`
- GET by tag again

5. Referral (code: `referral`)

- GET by tag (QR/ServiceRequest)
- POST `QuestionnaireResponse`
- POST referral `ServiceRequest` (reason, destination)
- GET by tag again

6. Discharge

- POST encounter discharge

## Data-entry forms: resources created

### Auto-refractometer

- `QuestionnaireResponse` anchor
- 6 `Observation` (OD/OS sphere, cylinder, axis)
- Optional `VisionPrescription` (right/left lensSpecification); tagged and QR-linked

### IOP

- `QuestionnaireResponse` anchor
- Multiple `Observation` for IOP by different methods; duct patency; CCT; QR-linked

### Screening

- `QuestionnaireResponse` anchor
- Vitals `Observation` (BP, SpO2, Pulse)
- `AllergyIntolerance`
- `Condition` (pre-existing illnesses)
- Social history `Observation` (travel time, occupation)
- Lab-like `Observation` (blood sugar, ECG)
- `Communication` (education)

### Anterior / Posterior chamber

- `QuestionnaireResponse` anchor
- Multiple `Observation` entries describing findings per body site; QR-linked

### Subjective refraction

- `QuestionnaireResponse` anchor
- `Observation` for DV UCVA (per eye)
- `Observation` for DV BCVA (per eye) with component lens `Observation` (sphere/cyl/axis)
- `Observation` for NV BCVA (per eye) with component lens `Observation` (sphere/cyl/axis/add)

### Lab

- `QuestionnaireResponse` anchor
- Lab `Observation` (biochemistry, serology)
- `DiagnosticReport` referencing the observations

### Free text

- `QuestionnaireResponse` anchor
- `Communication` with `payload.contentString` (linked to QR)

### A-scan

- `QuestionnaireResponse` anchor
- `Device` (IOL) for each eye
- Numerous metric `Observation`; the final IOL power `Observation` references the `Device`

### Glass prescription / PGP

- `QuestionnaireResponse` anchor
- Two `VisionPrescription` resources (distance and near)
- Visual acuity `Observation` values; QR-linked

### Drugs

- `QuestionnaireResponse` anchor
- `Condition` (diagnosis)
- `Communication` with advice (linked to QR)
- `ServiceRequest` for investigation and follow-up
- `MedicationRequest` referencing the `Condition`

### Surgical notes

- `QuestionnaireResponse` anchor
- `Procedure` (with `report` reference updated to pre-op `DiagnosticReport`)
- `Device` (implant)
- Pre-op `DiagnosticReport`
- `Condition` (diagnosis)
- Discharge summary `Composition`

### Referral

- `QuestionnaireResponse` anchor
- Referral `ServiceRequest`

## Troubleshooting notes

- Server does not support Bundle/List aggregations: use sequential POSTs and tag-only GETs as implemented here.
