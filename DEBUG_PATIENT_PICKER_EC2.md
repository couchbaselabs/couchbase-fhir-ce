# Patient Picker Debug - EC2 vs Mac Issue

## Problem Description

When testing SMART on FHIR standalone provider launch with Inferno:

- **Mac (Docker)**: After practitioner login → Patient picker is shown ✅
- **EC2**: After practitioner login → Goes straight to consent (skips patient picker) ❌

Both environments are using:

- Same code (from GitHub)
- Same `config.yaml`
- Same Couchbase setup
- Same Inferno test

## Root Cause Analysis

The patient picker is controlled by `SmartOAuthSuccessHandler` which checks TWO conditions:

1. **User has practitioner role**: `ROLE_PRACTITIONER` authority
2. **Scope contains "launch/patient"**: Must be in the OAuth request

If BOTH conditions are true → Redirect to patient picker
If EITHER is false → Skip to consent

## Enhanced Logging Added

I've added extensive logging to `SmartOAuthSuccessHandler.java` to help debug this issue.

### Startup Logs

When the application starts, you should see:

```
🔧 [SMART-AUTH] ========================================
🔧 [SMART-AUTH] SmartOAuthSuccessHandler bean created
🔧 [SMART-AUTH] Patient picker routing is ENABLED
🔧 [SMART-AUTH] ========================================
```

**If you don't see this on EC2**, it means the bean is not being created. This could happen if:

- `app.security.use-keycloak` is set to `true` in the environment
- The config is somehow different

### Authentication Success Logs

When a practitioner logs in, you should see detailed logs:

```
🔐 [SMART-AUTH] Authentication successful for user: ronald.bone@example.org
🔍 [SMART-AUTH] ========================================
🔍 [SMART-AUTH] OAuth params: client_id=..., scope=..., state=...
🔍 [SMART-AUTH] User 'ronald.bone@example.org' authorities (total: 1)
🔍 [SMART-AUTH]   - Authority: 'ROLE_PRACTITIONER'
🔍 [SMART-AUTH]   - MATCH: 'ROLE_PRACTITIONER' matches practitioner role
🔍 [SMART-AUTH] Decision criteria:
🔍 [SMART-AUTH]   - isPractitioner: true
🔍 [SMART-AUTH]   - requiresPatientContext: true
🔍 [SMART-AUTH]   - scope: 'launch/patient openid fhirUser offline_access patient/...'
🔍 [SMART-AUTH]   - scope contains 'launch/patient': true
🔍 [SMART-AUTH] ========================================
```

Then:

**If redirecting to patient picker:**

```
🏥 [SMART-AUTH] ✅ REDIRECTING TO PATIENT PICKER (practitioner + launch/patient)
🏥 [SMART-AUTH] Patient picker URL: /patient-picker?client_id=...&scope=...
🏥 [SMART-AUTH] Redirect sent successfully, response committed: true
```

**If skipping patient picker:**

```
❌ [SMART-AUTH] SKIPPING PATIENT PICKER - Redirecting to consent
❌ [SMART-AUTH] Reason: isPractitioner=false, requiresPatientContext=false
✅ [SMART-AUTH] Continuing OAuth flow - redirecting to authorization endpoint
```

## What to Check on EC2

1. **Check if SmartOAuthSuccessHandler bean is created:**

   ```bash
   docker logs couchbase-fhir-backend 2>&1 | grep "SmartOAuthSuccessHandler bean created"
   ```

2. **Check authentication success logs:**

   ```bash
   docker logs couchbase-fhir-backend 2>&1 | grep "\[SMART-AUTH\]" | tail -50
   ```

3. **Look for the decision criteria:**
   - Is `isPractitioner` true or false?
   - Is `requiresPatientContext` true or false?
   - What authorities are being assigned to the user?
   - What scope is being passed in the OAuth request?

## Possible Issues

### Issue 1: User doesn't have practitioner role

**Symptoms:**

```
🔍 [SMART-AUTH] User 'ronald.bone@example.org' authorities (total: 0)
🔍 [SMART-AUTH]   - isPractitioner: false
```

**Solution:** Check the user in Couchbase:

```sql
SELECT * FROM `fhir`.`_default`.`_default` WHERE META().id = 'Admin::User::ronald.bone@example.org';
```

The user should have:

```json
{
  "role": "practitioner",
  "status": "active",
  "authMethod": "local"
}
```

### Issue 2: Scope doesn't contain "launch/patient"

**Symptoms:**

```
🔍 [SMART-AUTH]   - scope: 'openid fhirUser offline_access patient/...'
🔍 [SMART-AUTH]   - scope contains 'launch/patient': false
🔍 [SMART-AUTH]   - requiresPatientContext: false
```

**Solution:** This is an Inferno configuration issue. The test should request `launch/patient` scope for standalone provider launch.

### Issue 3: SmartOAuthSuccessHandler not created

**Symptoms:** No startup logs showing bean creation.

**Possible causes:**

- `app.security.use-keycloak=true` in environment variables
- Docker environment variables override

**Solution:** Check environment variables:

```bash
docker exec couchbase-fhir-backend env | grep -i keycloak
```

## Testing the Fix

After pulling the updated code:

1. **Rebuild and restart:**

   ```bash
   curl -sSL https://raw.githubusercontent.com/couchbaselabs/couchbase-fhir-ce/master/install.sh | bash -s -- ./config.yaml
   ```

2. **Check startup logs:**

   ```bash
   docker logs couchbase-fhir-backend 2>&1 | grep "SmartOAuthSuccessHandler"
   ```

3. **Run Inferno test and check logs:**

   ```bash
   docker logs -f couchbase-fhir-backend 2>&1 | grep "\[SMART-AUTH\]"
   ```

4. **Look for the decision section** - it will tell you exactly why the patient picker was shown or skipped.

## Next Steps

1. Pull the updated code on EC2
2. Check the logs as described above
3. Share the `[SMART-AUTH]` logs so we can see exactly what's happening
4. The logs will tell us whether:
   - The handler is being created
   - What authorities the user has
   - What scope is being requested
   - Why the patient picker is being skipped

The enhanced logging will make the exact issue clear!
