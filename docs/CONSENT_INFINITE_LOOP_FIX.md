# Consent Infinite Loop Fix - Complete Solution

## Problem: Consent Form Redirect Loop

### Symptoms

- User clicks "Approve" on consent form
- Form gets stuck in infinite redirect loop
- State parameter changes on each request:
  ```
  Request 1: state=RZloooE5...
  Request 2: state=FKriMW_T...  (DIFFERENT!)
  ```
- Network shows continuous 302 redirects between `/oauth2/authorize` and `/consent`

### Root Cause Analysis

The issue had **TWO interrelated problems**:

#### Problem 1: Missing OAuth2AuthorizationConsentService Bean

Spring Authorization Server requires `OAuth2AuthorizationConsentService` to track consent state across requests. Without it:

1. User submits consent (POST `/oauth2/authorize`)
2. Spring processes the request but has **no way to remember** the consent state
3. Spring thinks consent is still needed
4. Spring generates a **NEW consent state**
5. Redirects back to `/consent` with the new state
6. Infinite loop

**Evidence from logs:**

```
21:54:47 - Consent displayed: state=RZloooE5...
21:54:49 - User clicks approve
21:54:50 - "Generated authorization consent state" ← NEW STATE CREATED
21:54:50 - Consent displayed AGAIN: state=FKriMW_T... (DIFFERENT!)
```

#### Problem 2: Manual Consent Redirect Breaking OAuth Flow

`SmartOAuthSuccessHandler` was manually building and redirecting to `/consent`:

```java
// BEFORE (Broken)
String consentUrl = buildConsentUrl(clientId, scope, state, ...);
response.sendRedirect(consentUrl);  // Manual redirect to /consent
```

This bypassed Spring's internal OAuth state machine:

1. User authenticates successfully
2. Handler manually redirects to `/consent?state=ABC`
3. Spring Authorization Server sees the consent page request
4. Spring thinks this is a NEW authorization attempt
5. Spring creates NEW OAuth2Authorization with NEW state=XYZ
6. Redirects to `/consent?state=XYZ`
7. User approves with state=XYZ
8. Spring still has OLD state=ABC in memory → Mismatch
9. Redirect loop

## The Complete Fix

### Change 1: Add OAuth2AuthorizationConsentService Bean

**File:** `AuthorizationServerConfig.java`

**Added after `authorizationService()` method:**

```java
/**
 * OAuth2 Authorization Consent Service
 * Tracks consent state to prevent infinite redirect loops during consent flow.
 * Without this, Spring generates a new consent state on every request.
 */
@Bean
public org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService authorizationConsentService() {
    // In-memory consent service - stores user consent decisions
    return new org.springframework.security.oauth2.server.authorization.InMemoryOAuth2AuthorizationConsentService();
}
```

**Why this works:**

- Spring Authorization Server can now persist consent state between requests
- When user clicks "Approve", Spring can look up the matching consent authorization
- State remains consistent throughout the flow

### Change 2: Let Spring Handle Consent Redirect

**File:** `SmartOAuthSuccessHandler.java`

**Line 37-43: Keep SavedRequest in session**

```java
public SmartOAuthSuccessHandler() {
    logger.info("🔧 [SMART-AUTH] SmartOAuthSuccessHandler bean created");
    // This is critical: prevent the cache from clearing the request
    // Setting matchingRequestParameterName to null disables the "continue" parameter check
    // which prevents automatic removal of the SavedRequest
    this.requestCache.setMatchingRequestParameterName(null);
}
```

**Line 109-114: Changed redirect logic**

**BEFORE (Broken):**

```java
// Otherwise, continue to consent page (default OAuth flow)
logger.info("✅ [SMART-AUTH] Redirecting to consent page (default OAuth flow)");
String consentUrl = buildConsentUrl(clientId, scope, state, redirectUri,
                   responseType, codeChallenge, codeChallengeMethod);
response.sendRedirect(consentUrl);
```

**AFTER (Fixed):**

```java
// Otherwise, let Spring Authorization Server handle the OAuth flow
// It will redirect to /consent automatically with proper state management
// Now that we have OAuth2AuthorizationConsentService, Spring can track consent state properly
logger.info("✅ [SMART-AUTH] Continuing OAuth flow - redirecting to authorization endpoint");
response.sendRedirect(savedUrl);  // Redirect to original /oauth2/authorize request
```

**Removed:** `buildConsentUrl()` method (no longer needed)

**Why this works:**

- We redirect back to the ORIGINAL `/oauth2/authorize` URL
- Spring Authorization Server processes it properly:
  - User is authenticated ✓
  - Consent is required → Creates `OAuth2AuthorizationConsent` with state
  - Redirects to `/consent` with the SAME state
  - User approves → POST to `/oauth2/authorize` with SAME state
  - Spring looks up consent by state → Match found ✓
  - Issues authorization code ✓

## OAuth Flow - Before vs After

### BEFORE (Broken Flow)

```
1. Client → GET /oauth2/authorize?state=ABC
2. Server → Not authenticated → Redirect to /oauth2/login
3. User logs in successfully
4. SmartOAuthSuccessHandler intercepts
5. Handler → Manual redirect to /consent?state=ABC
6. Spring sees consent request, creates NEW state=XYZ
7. Spring → Redirect to /consent?state=XYZ
8. User clicks "Approve"
9. Form → POST /oauth2/authorize with state=XYZ
10. Spring looks for consent with state=XYZ → NOT FOUND (no consent service!)
11. Spring thinks consent needed, generates NEW state=QRS
12. Spring → Redirect to /consent?state=QRS
13. INFINITE LOOP
```

### AFTER (Fixed Flow)

```
1. Client → GET /oauth2/authorize?state=ABC
2. Server → Not authenticated → Redirect to /oauth2/login
3. User logs in successfully
4. SmartOAuthSuccessHandler intercepts
5. Handler → Redirect to /oauth2/authorize?state=ABC (SAME URL)
6. Spring processes authorization:
   - User authenticated ✓
   - Consent required → Create OAuth2AuthorizationConsent (stored in service)
   - Generate internal consent state=XYZ
7. Spring → Redirect to /consent?state=XYZ
8. User clicks "Approve"
9. Form → POST /oauth2/authorize with state=XYZ
10. Spring looks up consent with state=XYZ → FOUND in OAuth2AuthorizationConsentService ✓
11. State matches ✓
12. Spring → Issue authorization code
13. Spring → Redirect to client with code
14. SUCCESS ✓
```

## Key Principles

### 1. Let Spring Control the OAuth State Machine

Don't manually redirect to `/consent`. Always redirect to `/oauth2/authorize` and let Spring handle the rest.

### 2. Provide Required Services

Spring Authorization Server requires these services to function properly:

- ✅ `OAuth2AuthorizationService` - Tracks issued authorizations (we already had this)
- ✅ `OAuth2AuthorizationConsentService` - Tracks consent state (THIS WAS MISSING!)
- ✅ `RegisteredClientRepository` - Tracks registered clients (we already had this)

### 3. Keep SavedRequest Available

The `SavedRequest` contains the original OAuth parameters. By setting:

```java
requestCache.setMatchingRequestParameterName(null);
```

We prevent Spring Security from automatically clearing it, which is needed for parameter recovery in `ConsentController`.

## Patient Picker Flow (Still Works)

The patient picker flow already follows the correct pattern:

```java
// PatientPickerController.java line 165-183
StringBuilder authorizeUrl = new StringBuilder("/oauth2/authorize");
authorizeUrl.append("?response_type=").append(responseType);
// ... add all OAuth parameters
return "redirect:" + authorizeUrl.toString();  // ✓ Redirects to /oauth2/authorize
```

This is why patient picker already works correctly - it redirects to `/oauth2/authorize`, not directly to `/consent`.

## Testing the Fix

### Test 1: Standard Patient Login Flow

```
1. Go to: https://dev.cbfhir.com/oauth2/authorize?client_id=...&state=TEST123&...
2. Login as patient user
3. Should redirect to /consent with state=TEST123
4. Click "Approve"
5. Should redirect back to client with authorization code ✓
6. NO infinite loop ✓
```

### Test 2: Practitioner with Patient Picker

```
1. Go to: https://dev.cbfhir.com/oauth2/authorize?client_id=...&scope=launch/patient ...
2. Login as practitioner
3. Should redirect to /patient-picker
4. Select patient
5. Should redirect to /consent with consistent state
6. Click "Approve"
7. Should redirect back to client with authorization code ✓
8. Token should contain patient claim ✓
```

### What to Look For in Logs

**SUCCESS indicators:**

```
✅ "Saved authorization consent state"
✅ State parameter stays consistent across requests
✅ No "Generated authorization consent state" after user clicks approve
✅ "Authorization granted" message
```

**FAILURE indicators (should not see these):**

```
❌ State changes between consent display and form submission
❌ Multiple "Generated authorization consent state" messages
❌ Repeated consent page loads without user action
```

## Files Modified

1. **AuthorizationServerConfig.java**

   - Added `OAuth2AuthorizationConsentService` bean
   - Lines: 304-311 (new method after `authorizationService()`)

2. **SmartOAuthSuccessHandler.java**
   - Added `setMatchingRequestParameterName(null)` in constructor
   - Changed consent redirect to redirect to `/oauth2/authorize` instead
   - Removed `buildConsentUrl()` method
   - Lines modified: 39-42, 109-114

## Why This is the Correct Approach

This solution follows Spring Authorization Server's intended architecture:

1. **Separation of Concerns**: Authentication success handler shouldn't know about consent logic
2. **State Management**: Let Spring manage OAuth state, don't try to track it manually
3. **Standard Beans**: Provide all required services (Authorization, Consent, Client Repository)
4. **Request Flow**: Always flow through proper endpoints, don't skip steps

## Conclusion

The fix ensures that:

- ✅ Spring Authorization Server has proper consent state tracking
- ✅ OAuth flow goes through proper channels (no shortcuts)
- ✅ State parameter remains consistent throughout flow
- ✅ Consent is properly recorded and matched on approval
- ✅ No infinite redirect loops

**Key Takeaway**: When working with Spring Authorization Server, provide all required services and let the framework handle the OAuth state machine. Don't try to manually manage state or skip steps in the flow.
