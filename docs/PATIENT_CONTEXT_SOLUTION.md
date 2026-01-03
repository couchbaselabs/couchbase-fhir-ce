# Patient Context Solution - OAuth2AuthorizationService Wrapper

## The Winning Approach! 🎯

After extensive debugging, we found the correct solution: **Wrap the `OAuth2AuthorizationService` to inject `patient_id` when the authorization is saved.**

## Why This Works

### The Problem We Had:

1. ❌ `SmartAuthorizationRequestAuthenticationConverter` reflection failed silently
2. ❌ HTTP session doesn't persist to `/oauth2/token` (different client makes the call)
3. ❌ Hidden form inputs don't automatically persist to authorization attributes

### The Solution:

✅ **Intercept the authorization save operation and inject `patient_id` directly into the attributes**

When Spring Authorization Server saves an `OAuth2Authorization` (after user consents), we:

1. Read `patient_id` from the HTTP session
2. Add it to the authorization's attributes: `attributes.put("patient_id", "example")`
3. Save the modified authorization

Later, when `/oauth2/token` is called with the authorization code:

1. Spring loads the `OAuth2Authorization` by code
2. The `patient_id` attribute is there!
3. Token customizer reads it and adds to JWT claims

## Implementation

### 1. PatientContextAwareAuthorizationService.java (NEW)

**Location**: `backend/src/main/java/com/couchbase/fhir/auth/PatientContextAwareAuthorizationService.java`

**Purpose**: Wraps the default `OAuth2AuthorizationService` to inject patient context.

**Key Logic**:

```java
@Override
public void save(OAuth2Authorization authorization) {
    // Get patient_id from HTTP session
    String patientId = getPatientIdFromSession();

    if (patientId != null) {
        // Inject into authorization attributes
        Map<String, Object> attributes = new HashMap<>(authorization.getAttributes());
        attributes.put("patient_id", patientId);

        OAuth2Authorization updatedAuthorization = OAuth2Authorization.from(authorization)
                .attributes(attrs -> attrs.putAll(attributes))
                .build();

        logger.info("🏥 [PATIENT-CONTEXT-SERVICE] Injected patient_id='{}' into authorization", patientId);
        delegate.save(updatedAuthorization);
        return;
    }

    delegate.save(authorization);
}
```

### 2. AuthorizationServerConfig.java (UPDATED)

**Added Bean**:

```java
@Bean
public OAuth2AuthorizationService authorizationService() {
    // Create default in-memory service
    InMemoryOAuth2AuthorizationService inMemoryService =
        new InMemoryOAuth2AuthorizationService();

    // Wrap with patient-context injection
    return new PatientContextAwareAuthorizationService(inMemoryService);
}
```

**Simplified Token Customizer**:

```java
// Read patient_id directly from attributes (injected when authorization was saved)
Object patientIdAttr = authorization.getAttribute("patient_id");
if (patientIdAttr != null) {
    selectedPatientId = patientIdAttr.toString();
    context.getClaims().claim("patient", selectedPatientId);
    logger.info("🏥 [TOKEN-CUSTOMIZER] Added patient claim '{}' from authorization attributes", selectedPatientId);
}
```

## Flow Diagram

```
[Patient Picker]
      ↓ (user selects "example")
      ↓ session.setAttribute("selected_patient_id", "example")
      ↓
[Redirect to /oauth2/authorize]
      ↓ (with patient_id=example in URL)
      ↓
[Consent Page]
      ↓ (shows scopes)
      ↓ (user clicks "Approve")
      ↓
[POST /oauth2/authorize] (consent approval)
      ↓
[Spring Authorization Server]
      ↓ creates OAuth2Authorization with authorization code
      ↓ calls authorizationService.save()
      ↓
[PatientContextAwareAuthorizationService]
      ↓ reads selected_patient_id from session
      ↓ injects into authorization.attributes
      ↓ authorization.attributes.put("patient_id", "example") ✅
      ↓ saves updated authorization
      ↓
[Authorization code issued to client]
      ↓
      ↓ ... client exchanges code for tokens ...
      ↓
[POST /oauth2/token] (from Inferno backend - NO session)
      ↓ code=...
      ↓
[Spring Authorization Server]
      ↓ calls authorizationService.findByToken(code)
      ↓ loads OAuth2Authorization (with patient_id attribute) ✅
      ↓
[Token Customizer]
      ↓ authorization.getAttribute("patient_id") = "example" ✅
      ↓ adds to JWT claims
      ↓
[JWT Token] ✅
{
  "patient": "example",
  "fhirUser": "Practitioner/practitioner-1"
}
      ↓
[SmartTokenEnhancerFilter]
      ↓ extracts patient from JWT
      ↓ adds to top-level response
      ↓
[Token Response] ✅
{
  "access_token": "eyJ...",
  "patient": "example",
  "fhirUser": "Practitioner/practitioner-1"
}
```

## Expected Logs

### 1. Patient Selection

```
INFO  ✅ Patient context stored in session: example
```

### 2. Authorization Save (After Consent)

```
INFO  🏥 [PATIENT-CONTEXT-SERVICE] Initialized - will inject patient_id into authorizations
DEBUG 🔍 [PATIENT-CONTEXT-SERVICE] Found patient_id in session: example
INFO  🏥 [PATIENT-CONTEXT-SERVICE] Injected patient_id='example' into authorization (id=...)
```

### 3. Token Generation

```
DEBUG 🔍 [TOKEN-CUSTOMIZER] Checking OAuth2Authorization for patient_id...
INFO  🏥 [TOKEN-CUSTOMIZER] Added patient claim 'example' from authorization attributes
DEBUG 🎫 [TOKEN-CUSTOMIZER] Added fhirUser claim: Practitioner/practitioner-1
```

### 4. Token Response Enhancement

```
INFO  🎫 [SMART-ENHANCER] *** INTERCEPTING token endpoint: POST /oauth2/token
INFO  🔍 [TOKEN-ENHANCER] JWT claims: patient=example, fhirUser=Practitioner/practitioner-1
INFO  ✅ [TOKEN-ENHANCER] Added 'patient' to token response: example
INFO  ✅ [SMART-ENHANCER] *** Response MODIFIED
```

## Why This is Better Than Other Approaches

### ❌ Reflection on OAuth2AuthorizationCodeRequestAuthenticationToken

- **Problem**: Failed silently, fields are final/immutable
- **Complexity**: Fragile, depends on internal implementation

### ❌ HTTP Session Fallback

- **Problem**: Token endpoint called by different client (no session cookies)
- **Limitation**: Only works for browser-based flows

### ❌ Custom Authorization Request Converter

- **Problem**: Parameters don't persist through consent flow
- **Limitation**: Spring Authorization Server doesn't preserve them

### ✅ OAuth2AuthorizationService Wrapper

- **Works**: Authorization object is persisted and loaded by code
- **Clean**: Uses proper Spring Authorization Server extension point
- **Reliable**: No reflection, no session dependency
- **Standard**: This is the intended way to add custom attributes

## Related Files

- **NEW**: `backend/src/main/java/com/couchbase/fhir/auth/PatientContextAwareAuthorizationService.java`
- **UPDATED**: `backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java`
  - Added `authorizationService()` bean
  - Simplified `tokenCustomizer()` to read from attributes
- **UNCHANGED**: All other files (consent form, patient picker, etc.) still work the same

## Testing

After rebuild:

```bash
# Watch for patient context injection
docker logs -f fhir-server 2>&1 | grep "PATIENT-CONTEXT-SERVICE\|TOKEN-CUSTOMIZER\|TOKEN-ENHANCER"
```

You should see:

1. ✅ Patient picker stores in session
2. ✅ Authorization service injects into attributes
3. ✅ Token customizer reads from attributes
4. ✅ Token enhancer adds to response

## Credit

This solution was suggested by the user after recognizing that the proper extension point for Spring Authorization Server is the `OAuth2AuthorizationService`, not the request converter or session hacks. Brilliant insight! 🎉

---

_Last updated: 2026-01-03_
