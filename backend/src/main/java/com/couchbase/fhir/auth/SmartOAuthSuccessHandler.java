package com.couchbase.fhir.auth;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.web.authentication.AuthenticationSuccessHandler;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Custom OAuth Authentication Success Handler for SMART on FHIR
 * 
 * Intercepts successful authentication during OAuth flow to determine next step:
 * 1. If practitioner + launch/patient scope requested → redirect to /patient-picker
 * 2. Otherwise → continue to /consent (default OAuth flow)
 * 
 * This enables provider standalone launch where practitioners select a patient context.
 */
@Component
@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "false", matchIfMissing = true)
public class SmartOAuthSuccessHandler implements AuthenticationSuccessHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartOAuthSuccessHandler.class);
    private final HttpSessionRequestCache requestCache = new HttpSessionRequestCache();
    
    public SmartOAuthSuccessHandler() {
        logger.info("🔧 [SMART-AUTH] SmartOAuthSuccessHandler bean created");
    }
    
    @Override
    public void onAuthenticationSuccess(
            HttpServletRequest request,
            HttpServletResponse response,
            Authentication authentication) throws IOException, ServletException {
        
        logger.info("🔐 [SMART-AUTH] Authentication successful for user: {}", authentication.getName());
        
        // Get the original OAuth authorization request that was saved before login
        SavedRequest savedRequest = requestCache.getRequest(request, response);
        
        if (savedRequest == null) {
            logger.debug("ℹ️  No saved request found - not an OAuth flow, using default success URL");
            response.sendRedirect("/");
            return;
        }
        
        String savedUrl = savedRequest.getRedirectUrl();
        logger.debug("🔍 [SMART-AUTH] Saved request URL: {}", savedUrl);
        
        // Check if this is an OAuth authorization request
        if (!savedUrl.contains("/oauth2/authorize")) {
            logger.debug("ℹ️  Not an OAuth authorization request - continuing to: {}", savedUrl);
            response.sendRedirect(savedUrl);
            return;
        }
        
        // Extract OAuth parameters from the saved request
        String clientId = getParameter(savedRequest, OAuth2ParameterNames.CLIENT_ID);
        String scope = getParameter(savedRequest, OAuth2ParameterNames.SCOPE);
        String state = getParameter(savedRequest, OAuth2ParameterNames.STATE);
        String redirectUri = getParameter(savedRequest, OAuth2ParameterNames.REDIRECT_URI);
        String responseType = getParameter(savedRequest, OAuth2ParameterNames.RESPONSE_TYPE);
        String codeChallenge = getParameter(savedRequest, "code_challenge");
        String codeChallengeMethod = getParameter(savedRequest, "code_challenge_method");
        
        logger.debug("🔍 [SMART-AUTH] OAuth params: client_id={}, scope={}, state={}", 
                    clientId, scope, state);
        
        // Check if user has practitioner role
        boolean isPractitioner = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .anyMatch(auth -> 
                    auth.equals("ROLE_PRACTITIONER") || 
                    auth.equalsIgnoreCase("practitioner"));
        
        // Check if launch/patient scope is requested
        boolean requiresPatientContext = scope != null && scope.contains("launch/patient");
        
        logger.info("🔍 [SMART-AUTH] User role check: isPractitioner={}, requiresPatientContext={}, authorities={}", 
                   isPractitioner, requiresPatientContext, authentication.getAuthorities());
        
        // If practitioner requesting launch/patient scope → redirect to patient picker
        if (isPractitioner && requiresPatientContext) {
            logger.info("🏥 [SMART-AUTH] Redirecting to patient picker (practitioner + launch/patient)");
            String pickerUrl = buildPatientPickerUrl(clientId, scope, state, redirectUri, 
                                                     responseType, codeChallenge, codeChallengeMethod);
            logger.info("🏥 [SMART-AUTH] Patient picker URL: {}", pickerUrl);
            
            response.sendRedirect(pickerUrl);
            logger.info("🏥 [SMART-AUTH] Redirect sent successfully, response committed: {}", response.isCommitted());
            return;
        }
        
        // Otherwise, let Spring Authorization Server handle the OAuth flow
        // It will redirect to /consent automatically with proper state management
        // Now that we have OAuth2AuthorizationConsentService, Spring can track consent state properly
        logger.info("✅ [SMART-AUTH] Continuing OAuth flow - redirecting to authorization endpoint");
        response.sendRedirect(savedUrl);  // Redirect to original /oauth2/authorize request
    }
    
    /**
     * Build patient picker URL with OAuth parameters
     */
    private String buildPatientPickerUrl(String clientId, String scope, String state, 
                                         String redirectUri, String responseType,
                                         String codeChallenge, String codeChallengeMethod) {
        StringBuilder url = new StringBuilder("/patient-picker?");
        appendParam(url, OAuth2ParameterNames.CLIENT_ID, clientId);
        appendParam(url, OAuth2ParameterNames.SCOPE, scope);
        appendParam(url, OAuth2ParameterNames.STATE, state);
        appendParam(url, OAuth2ParameterNames.REDIRECT_URI, redirectUri);
        appendParam(url, OAuth2ParameterNames.RESPONSE_TYPE, responseType);
        appendParam(url, "code_challenge", codeChallenge);
        appendParam(url, "code_challenge_method", codeChallengeMethod);
        
        // Remove trailing &
        if (url.charAt(url.length() - 1) == '&') {
            url.deleteCharAt(url.length() - 1);
        }
        
        return url.toString();
    }
    
    /**
     * Append parameter to URL if not null
     */
    private void appendParam(StringBuilder url, String name, String value) {
        if (value != null && !value.isEmpty()) {
            url.append(name).append("=").append(encode(value)).append("&");
        }
    }
    
    /**
     * URL encode parameter value
     */
    private String encode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.toString());
        } catch (Exception e) {
            logger.warn("Failed to URL encode value: {}", value);
            return value;
        }
    }
    
    /**
     * Get parameter from saved request
     */
    private String getParameter(SavedRequest savedRequest, String paramName) {
        String[] values = savedRequest.getParameterValues(paramName);
        return (values != null && values.length > 0) ? values[0] : null;
    }
}

