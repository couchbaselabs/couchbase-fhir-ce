# Patient Claim Debugging Guide

## The Problem

JWT tokens are missing the `patient` claim even after patient selection in provider standalone launch.

**Expected Token**:

```json
{
  "patient": "example",
  "fhirUser": "Practitioner/practitioner-1"
}
```

**Actual Token** (WRONG):

```json
{
  "fhirUser": "Practitioner/practitioner-1"
  // ❌ patient claim is missing!
}
```

## How Patient Context Should Flow

### 1. Patient Picker → Authorization Endpoint

**File**: `PatientPickerController.java` (line 174)

```java
authorizeUrl.append("&patient_id=").append(patientId);
```

✅ Patient ID is passed as request parameter

### 2. Authorization Request Converter Captures It

**File**: `SmartAuthorizationRequestAuthenticationConverter.java` (line 29-31)

```java
String patientId = request.getParameter("patient_id");
if (patientId != null && !patientId.isBlank()) {
    merged.put("patient_id", patientId);
}
```

✅ Converts request parameter → `additionalParameters` map
✅ Spring Authorization Server stores this in `OAuth2Authorization.attributes`

### 3. Token Customizer Reads It

**File**: `AuthorizationServerConfig.java` (line 353-364)

```java
Object patientContextAttr = authorization.getAttribute("selected_patient_id");
if (patientContextAttr == null) {
    patientContextAttr = authorization.getAttribute("patient_id");
}
if (patientContextAttr != null) {
    context.getClaims().claim("patient", selectedPatientId);
}
```

✅ Reads from `OAuth2Authorization.attributes` → adds to JWT claims

## Debug Logs to Watch

After rebuild, run the OAuth flow and watch for these logs:

### Step 1: Patient Selection

```
INFO  🏥 [SMART-CONVERTER] Captured patient_id parameter: example
INFO  ✅ [SMART-CONVERTER] Injected additionalParameters: {patient_id=example, ...}
```

✅ If you see this, the converter is working

### Step 2: Token Generation

```
DEBUG 🔍 [TOKEN-CUSTOMIZER] Checking OAuth2Authorization for patient context...
DEBUG 🔍 [TOKEN-CUSTOMIZER] Authorization attributes: [patient_id, ...]
INFO  🏥 [TOKEN-CUSTOMIZER] Added patient claim 'example' from authorization attributes
```

✅ If you see this, the token customizer found it

### ⚠️ Problem Indicators

**If you see**:

```
WARN  ⚠️ [TOKEN-CUSTOMIZER] No patient_id or selected_patient_id found in authorization attributes
```

❌ The patient ID didn't make it into the `OAuth2Authorization` object

**If you see**:

```
WARN  ⚠️ [SMART-CONVERTER] Could not inject additionalParameters; proceeding with original
```

❌ Reflection failed to set the `additionalParameters` field

## Your Scope Analysis is Correct! 👍

You nailed the understanding:

### `launch/patient` Scope

- Means: "Establish patient context"
- **For Patient launch**: User IS the patient → auto-populate, skip picker
- **For Practitioner launch**: Show picker → practitioner selects patient
- **For RelatedPerson launch**: Show patients they're related to → select one

### Patient vs Provider Launch

- **Same scopes apply to both!**
- Only difference: Patient picker behavior
- Both need `patient` claim in token
- Inferno doesn't have separate "Provider Standalone" test because it's the same flow

### Token Claims

- `patient`: The patient ID in context (from picker or auto-populated)
- `fhirUser`: Who is logged in (Patient/X, Practitioner/Y, etc.)
- These are **independent**:
  - Practitioner can have `fhirUser: "Practitioner/1"` and `patient: "example"`
  - Patient has `fhirUser: "Patient/example"` and `patient: "example"` (same)

## Testing Commands

### Check converter logs

```bash
docker logs -f fhir-server 2>&1 | grep "SMART-CONVERTER"
```

### Check token customizer logs

```bash
docker logs -f fhir-server 2>&1 | grep "TOKEN-CUSTOMIZER"
```

### Full OAuth flow

```bash
docker logs -f fhir-server 2>&1 | grep -E "(SMART-CONVERTER|TOKEN-CUSTOMIZER|Patient)"
```

## Next Steps

1. **Rebuild the app** with enhanced logging
2. **Run the OAuth flow** (provider standalone launch)
3. **Check logs** for the debug messages above
4. **Share the logs** showing:
   - Patient picker selection
   - Authorization request converter
   - Token customizer execution
   - Final token contents

This will tell us exactly where the patient ID is being lost!

---

_Last updated: 2026-01-02_
