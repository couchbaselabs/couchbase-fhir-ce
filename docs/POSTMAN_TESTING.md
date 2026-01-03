# Testing OAuth Flow with Postman

## Quick Token Exchange Test

Yes! You can absolutely use Postman to test the token endpoint directly. This is much faster for debugging.

### POST /oauth2/token

**URL**: `https://dev.cbfhir.com/oauth2/token`

**Method**: `POST`

**Headers**:

```
Content-Type: application/x-www-form-urlencoded
Authorization: Basic <base64(client_id:client_secret)>
```

**Body** (x-www-form-urlencoded):

```
grant_type=authorization_code
code=<your_authorization_code>
redirect_uri=https://inferno.healthit.gov/suites/custom/smart/redirect
code_verifier=<your_code_verifier>
```

### Example from Your Log:

```
POST https://dev.cbfhir.com/oauth2/token
Content-Type: application/x-www-form-urlencoded

code=MizSxWM_tuji3OCeVX9KbS9Olsa4-5LiwyfzUOivn2Rc2gpZv3WzT_YkvQG0-USK-vzjdnPIjg8RfJ37h0O969Q5t697zTTpThOFWSfGXgG_dytFJmR7cCh-23AI_iKl
&code_verifier=2b7128fa-ac06-4b89-8880-f0481e51e7bd-c71cdb56-ba5b-446e-8c88-a9aa6f8e51e2
&grant_type=authorization_code
&redirect_uri=https%3A%2F%2Finferno.healthit.gov%2Fsuites%2Fcustom%2Fsmart%2Fredirect
```

## Important Notes

### Authorization Code is Single-Use!

⚠️ **Each authorization code can only be used ONCE.** After you exchange it for tokens, it becomes invalid.

So you can't just copy the code from Inferno and reuse it in Postman multiple times. You need to:

1. Run through the OAuth flow once to get a fresh code
2. Use that code immediately in Postman before it expires
3. Get a new code for each test

### Alternative: Test with Refresh Token

A better approach for Postman testing is to use the **refresh token** once you have one:

```
POST https://dev.cbfhir.com/oauth2/token
Content-Type: application/x-www-form-urlencoded

grant_type=refresh_token
refresh_token=<your_refresh_token>
client_id=app-5d6924fa-d19d-4279-9f21-6c6ed0ae443c
client_secret=<your_client_secret>
```

This way you can test the token response structure repeatedly without going through the full OAuth flow each time!

## Expected Response Structure

### Current (Missing Patient):

```json
{
  "access_token": "eyJ...",
  "refresh_token": "CU...",
  "scope": "launch/patient openid patient/*.rs offline_access fhirUser",
  "id_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3599,
  "fhirUser": "Practitioner/practitioner-1"
  // ❌ patient is missing!
}
```

### Expected (With Patient):

```json
{
  "access_token": "eyJ...",
  "refresh_token": "CU...",
  "scope": "launch/patient openid patient/*.rs offline_access fhirUser",
  "id_token": "eyJ...",
  "token_type": "Bearer",
  "expires_in": 3599,
  "fhirUser": "Practitioner/practitioner-1",
  "patient": "example" // ✅ Added by TokenResponseEnhancer
}
```

## How It Works

The `SmartTokenEnhancerFilter` intercepts the `/oauth2/token` response and uses `TokenResponseEnhancer` to:

1. Decode the JWT access token
2. Extract `patient` and `fhirUser` claims
3. Add them to the top-level JSON response

**New Logs to Watch**:

```
🔍 [TOKEN-ENHANCER] JWT claims: patient=example, fhirUser=Practitioner/practitioner-1
✅ [TOKEN-ENHANCER] Added 'patient' to token response: example
✅ [TOKEN-ENHANCER] Added 'fhirUser' to token response: Practitioner/practitioner-1
🎫 [TOKEN-ENHANCER] Enhanced token response
```

**If patient is missing from JWT**:

```
⚠️ [TOKEN-ENHANCER] No 'patient' claim in JWT!
```

This tells us the problem is earlier - the token customizer isn't adding the patient claim to the JWT.

## Postman Collection Setup

### 1. Create Environment Variables

```
base_url: https://dev.cbfhir.com
client_id: app-5d6924fa-d19d-4279-9f21-6c6ed0ae443c
client_secret: <your_secret>
redirect_uri: https://inferno.healthit.gov/suites/custom/smart/redirect
```

### 2. Authorization Code Exchange

```
POST {{base_url}}/oauth2/token
Headers:
  Content-Type: application/x-www-form-urlencoded
  Authorization: Basic {{base64(client_id:client_secret)}}
Body:
  grant_type: authorization_code
  code: {{authorization_code}}
  redirect_uri: {{redirect_uri}}
  code_verifier: {{code_verifier}}
```

### 3. Refresh Token Exchange (Repeatable!)

```
POST {{base_url}}/oauth2/token
Headers:
  Content-Type: application/x-www-form-urlencoded
Body:
  grant_type: refresh_token
  refresh_token: {{refresh_token}}
  client_id: {{client_id}}
  client_secret: {{client_secret}}
```

### 4. Test FHIR API with Token

```
GET {{base_url}}/fhir/Patient/example
Headers:
  Authorization: Bearer {{access_token}}
```

## Debugging Steps

1. **Get a fresh authorization code** by running through the OAuth flow
2. **Exchange it in Postman** immediately
3. **Check the response** - does it have `patient` field?
4. **Check the logs**:
   ```bash
   docker logs -f fhir-server 2>&1 | grep "TOKEN-ENHANCER"
   ```
5. **If no patient in response**, check if it's in the JWT:
   - Copy the `access_token`
   - Paste into https://jwt.io
   - Look for `patient` claim in the payload

## Quick Test Script

Save this as a Postman "Tests" script to auto-extract tokens:

```javascript
// After token exchange, save tokens to environment
if (pm.response.code === 200) {
  var jsonData = pm.response.json();
  pm.environment.set("access_token", jsonData.access_token);
  pm.environment.set("refresh_token", jsonData.refresh_token);
  pm.environment.set("id_token", jsonData.id_token);

  // Check for patient claim
  if (jsonData.patient) {
    console.log("✅ Patient claim present: " + jsonData.patient);
  } else {
    console.log("❌ Patient claim missing!");
  }

  // Check for fhirUser claim
  if (jsonData.fhirUser) {
    console.log("✅ fhirUser claim present: " + jsonData.fhirUser);
  } else {
    console.log("❌ fhirUser claim missing!");
  }
}
```

---

## Summary

- ✅ Yes, you can use Postman to test token exchange
- ⚠️ Authorization codes are single-use (can't reuse)
- ✅ Use refresh tokens for repeated testing
- ✅ Enhanced logging added to `TokenResponseEnhancer`
- ✅ Response should include `patient` at top level
- 🔍 Check logs to see if `patient` is in the JWT first

After rebuild, watch for the new enhancer logs to see exactly what's happening!

---

_Last updated: 2026-01-02_
