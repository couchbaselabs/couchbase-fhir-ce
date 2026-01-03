# Patient Picker Implementation for SMART on FHIR Provider Apps

## Overview

This implementation adds a Patient Picker feature for SMART on FHIR Provider standalone launch applications. It enables practitioners to select which patient context to use when authorizing a SMART app.

## Key Features

1. **Role-Based Access**: Only users with the "practitioner" role can access the patient picker
2. **FTS-Based Queries**: Uses Full-Text Search (FTS) instead of N1QL primary index for better performance
3. **OAuth Flow Integration**: Seamlessly integrated into the OAuth authorization flow
4. **Search Capability**: Providers can search patients by ID
5. **Patient Context Storage**: Selected patient ID is stored in OAuth2Authorization and added to JWT tokens

## Implementation Components

### 1. PatientPickerService (Updated)

**File**: `backend/src/main/java/com/couchbase/fhir/auth/service/PatientPickerService.java`

**Changes**:

- Updated `searchPatients()` to use FTS with `SEARCH()` function
- Modified to use `USE KEYS` for direct ID lookups
- Uses index: `fhir.Resources.ftsPatient`

**Key Queries**:

```sql
-- Get all patients (FTS)
SELECT p.id, p.birthDate, p.gender, p.deceasedDateTime,
       p.name[0].family AS family, p.name[0].given[0] AS given
FROM `fhir`.`Resources`.`Patient` AS p
WHERE SEARCH(p, { "query": { "match_all": {} } },
      { "index": "fhir.Resources.ftsPatient" })
LIMIT 10;

-- Get specific patient by ID
SELECT p.id, p.birthDate, p.gender, p.deceasedDateTime,
       p.name[0].family AS family, p.name[0].given[0] AS given
FROM `fhir`.`Resources`.`Patient` AS p
USE KEYS "Patient/example";
```

### 2. PatientPickerController (New)

**File**: `backend/src/main/java/com/couchbase/fhir/auth/controller/PatientPickerController.java`

**Endpoints**:

- `GET /patient-picker` - Display patient selection interface
- `POST /patient-picker` - Handle patient selection

**Features**:

- Role validation (practitioner only)
- OAuth parameter preservation through flow
- Patient search functionality
- Session-based patient ID storage

### 3. Patient Picker HTML Template (New)

**File**: `backend/src/main/resources/templates/patient-picker.html`

**Features**:

- Clean, modern UI matching consent page style
- Search box for patient ID lookup
- Interactive patient table with click-to-select
- Auto-selects single patient if only one result
- Displays patient demographics (name, birth date, gender, deceased status)
- Cancel option to abort authorization

### 4. SmartOAuthSuccessHandler (New)

**File**: `backend/src/main/java/com/couchbase/fhir/auth/SmartOAuthSuccessHandler.java`

**Purpose**: Intercepts OAuth authentication success to route practitioners to patient picker

**Logic**:

```
1. User authenticates successfully
2. Check if user has "practitioner" role
3. Check if "launch/patient" scope is requested
4. If both conditions met → redirect to /patient-picker
5. Otherwise → redirect to /consent (standard flow)
```

### 5. PatientContextInjectionFilter (New)

**File**: `backend/src/main/java/com/couchbase/fhir/auth/PatientContextInjectionFilter.java`

**Purpose**: Injects selected patient_id from session into OAuth2 authorization request

**Flow**:

```
1. Patient picker stores selected_patient_id in HTTP session
2. Filter intercepts POST to /oauth2/authorize (consent submission)
3. Reads selected_patient_id from session
4. Wraps request to add patient_id as parameter
5. Clears patient_id from session
6. Spring Authorization Server stores in OAuth2Authorization
```

### 6. AuthorizationServerConfig (Updated)

**File**: `backend/src/main/java/com/couchbase/fhir/auth/AuthorizationServerConfig.java`

**Changes**:

1. Added `SmartOAuthSuccessHandler` as form login success handler
2. Added `PatientContextInjectionFilter` to filter chain
3. Updated `tokenCustomizer()` to read selected_patient_id from OAuth2Authorization

**Token Customizer Logic**:

```java
// Priority 1: Check for selected_patient_id from provider flow
if (authorization.getAttribute("selected_patient_id") != null) {
    // Use provider-selected patient
    context.getClaims().claim("patient", selectedPatientId);
}
// Priority 2: Use user's fhirUser if they are a Patient
else if (user.getFhirUser().startsWith("Patient/")) {
    // Use patient's own context
    context.getClaims().claim("patient", patientId);
}
```

### 7. SecurityConfig (Updated)

**File**: `backend/src/main/java/com/couchbase/common/config/SecurityConfig.java`

**Changes**:

1. Added `SmartOAuthSuccessHandler` injection (optional, for embedded auth server mode)
2. Updated default filter chain's form login to use the success handler

**Note**: The success handler is configured in **both** the Authorization Server chain and the default filter chain to ensure it intercepts all OAuth login flows regardless of which security chain processes the request.

## OAuth Flow Sequence

### Login Endpoint Separation

**Important**: The implementation uses separate login endpoints to avoid confusion between different authentication flows:

- **Admin UI Login**: `POST /api/auth/login` - Pure API endpoint for Admin UI JWT authentication
- **OAuth2 Login**: `GET/POST /oauth2/login` - Form-based login for SMART app OAuth flows
- **Legacy Login**: `GET/POST /login` - Kept for backwards compatibility

This separation ensures clear routing and prevents context-dependent behavior.

### Patient Standalone Launch (Existing Behavior)

```
1. App requests authorization with patient/* scopes
2. User (patient role) logs in
3. → Redirect to /consent
4. User approves/denies
5. Token issued with patient claim from user's fhirUser
```

### Provider Standalone Launch (New Behavior)

```
1. App requests authorization with launch/patient scope
2. User (practitioner role) logs in
3. → SmartOAuthSuccessHandler detects practitioner + launch/patient
4. → Redirect to /patient-picker
5. Practitioner searches and selects patient
6. Selected patient_id stored in session
7. → Redirect to /consent
8. → PatientContextInjectionFilter injects patient_id
9. User approves/denies
10. Token issued with patient claim from selected patient
```

## Role Requirements

### Patient Role

- **Purpose**: For patient standalone launch
- **Access**: Cannot access patient picker (automatic context)
- **Token**: `patient` claim from their own fhirUser

### Practitioner Role

- **Purpose**: For provider standalone launch
- **Access**: Can access patient picker when launch/patient requested
- **Token**: `patient` claim from selected patient

## Configuration Requirements

### FTS Index

The implementation requires an FTS index named `fhir.Resources.ftsPatient` on the Patient collection:

```json
{
  "name": "ftsPatient",
  "sourceName": "fhir",
  "type": "fulltext-index",
  "params": {
    "mapping": {
      "default_mapping": {
        "enabled": true,
        "dynamic": true
      }
    },
    "store": {
      "indexType": "scorch"
    }
  },
  "sourceType": "couchbase",
  "planParams": {
    "maxPartitionsPerPIndex": 1024
  }
}
```

### Security Configuration

The patient picker endpoints are protected by Spring Security and require authentication. The endpoints are added to the default filter chain which allows authenticated users to access them.

## Testing Scenarios

### Scenario 1: Provider App with launch/patient

```
1. Register SMART app with scopes: "launch/patient patient/*.rs"
2. Create practitioner user
3. Initiate authorization
4. Login as practitioner
5. Verify redirect to /patient-picker
6. Search for patient (or see first 10)
7. Select patient by clicking row
8. Click "Continue with Selected Patient"
9. Consent page should display
10. Approve → Token should have patient claim
```

### Scenario 2: Patient App without launch/patient

```
1. Register SMART app with scopes: "patient/*.rs"
2. Create patient user with fhirUser="Patient/example"
3. Initiate authorization
4. Login as patient
5. Verify redirect directly to /consent (skip picker)
6. Approve → Token should have patient claim from fhirUser
```

### Scenario 3: Patient Search by ID

```
1. In patient picker, enter "example" in search box
2. Click Search
3. Verify only Patient/example is shown
4. Verify auto-selected if only one result
5. Click Continue
```

### Scenario 4: Cancel Patient Selection

```
1. In patient picker, click Cancel
2. Verify redirect to client's redirect_uri with error=access_denied
```

## API Endpoints

### GET /patient-picker

**Parameters**:

- `client_id` (required) - OAuth client ID
- `scope` (required) - Requested scopes
- `state` (required) - OAuth state parameter
- `redirect_uri` (optional) - Client redirect URI
- `response_type` (optional, default: code) - OAuth response type
- `code_challenge` (optional) - PKCE code challenge
- `code_challenge_method` (optional) - PKCE challenge method
- `searchTerm` (optional) - Patient ID to search

**Response**: HTML patient picker page

### POST /patient-picker

**Parameters**: (Same as GET, plus:)

- `patient_id` (required if action=select) - Selected patient ID
- `action` (required) - "select" or "cancel"

**Response**: Redirect to `/consent` or error redirect

## Security Considerations

1. **Role-Based Access**: Only practitioners can access patient picker
2. **Session Security**: Patient ID stored in session with HTTP-only cookies
3. **OAuth Parameter Preservation**: All OAuth parameters preserved through flow
4. **CSRF Protection**: POST requests protected by Spring Security CSRF tokens
5. **Input Validation**: Patient ID validated before use

## Error Handling

- **Invalid Patient ID**: Redirects back to picker with error message
- **No Patient Selected**: Prevents form submission, shows alert
- **Role Validation Failure**: Shows error page
- **Database Errors**: Logged and wrapped in RuntimeException

## Future Enhancements

1. **Name-Based Search**: Add FTS-based name search (requires more complex FTS mapping)
2. **Pagination**: Add pagination for large patient sets
3. **Recent Patients**: Show practitioner's recently accessed patients
4. **Patient Details**: Show more patient information in picker
5. **Favorites**: Allow practitioners to favorite frequent patients
6. **Access Control**: Filter patients by practitioner's care team or organization

## Notes

- The implementation does NOT require a primary index on the Patient collection
- FTS index must be built before patient picker can work
- Session cleanup is automatic (patient_id removed after injection)
- Compatible with PKCE (code_challenge preserved through flow)
- Supports both authorization_code and authorization_code+PKCE flows
