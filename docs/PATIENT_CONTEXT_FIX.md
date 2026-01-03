# Patient Context Fix - Form POST Approach

## Problem Identified

The `patient` claim was missing from JWT tokens even though the patient ID was being passed as a URL parameter.

## Root Cause

Looking at how **scope selection** works in the consent form provided the key insight:

### How Scopes Work (✅ Working):

1. Consent form has **hidden checkboxes** for each scope (line 272-279 in consent.html)
2. User interaction updates which checkboxes are `checked`
3. Form **POSTs directly to `/oauth2/authorize`**
4. Spring Authorization Server automatically captures the `scope` parameters from the form POST
5. Scopes appear correctly in the token ✅

### How Patient Selection Was Working (❌ Broken):

1. Patient picker stores `patient_id` in session
2. Redirects to `/consent?...&patient_id=example`
3. Consent form redirects again to `/oauth2/authorize`
4. Patient ID gets lost in the redirects ❌

## Solution: Match the Scope Pattern

Just like scopes, we need to include `patient_id` as a **form parameter** in the consent form POST.

### Changes Made

#### 1. ConsentController.java

**Added**:

- `HttpSession session` parameter
- `@RequestParam(value = "patient_id", required = false) String patientIdParam`
- Logic to read patient_id from either request parameter OR session
- Pass `patient_id` to model for the template

```java
// Get patient_id from request parameter (passed from patient picker) or session
String patientId = patientIdParam;
if (patientId == null) {
    Object sessionPatientId = session.getAttribute("selected_patient_id");
    if (sessionPatientId != null) {
        patientId = sessionPatientId.toString();
    }
}

model.addAttribute("patient_id", patientId);  // Pass to template
```

#### 2. consent.html

**Added**:

- Cache-control meta tags in `<head>` to prevent browser caching
- Hidden input field for `patient_id` (line 271):

```html
<!-- Patient context (if selected via patient picker) -->
<input
  type="hidden"
  name="patient_id"
  th:value="${patient_id}"
  th:if="${patient_id != null}"
/>
```

This hidden input is submitted along with the form POST to `/oauth2/authorize`, just like the scope checkboxes!

#### 3. login.html

**Added**:

- Cache-control meta tags in `<head>` to prevent browser caching:

```html
<meta
  http-equiv="Cache-Control"
  content="no-store, no-cache, must-revalidate, max-age=0"
/>
<meta http-equiv="Pragma" content="no-cache" />
<meta http-equiv="Expires" content="0" />
```

#### 4. application.yml

**Added**:

- Disabled Thymeleaf template caching:

```yaml
spring:
  thymeleaf:
    cache: false
```

This ensures templates are always re-rendered, preventing stale cached pages during OAuth flows.

## How It Works Now

### Patient Standalone Launch (Patient user):

1. Patient logs in
2. `fhirUser = "Patient/example"`
3. Token customizer extracts patient ID from `fhirUser` (lines 404-408 in AuthorizationServerConfig)
4. Token gets: `{"patient": "example", "fhirUser": "Patient/example"}` ✅

### Provider Standalone Launch (Practitioner user):

1. Practitioner logs in → redirected to patient picker
2. Selects patient "example" → stored in session
3. Redirected to `/consent?...&patient_id=example`
4. Consent controller reads patient_id, adds to model
5. Consent form includes hidden input: `<input type="hidden" name="patient_id" value="example" />`
6. User approves → form POSTs to `/oauth2/authorize` with `patient_id=example`
7. `SmartAuthorizationRequestAuthenticationConverter` captures it (line 29-31)
8. Spring Authorization Server stores it in `OAuth2Authorization.attributes`
9. Token customizer reads it (line 353-364)
10. Token gets: `{"patient": "example", "fhirUser": "Practitioner/practitioner-1"}` ✅

## Why This Approach is Better

### Before (URL Parameters):

❌ Patient ID passed in URL → redirect → redirect → lost
❌ Relies on multiple redirect hops preserving parameters
❌ Complex filter injection required

### After (Form POST):

✅ Patient ID in form POST (same as scopes)
✅ Single POST directly to `/oauth2/authorize`
✅ Spring Authorization Server naturally captures it
✅ Follows standard OAuth consent flow pattern
✅ Matches existing scope selection behavior

## Flow Diagram

```
[Patient Picker]
      ↓ (select patient "example")
      ↓ (store in session)
      ↓
[GET /consent?...&patient_id=example]
      ↓ (controller reads patient_id)
      ↓ (adds to model)
      ↓
[Consent Form HTML]
      ↓ <input type="hidden" name="patient_id" value="example" />
      ↓ (user clicks "Approve")
      ↓
[POST /oauth2/authorize]
      ↓ (form data includes patient_id=example)
      ↓
[SmartAuthorizationRequestAuthenticationConverter]
      ↓ (captures patient_id from request parameter)
      ↓ (adds to additionalParameters)
      ↓
[OAuth2Authorization.attributes]
      ↓ (patient_id stored here)
      ↓
[Token Customizer]
      ↓ (reads patient_id from attributes)
      ↓ (adds to JWT claims)
      ↓
[JWT Token] ✅
{
  "patient": "example",
  "fhirUser": "Practitioner/practitioner-1"
}
```

## Caching Prevention

### Meta Tags (Browser-Level)

Added to login.html and consent.html:

```html
<meta
  http-equiv="Cache-Control"
  content="no-store, no-cache, must-revalidate, max-age=0"
/>
<meta http-equiv="Pragma" content="no-cache" />
<meta http-equiv="Expires" content="0" />
```

### HTTP Headers (Server-Level)

Already set by ConsentController (line 55-57) and LoginController.

### Thymeleaf Cache (Application-Level)

Disabled in application.yml:

```yaml
spring:
  thymeleaf:
    cache: false
```

## Testing

After rebuild, verify:

1. **Patient Picker Logs**:

```
INFO  ✅ Patient context stored in session: example
INFO  ➡️ Redirecting to authorization endpoint with patient context: example
```

2. **Consent Logs**:

```
DEBUG 🏥 [CONSENT] Found patient_id in request parameter: example
INFO  🏥 [CONSENT] Patient context for consent: example
```

3. **Converter Logs**:

```
INFO  🏥 [SMART-CONVERTER] Captured patient_id parameter: example
INFO  ✅ [SMART-CONVERTER] Injected additionalParameters: {patient_id=example}
```

4. **Token Customizer Logs**:

```
DEBUG 🔍 [TOKEN-CUSTOMIZER] Authorization attributes: [patient_id, ...]
INFO  🏥 [TOKEN-CUSTOMIZER] Added patient claim 'example' from authorization attributes
```

5. **Final Token**:

```json
{
  "patient": "example",
  "fhirUser": "Practitioner/practitioner-1"
}
```

## Related Files

- `backend/src/main/java/com/couchbase/fhir/auth/controller/ConsentController.java` - Reads patient_id, passes to model
- `backend/src/main/resources/templates/consent.html` - Hidden input for patient_id
- `backend/src/main/resources/templates/login.html` - Cache-control meta tags
- `backend/src/main/resources/application.yml` - Thymeleaf caching disabled
- `backend/src/main/java/com/couchbase/fhir/auth/SmartAuthorizationRequestAuthenticationConverter.java` - Captures patient_id from form POST
- `backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java` - Token customizer reads patient_id

---

_Last updated: 2026-01-02_
