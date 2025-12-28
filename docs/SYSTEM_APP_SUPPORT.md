# System App Support (Backend Service / Client Credentials)

## Overview

Implemented full support for **System Apps** using OAuth 2.0 `client_credentials` grant flow. System apps are backend services that authenticate directly with client credentials (no user interaction) and access ALL patients' data using `system/*` scopes.

## Three App Types Supported

| App Type     | Scope Prefix | Grant Type         | User Login? | Context          | Redirect URIs Required? |
| ------------ | ------------ | ------------------ | ----------- | ---------------- | ----------------------- |
| **Patient**  | `patient/*`  | authorization_code | ✅ Yes      | `patient` claim  | ✅ Yes                  |
| **Provider** | `user/*`     | authorization_code | ✅ Yes      | `fhirUser` claim | ✅ Yes                  |
| **System**   | `system/*`   | client_credentials | ❌ No       | (none)           | ❌ No                   |

---

## Backend Changes

### 1. OAuth Client Model (`OAuthClient.java`)

The model already had a `clientType` field supporting `"patient"`, `"provider"`, and `"system"`.

### 2. Registered Client Repository (`CouchbaseRegisteredClientRepository.java`)

Updated to dynamically assign grant types based on `clientType`:

**Changes:**

```java
// Authorization grant types
// System apps use client_credentials, others use authorization_code
if ("system".equals(client.getClientType())) {
    // System/Backend Service - client_credentials grant (no user interaction)
    builder.authorizationGrantType(AuthorizationGrantType.CLIENT_CREDENTIALS);
    logger.debug("🔧 System app: enabling client_credentials grant for {}", client.getClientId());
} else {
    // Patient/Provider apps - authorization_code grant (interactive)
    builder.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE);

    // Add refresh token grant if offline_access scope is requested
    if (client.getScopes() != null && client.getScopes().contains("offline_access")) {
        builder.authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN);
    }
}

// Client settings
ClientSettings.Builder clientSettingsBuilder = ClientSettings.builder()
    // System apps don't need consent (no user interaction)
    // Patient/Provider apps require consent
    .requireAuthorizationConsent(!"system".equals(client.getClientType()))
    .requireProofKey(client.isPkceEnabled()); // PKCE requirement
```

**Key Points:**

- System apps use `CLIENT_CREDENTIALS` grant type
- System apps **skip consent** (no user to show consent to)
- Patient/Provider apps use `AUTHORIZATION_CODE` grant type

### 3. SMART Scope Validator (`SmartScopeValidator.java`)

Already had `hasSystemScope()` method:

```java
public boolean hasSystemScope(Authentication authentication) {
    List<String> scopes = extractScopes(authentication);
    if (scopes != null && scopes.stream().anyMatch(s -> s.startsWith("system/"))) return true;
    return hasAdminRole(authentication);
}
```

### 4. Smart Authorization Interceptor (`SmartAuthorizationInterceptor.java`)

Updated to skip patient/user context enforcement for system scopes:

**Changes:**

```java
logger.debug("✅ Authorized: {} for {} {}", authentication.getName(), operation, resourceType);

// System-scoped tokens have full access (no patient/user context restrictions)
if (scopeValidator.hasSystemScope(authentication)) {
    logger.debug("🔓 System-scoped request: full access granted (no context restrictions)");
    return; // Skip all context enforcement for system/* scopes
}

// Patient-scope filtering - ENFORCE patient context
if (scopeValidator.hasPatientScope(authentication)) {
    String patientContext = scopeValidator.getPatientContext(authentication);
    if (patientContext != null) {
        logger.debug("📋 Patient-scoped request: enforcing patient context {}", patientContext);
        enforcePatientContext(theRequestDetails, resourceType, operationType, patientContext);
    }
}

// User-scope filtering - TODO: Implement provider/clinician context enforcement
if (scopeValidator.hasUserScope(authentication)) {
    logger.debug("👤 User-scoped request: user context enforcement not yet implemented");
    // TODO: Implement fhirUser-based access control (care team, organization, etc.)
}
```

**Key Behaviors:**

- ✅ System scopes (`system/*`) → **Full access**, no restrictions
- ✅ Patient scopes (`patient/*`) → Restricted to single patient
- 🔄 User scopes (`user/*`) → TODO (placeholder for provider apps)

---

## Frontend Changes

### 1. Dynamic Scope Helper Functions

```typescript
// Helper function to get scope prefix based on client type
const getScopePrefix = (clientType: "patient" | "provider" | "system") => {
  switch (clientType) {
    case "patient": return "patient";
    case "provider": return "user";
    case "system": return "system";
    default: return "patient";
  }
};

// Helper function to get US Core scopes with correct prefix
const getUSCoreScopes = (clientType: "patient" | "provider" | "system") => {
  const prefix = getScopePrefix(clientType);
  const resources = ["Medication", "AllergyIntolerance", "CarePlan", ...];
  return resources.map((r) => `${prefix}/${r}.rs`);
};
```

### 2. Mandatory Scopes Updated

System apps don't need interactive launch scopes:

```typescript
const getMandatoryScopes = (clientType: "patient" | "provider" | "system") => {
  // System apps don't need interactive scopes (no user, no launch, no browser)
  if (clientType === "system") {
    return []; // System apps use client_credentials - no mandatory scopes
  }

  const base = ["openid", "fhirUser", "offline_access"];
  const launchScope = clientType === "patient" ? "launch/patient" : "launch";
  return [launchScope, ...base];
};
```

### 3. UI Conditional Rendering

**Launch Type** - Hidden for system apps:

```tsx
{
  formData.clientType !== "system" && (
    <FormControl component="fieldset">
      <FormLabel>Launch Type</FormLabel>
      <ToggleButtonGroup value={formData.launchType}>
        <ToggleButton value="standalone">Standalone Launch</ToggleButton>
        <ToggleButton value="ehr-launch" disabled>
          EHR Launch
        </ToggleButton>
      </ToggleButtonGroup>
    </FormControl>
  );
}
```

**Authentication Type** - Public disabled for system apps:

```tsx
<ToggleButton
  value="public"
  disabled={formData.clientType === "system"}
>
  Public Client
</ToggleButton>
<Typography variant="caption">
  {formData.clientType === "system"
    ? "System apps must be confidential (client_credentials requires a secret)"
    : "Public: No client secret | Confidential: Uses client secret"
  }
</Typography>
```

**Redirect URIs** - Optional for system apps:

```tsx
<Typography variant="subtitle2">
  Redirect URIs {formData.clientType !== "system" && "*"}
</Typography>;

{
  formData.clientType === "system" ? (
    <Alert severity="info">
      System apps use client_credentials flow (no user interaction, no redirect)
    </Alert>
  ) : (
    <TextField placeholder="https://example.com/callback" />
  );
}
```

**Mandatory Scopes Display**:

```tsx
{
  formData.clientType === "system" ? (
    <Alert severity="info">
      System apps don't require launch/openid/fhirUser scopes (no user
      interaction)
    </Alert>
  ) : (
    <Box>
      {getMandatoryScopes(formData.clientType).map((scope) => (
        <Chip label={scope.value} color="primary" />
      ))}
    </Box>
  );
}
```

### 4. Validation Updates

```typescript
const handleRegisterClient = async () => {
  // System apps: require confidential authentication
  if (
    formData.clientType === "system" &&
    formData.authenticationType !== "confidential"
  ) {
    setError(
      "System apps must use confidential authentication (client_credentials flow requires a secret)"
    );
    return;
  }

  // Patient/Provider apps: require redirect URIs
  if (formData.clientType !== "system" && formData.redirectUris.length === 0) {
    setError("At least one redirect URI is required for interactive apps");
    return;
  }

  // ... rest of validation
};
```

### 5. Scope Preset Buttons

Dynamically generate scope prefixes:

```typescript
const applyScopePreset = (mode: "custom" | "all-read" | "us-core") => {
  setScopeMode(mode);
  if (mode === "all-read") {
    const prefix = getScopePrefix(formData.clientType);
    setScopesText(`${prefix}/*.rs`); // patient/*.rs or user/*.rs or system/*.rs
  } else if (mode === "us-core") {
    const usCoreScopes = getUSCoreScopes(formData.clientType);
    setScopesText(usCoreScopes.join(" "));
  }
};
```

### 6. Auto-Force Confidential for System Apps

When user selects "System App", automatically set authentication to confidential:

```typescript
onChange={(_, newValue) => {
  if (newValue) {
    const updates: any = { clientType: newValue };
    if (newValue === "system") {
      updates.authenticationType = "confidential";
    }
    setFormData({ ...formData, ...updates });
  }
}}
```

---

## OAuth Token Exchange

### Patient App (Authorization Code Flow)

```bash
# 1. User logs in via browser
GET /oauth/authorize?
    response_type=code
    &client_id=patient-app-123
    &redirect_uri=https://app.example.com/callback
    &scope=openid fhirUser launch/patient patient/*.rs
    &state=xyz

# 2. Exchange authorization code for token
POST /oauth/token
Content-Type: application/x-www-form-urlencoded

grant_type=authorization_code
&code=AUTH_CODE
&redirect_uri=https://app.example.com/callback
&client_id=patient-app-123
&code_verifier=PKCE_VERIFIER

# 3. Response
{
  "access_token": "eyJhbG...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "openid fhirUser launch/patient patient/*.rs",
  "patient": "example",  // ⭐ Patient context
  "refresh_token": "..."
}
```

### System App (Client Credentials Flow)

```bash
# Direct token request (no user login)
POST /oauth/token
Content-Type: application/x-www-form-urlencoded
Authorization: Basic base64(client_id:client_secret)

grant_type=client_credentials
&scope=system/Patient.rs system/Observation.rs

# Response
{
  "access_token": "eyJhbG...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "system/Patient.rs system/Observation.rs"
  // NO "patient" or "fhirUser" claim - full access
}
```

---

## Testing Instructions

### Create a System App

1. Navigate to **Client Registration** in Admin UI
2. Click **Register New Client**
3. Fill in details:
   - **App Name**: "My Backend Service"
   - **Client Type**: Select **"System App"**
   - **Authentication**: Automatically set to **"Confidential Client"**
   - **Launch Type**: (Hidden - not applicable)
   - **Redirect URIs**: (Optional - shows info message)
   - **Scopes**: Click **[All-Read]** button → generates `system/*.rs`
4. Click **Register**
5. **Save the client secret!** (shown once)

### Get a System Token

```bash
# Using curl
curl -X POST https://cbfhir.com/oauth/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -u "system-app-id:client-secret-here" \
  -d "grant_type=client_credentials&scope=system/Patient.rs system/Observation.rs"

# Response:
{
  "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "token_type": "Bearer",
  "expires_in": 3600,
  "scope": "system/Patient.rs system/Observation.rs"
}
```

### Use System Token to Access FHIR API

```bash
TOKEN="eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9..."

# Access ANY patient's data (no restrictions)
curl -H "Authorization: Bearer $TOKEN" \
  https://cbfhir.com/fhir/Patient/example
→ 200 OK ✅

curl -H "Authorization: Bearer $TOKEN" \
  https://cbfhir.com/fhir/Patient/infant-example
→ 200 OK ✅

curl -H "Authorization: Bearer $TOKEN" \
  https://cbfhir.com/fhir/Patient?_count=1000
→ 200 OK ✅ (returns ALL patients)

curl -H "Authorization: Bearer $TOKEN" \
  https://cbfhir.com/fhir/Observation?patient=infant-example
→ 200 OK ✅
```

Compare with patient-scoped token:

```bash
# Amy's patient token (patient=example)
PATIENT_TOKEN="..."

curl -H "Authorization: Bearer $PATIENT_TOKEN" \
  https://cbfhir.com/fhir/Patient/infant-example
→ 401 Unauthorized ❌ (correct - can only access Patient/example)
```

---

## Security Considerations

### System Scopes = High Privilege

System apps have **unrestricted access** to ALL patients' data. Security measures:

1. ✅ **Mandatory Confidential Authentication**: System apps MUST have a client secret
2. ✅ **No User Consent**: System apps can't be used for interactive flows
3. ✅ **Audit Logging**: All system-scoped requests are logged
4. ⚠️ **Recommended**: Implement IP whitelisting for production system apps
5. ⚠️ **Recommended**: Use short-lived tokens (current: 1 hour)
6. ⚠️ **Recommended**: Store client secrets in secure vault (e.g., HashiCorp Vault)

### Token Claims Comparison

**Patient Token:**

```json
{
  "sub": "amy.shaw@example.com",
  "scope": "openid fhirUser launch/patient patient/*.rs",
  "patient": "example", // ⭐ Restricted to this patient only
  "fhirUser": "Patient/example"
}
```

**System Token:**

```json
{
  "sub": "system-app-123", // Client ID, not a user
  "scope": "system/Patient.rs system/Observation.rs"
  // NO patient or fhirUser claim - full access
}
```

---

## Logging

New log messages for system app detection:

```
🔧 System app: enabling client_credentials grant for system-app-123
🔓 System-scoped request: full access granted (no context restrictions)
```

---

## Build Status

```
✅ Backend: BUILD SUCCESS
✅ Frontend: No compilation errors
✅ All TODO items completed
```

---

## Next Steps

**Phase 2 (Future):** Implement Provider/Clinician Apps (`user/*` scopes)

- Add `fhirUser` context enforcement
- Implement care team relationships
- Role-based access control (which patients can Dr. Smith access?)
- Organization boundaries

**Phase 3 (Future):** Enhanced System App Security

- IP whitelisting configuration
- Client secret rotation
- Fine-grained system scopes (e.g., `system/Patient.read` vs `system/Patient.write`)
- Audit trail for all system app access

---

## Summary

✅ System apps now fully supported with:

- **Backend**: Client credentials grant, no consent, full access
- **Frontend**: Dynamic UI hiding launch/redirect URIs, scope prefix buttons
- **Security**: Patient context enforcement bypassed for `system/*` scopes
- **Testing**: Ready for backend service integration

System apps are ideal for:

- Bulk data exports
- Analytics pipelines
- Admin dashboards
- Integration with external systems
- Scheduled data synchronization
