package com.couchbase.fhir.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

/**
 * Custom authentication converter for OAuth2 consent approval.
 * Captures patient_id from the request and adds it to the authorization,
 * then delegates to Spring's default consent converter.
 */
public class SmartConsentAuthenticationConverter implements AuthenticationConverter {
    private static final Logger logger = LoggerFactory.getLogger(SmartConsentAuthenticationConverter.class);
    
    // Delegate to Spring Authorization Server's default consent converter
    private final org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter delegate =
            new org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationConsentAuthenticationConverter();

    @Override
    public Authentication convert(HttpServletRequest request) {
        // Extract patient_id from request before delegating
        String patientId = request.getParameter("patient_id");
        if (StringUtils.hasText(patientId)) {
            logger.info("🏥 [SMART-CONVERTER] Captured patient_id parameter: {}", patientId);
            // Store in request attribute so it can be accessed by authorization handler
            request.setAttribute("patient_id", patientId);
        }
        
        // Let Spring Authorization Server's default consent converter handle the OAuth processing
        return delegate.convert(request);
    }
}
