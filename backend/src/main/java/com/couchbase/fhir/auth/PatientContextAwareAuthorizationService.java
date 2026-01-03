package com.couchbase.fhir.auth;

import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.HashMap;
import java.util.Map;

/**
 * Wrapper for OAuth2AuthorizationService that injects patient_id from the HTTP session
 * into the OAuth2Authorization attributes when the authorization code is issued.
 * 
 * This ensures the patient context flows through the authorization code exchange
 * and is available to the token customizer.
 */
public class PatientContextAwareAuthorizationService implements OAuth2AuthorizationService {
    private static final Logger logger = LoggerFactory.getLogger(PatientContextAwareAuthorizationService.class);
    
    private final OAuth2AuthorizationService delegate;

    public PatientContextAwareAuthorizationService(OAuth2AuthorizationService delegate) {
        this.delegate = delegate;
        logger.info("🏥 [PATIENT-CONTEXT-SERVICE] Initialized - will inject patient_id into authorizations");
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        // When saving an authorization (after consent), inject patient_id from session
        if (authorization != null) {
            logger.info("🔍 [AUTH-SERVICE] Saving authorization: id={}, state={}, grantType={}", 
                       authorization.getId(), 
                       authorization.getAttribute("state"),
                       authorization.getAuthorizationGrantType());
            
            try {
                // Try to get patient_id from HTTP session
                String patientId = getPatientIdFromSession();
                
                if (patientId != null) {
                    // Create a new authorization with the patient_id attribute
                    Map<String, Object> attributes = new HashMap<>(authorization.getAttributes());
                    attributes.put("patient_id", patientId);
                    
                    OAuth2Authorization updatedAuthorization = OAuth2Authorization.from(authorization)
                            .attributes(attrs -> attrs.putAll(attributes))
                            .build();
                    
                    logger.info("🏥 [PATIENT-CONTEXT-SERVICE] Injected patient_id='{}' into authorization (id={})", 
                               patientId, authorization.getId());
                    
                    delegate.save(updatedAuthorization);
                    return;
                }
            } catch (Exception e) {
                logger.debug("[PATIENT-CONTEXT-SERVICE] Could not inject patient_id: {}", e.getMessage());
            }
        }
        
        // No patient_id found or error - save as-is
        delegate.save(authorization);
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        if (authorization != null) {
            logger.info("🗑️  [AUTH-SERVICE] Removing authorization: id={}", authorization.getId());
        }
        delegate.remove(authorization);
    }

    @Override
    public OAuth2Authorization findById(String id) {
        OAuth2Authorization authorization = delegate.findById(id);
        logger.info("🔍 [AUTH-SERVICE] findById({}): {}", id, authorization != null ? "FOUND" : "NOT FOUND");
        logAuthorizationAttributes(authorization, "findById");
        return authorization;
    }

    @Override
    public OAuth2Authorization findByToken(String token, org.springframework.security.oauth2.server.authorization.OAuth2TokenType tokenType) {
        OAuth2Authorization authorization = delegate.findByToken(token, tokenType);
        logger.info("🔍 [AUTH-SERVICE] findByToken(type={}): {}", tokenType, authorization != null ? "FOUND (id=" + authorization.getId() + ")" : "NOT FOUND");
        logAuthorizationAttributes(authorization, "findByToken(" + tokenType + ")");
        return authorization;
    }

    private String getPatientIdFromSession() {
        try {
            ServletRequestAttributes requestAttributes = 
                (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            
            if (requestAttributes != null) {
                HttpSession session = requestAttributes.getRequest().getSession(false);
                if (session != null) {
                    Object patientId = session.getAttribute("selected_patient_id");
                    if (patientId == null) {
                        patientId = session.getAttribute("patient_id");
                    }
                    if (patientId != null) {
                        logger.debug("🔍 [PATIENT-CONTEXT-SERVICE] Found patient_id in session: {}", patientId);
                        return patientId.toString();
                    }
                }
            }
        } catch (Exception e) {
            logger.debug("[PATIENT-CONTEXT-SERVICE] Session access failed: {}", e.getMessage());
        }
        return null;
    }

    private void logAuthorizationAttributes(OAuth2Authorization authorization, String operation) {
        if (authorization != null && logger.isDebugEnabled()) {
            Object patientId = authorization.getAttribute("patient_id");
            if (patientId != null) {
                logger.debug("🏥 [PATIENT-CONTEXT-SERVICE] {} - authorization has patient_id: {}", 
                           operation, patientId);
            }
        }
    }
}

