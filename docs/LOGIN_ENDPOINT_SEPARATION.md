# Login Endpoint Separation

## Overview

This document describes the separation of login endpoints to avoid confusion between Admin UI and OAuth2 SMART app authentication flows.

## Problem Statement

Previously, both Admin UI and OAuth2 SMART app logins shared the same `/login` endpoint, relying on context to determine behavior. This created:

- Confusion about which authentication flow was being used
- Difficulty in debugging authentication issues
- Potential for routing errors between different flows

## Solution

Separate login endpoints with clear purposes:

### 1. Admin UI Login

**Endpoint**: `POST /api/auth/login`

**Purpose**: API-based authentication for the Admin UI (React frontend)

**Request**:

```json
{
  "email": "admin@example.com",
  "password": "password"
}
```

**Response**:

```json
{
  "token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
  "userInfo": {
    "email": "admin@example.com",
    "displayName": "Admin User",
    "role": "admin",
    "scopes": ["user/*.*", "system/*.*"]
  }
}
```

**Handler**: `AuthController.login()`

**Flow**: Direct API call → JWT token response → No redirects

### 2. OAuth2 SMART App Login

**Endpoint**: `GET /oauth2/login` (display form) + `POST /oauth2/login` (submit credentials)

**Purpose**: Form-based authentication for SMART on FHIR OAuth flows

**Handler**: Spring Security form login with `SmartOAuthSuccessHandler`

**Flow**:

```
1. User visits /oauth2/authorize
2. Not authenticated → redirect to /oauth2/login
3. User enters credentials
4. POST to /oauth2/login
5. SmartOAuthSuccessHandler determines next step:
   - Practitioner + launch/patient → /patient-picker
   - Patient or no launch/patient → /consent
6. Continue OAuth flow
```

### 3. Legacy Login (Backwards Compatibility)

**Endpoint**: `GET /login` + `POST /login`

**Purpose**: Kept for backwards compatibility with existing integrations

**Handler**: Same as OAuth2 login (uses same template and flow)

**Note**: New implementations should use `/oauth2/login`

## Configuration Changes

### AuthorizationServerConfig.java

```java
// Changed from /login to /oauth2/login
http.formLogin(form -> form
    .loginPage("/oauth2/login")
    .successHandler(smartOAuthSuccessHandler)
    .permitAll()
);

http.exceptionHandling((exceptions) -> exceptions
    .defaultAuthenticationEntryPointFor(
        new LoginUrlAuthenticationEntryPoint("/oauth2/login"),
        new MediaTypeRequestMatcher(MediaType.TEXT_HTML)
    )
);
```

### SecurityConfig.java

```java
// Allow both endpoints
.requestMatchers("/login", "/oauth2/login", "/error", "/css/**", "/js/**").permitAll()

// Use /oauth2/login for form login
.formLogin(form -> {
    form.loginPage("/oauth2/login").permitAll();
    if (smartOAuthSuccessHandler != null) {
        form.successHandler(smartOAuthSuccessHandler);
    }
})
```

### LoginController.java

```java
@GetMapping("/login")
public String login(HttpServletRequest request, HttpServletResponse response, Model model) {
    // Pass the form action URL to the template
    model.addAttribute("loginUrl", "/login");
    return "login";
}

@GetMapping("/oauth2/login")
public String oauth2Login(HttpServletRequest request, HttpServletResponse response, Model model) {
    // Pass the form action URL to the template
    model.addAttribute("loginUrl", "/oauth2/login");
    return "login";
}
```

### login.html Template

**Important**: The form uses a model attribute to determine where to submit:

```html
<!-- Dynamic form action based on controller-provided loginUrl -->
<form
  method="post"
  th:action="${loginUrl != null ? loginUrl : '/login'}"
></form>
```

This ensures:

- Form loaded from `/login` → Controller sets `loginUrl="/login"` → POSTs to `/login`
- Form loaded from `/oauth2/login` → Controller sets `loginUrl="/oauth2/login"` → POSTs to `/oauth2/login`
- Fallback to `/login` if loginUrl is not set (defensive programming)

## Benefits

### 1. Clear Separation of Concerns

- Admin authentication is pure API (JSON)
- OAuth2 authentication is form-based (HTML)
- No context-dependent routing

### 2. Easier Debugging

- Logs clearly show which authentication flow is being used
- Network traces show different endpoints
- Error messages can be flow-specific

### 3. Better Security

- Can apply different rate limiting per endpoint
- Can add flow-specific security headers
- Can implement different CSRF strategies

### 4. Future Extensibility

- Can customize OAuth2 login page separately from admin
- Can add OAuth2-specific features (e.g., social login buttons)
- Can version endpoints independently

## URL Routing Matrix

| Endpoint            | Method | Purpose              | Handler                 | Response Type     |
| ------------------- | ------ | -------------------- | ----------------------- | ----------------- |
| `/api/auth/login`   | POST   | Admin UI login       | AuthController          | JSON (JWT)        |
| `/oauth2/login`     | GET    | OAuth2 login page    | LoginController         | HTML (form)       |
| `/oauth2/login`     | POST   | OAuth2 login submit  | Spring Security         | Redirect          |
| `/login`            | GET    | Legacy login page    | LoginController         | HTML (form)       |
| `/login`            | POST   | Legacy login submit  | Spring Security         | Redirect          |
| `/oauth2/authorize` | GET    | OAuth2 authorization | Spring OAuth Server     | Redirect to login |
| `/oauth2/token`     | POST   | Token exchange       | Spring OAuth Server     | JSON (tokens)     |
| `/patient-picker`   | GET    | Patient selection    | PatientPickerController | HTML              |
| `/consent`          | GET    | OAuth consent        | ConsentController       | HTML              |

## Migration Path

### For Existing SMART Apps

**No changes required** - OAuth2 flows automatically use `/oauth2/login`

### For Admin UI

**No changes required** - Admin UI uses `/api/auth/login`

### For Custom Integrations

If you have custom code referencing `/login`:

1. Update to use `/oauth2/login` for OAuth flows
2. Or keep using `/login` (still supported)

## Testing

### Test Admin Login

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@example.com","password":"password"}'
```

Expected: JSON response with JWT token

### Test OAuth2 Login

```bash
# Visit in browser (requires interactive form)
http://localhost:8080/oauth2/authorize?client_id=...&scope=...

# Will redirect to:
http://localhost:8080/oauth2/login
```

Expected: HTML login form

### Verify Separation

Check logs during authentication:

```
# OAuth2 flow should show:
DefaultAuthenticationEntryPoint - Redirecting to /oauth2/login
SmartOAuthSuccessHandler - Authentication successful for user: ...

# Admin flow should show:
AuthController - Authenticated via Admin.users (doc lookup)
```

## Future Enhancements

### 1. Custom OAuth2 Login Page

Create a separate template for OAuth2 that shows:

- App name/logo that's requesting access
- Scopes being requested
- "Sign in with..." social providers
- "Don't have an account?" link

### 2. Login Page Branding

Different branding for:

- Admin UI login (professional, internal)
- OAuth2 login (patient-friendly, external)

### 3. Multi-Factor Authentication

Could implement different MFA strategies:

- Admin: TOTP/SMS (stricter)
- OAuth2: Email verification (user-friendly)

### 4. Rate Limiting

Apply different rate limits:

- Admin: Higher limit (fewer users)
- OAuth2: Lower limit (public-facing)

## Summary

The login endpoint separation provides:

- ✅ Clear distinction between authentication flows
- ✅ Easier debugging and maintenance
- ✅ Better security through separation of concerns
- ✅ Backwards compatibility with existing code
- ✅ Foundation for future enhancements

This is a best practice for systems with multiple authentication mechanisms.
