# Consent Loop Fix - Option B (Custom Consent Page)

## Changes Made

### 1. Added OAuth2AuthorizationConsentService Bean

**File:** `AuthorizationServerConfig.java`
**Lines:** 304-311

```java
@Bean
public OAuth2AuthorizationConsentService authorizationConsentService() {
    return new InMemoryOAuth2AuthorizationConsentService();
}
```

**Why:** Spring Authorization Server needs this service to track consent state between requests.

### 2. Fixed SmartOAuthSuccessHandler Redirect Logic

**File:** `SmartOAuthSuccessHandler.java`  
**Lines:** 37-42, 109-113

**Changes:**

- Added `requestCache.setMatchingRequestParameterName(null)` to preserve SavedRequest
- Changed consent redirect to redirect to `/oauth2/authorize` instead of manually building `/consent` URL

**Why:** Let Spring Authorization Server handle the OAuth flow naturally instead of bypassing its state machine.

### 3. Fixed Consent Form Scope Submission

**File:** `consent.html`
**Lines:** 298-302

**Before:**

```html
<input type="hidden" name="scope" th:value="${scopeString}" />
```

**After:**

```html
<input
  type="hidden"
  name="scope"
  th:each="scopeItem : ${scopes}"
  th:value="${scopeItem.scope}"
/>
```

**Why:** Spring Authorization Server expects each scope as a separate `scope=X` parameter, not a single space-separated string.

### 4. Enhanced POST Logging

**File:** `AuthorizeRequestLoggingFilter.java`  
**Lines:** 23-28

Added comprehensive logging to show ALL parameters in the POST request to help debug.

### 5. Updated SmartConsentAuthenticationConverter

**File:** `SmartConsentAuthenticationConverter.java`

Made it delegate to Spring's default consent converter instead of returning null.

## How To Test

1. Rebuild and restart the server:

```bash
docker-compose restart fhir-server
```

2. Initiate OAuth flow from Inferno or your test app

3. Watch the logs for:
   - `[AUTHZ-POST] ALL Parameters:` - Shows what the consent form submits
   - Look for the state parameter consistency
   - Check if Spring generates a NEW consent state after form POST (it shouldn't!)

## What Should Happen Now

### Correct Flow:

```
1. Login successful
2. SmartOAuthSuccessHandler → Redirect to /oauth2/authorize
3. Spring creates OAuth2Authorization with consent state
4. Spring redirects to /consent?state=ABC&...
5. ConsentController displays form with state=ABC
6. User clicks Approve
7. Form POSTs to /oauth2/authorize with:
   - client_id=X
   - state=ABC
   - consent_action=approve
   - scope=openid
   - scope=fhirUser
   - scope=patient/*.rs  (each as separate parameter!)
   - ...other parameters
8. Spring looks up OAuth2Authorization by state=ABC
9. MATCH FOUND! ✓
10. Spring issues authorization code
11. Success!
```

### What To Look For In Logs:

**SUCCESS indicators:**

```
✅ [AUTHZ-POST] ALL Parameters: (shows scope as multiple entries)
✅ [AUTHZ-POST]   scope=[openid, fhirUser, patient/*.rs, ...]
✅ NO "Generated authorization consent state" after POST
✅ Authorization code issued
```

**FAILURE indicators (means still broken):**

```
❌ [AUTHZ-POST]   scope=[openid%20fhirUser%20patient/*.rs]  (single entry)
❌ "Generated authorization consent state" appears AFTER user clicks Approve
❌ State changes between consent display and POST
```

## If It Still Doesn't Work

The next things to check:

1. **Scope format** - Are scopes being submitted as individual parameters?
2. **State matching** - Does the POSTed state match what Spring stored?
3. **Request method detection** - Is Spring recognizing this as a consent POST?

We can add more targeted logging based on what we see in the `[AUTHZ-POST]` logs.

## Rollback Plan

If this breaks things worse, we can:

1. Remove the `OAuth2AuthorizationConsentService` bean
2. Revert `SmartOAuthSuccessHandler` to manually redirect to `/consent`
3. Revert consent.html scope submission
4. Go back to investigating the original working version

## Next Steps

Run the test and share the complete logs, especially:

- The `[AUTHZ-POST] ALL Parameters:` section
- Any "Generated authorization consent state" messages
- The final outcome (success or redirect loop)

This will tell us if we're on the right track or need to adjust our approach.
