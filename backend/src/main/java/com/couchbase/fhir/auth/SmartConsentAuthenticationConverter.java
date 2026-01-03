package com.couchbase.fhir.auth;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationCodeRequestAuthenticationToken;
import org.springframework.security.oauth2.server.authorization.authentication.OAuth2AuthorizationConsentAuthenticationToken;
import org.springframework.security.web.authentication.AuthenticationConverter;
import org.springframework.util.StringUtils;

import java.util.*;

/**
 * Custom authentication converter for OAuth2 consent approval.
 * Captures patient_id from the request and adds it to the authorization.
 */
public class SmartConsentAuthenticationConverter implements AuthenticationConverter {
    private static final Logger logger = LoggerFactory.getLogger(SmartConsentAuthenticationConverter.class);

    @Override
    public Authentication convert(HttpServletRequest request) {
        if (!"POST".equals(request.getMethod())) {
            return null;
        }

        String consentAction = request.getParameter("consent_action");
        if (!"approve".equals(consentAction)) {
            return null; // User denied consent
        }

        // Extract patient_id from request
        String patientId = request.getParameter("patient_id");
        if (StringUtils.hasText(patientId)) {
            logger.info("🏥 [CONSENT-CONVERTER] Captured patient_id from consent form: {}", patientId);
            // Store in request attribute so it can be accessed by authorization handler
            request.setAttribute("patient_id", patientId);
        } else {
            logger.debug("[CONSENT-CONVERTER] No patient_id in consent request");
        }

        // Let Spring Authorization Server's default consent converter handle the rest
        return null;
    }
}

