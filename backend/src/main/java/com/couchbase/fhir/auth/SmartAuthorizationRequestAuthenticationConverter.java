package com.couchbase.fhir.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.AuthenticationConverter;

import java.util.HashMap;
import java.util.Map;

/**
 * Wraps the default Authorization Code request converter to capture SMART-specific
 * parameters (e.g., patient_id) and attach them as additional parameters so
 * Spring Authorization Server persists them with the OAuth2Authorization.
 */
public class SmartAuthorizationRequestAuthenticationConverter implements AuthenticationConverter {
    private static final Logger log = LoggerFactory.getLogger(SmartAuthorizationRequestAuthenticationConverter.class);

    private final org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter delegate =
            new org.springframework.security.oauth2.server.authorization.web.authentication.OAuth2AuthorizationCodeRequestAuthenticationConverter();

    @Override
    public Authentication convert(HttpServletRequest request) {
        Authentication auth = delegate.convert(request);
        if (auth instanceof org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken token) {
            Map<String, Object> merged = new HashMap<>(token.getAdditionalParameters());

            String patientId = request.getParameter("patient_id");
            if (patientId != null && !patientId.isBlank()) {
                merged.put("patient_id", patientId);
                log.info("🏥 [SMART-CONVERTER] Captured patient_id parameter: {}", patientId);
            } else {
                log.debug("[SMART-CONVERTER] No patient_id parameter in request");
            }
            
            String launch = request.getParameter("launch");
            if (launch != null && !launch.isBlank()) {
                merged.put("launch", launch);
                log.debug("[SMART-CONVERTER] Captured launch parameter: {}", launch);
            }
            
            String aud = request.getParameter("aud");
            if (aud != null && !aud.isBlank()) {
                merged.put("aud", aud);
                log.debug("[SMART-CONVERTER] Captured aud parameter: {}", aud);
            }

            if (!merged.equals(token.getAdditionalParameters())) {
                try {
                    var field = org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken.class.getDeclaredField("additionalParameters");
                    field.setAccessible(true);
                    field.set(token, java.util.Collections.unmodifiableMap(merged));
                    log.info("✅ [SMART-CONVERTER] Injected additionalParameters: {}", merged);
                } catch (Throwable t) {
                    log.warn("⚠️ [SMART-CONVERTER] Could not inject additionalParameters; proceeding with original. {}", t.getMessage());
                }
            }
        }
        return auth;
    }
}
