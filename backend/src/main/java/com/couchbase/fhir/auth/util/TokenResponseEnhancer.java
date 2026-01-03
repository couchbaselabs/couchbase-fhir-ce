package com.couchbase.fhir.auth.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * Utility to enhance token response JSON by extracting claims from the access_token JWT
 * and adding top-level fields such as `patient` and `fhirUser` when present.
 */
public class TokenResponseEnhancer {
    private static final Logger logger = LoggerFactory.getLogger(TokenResponseEnhancer.class);
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String enhance(String originalJson) {
        if (originalJson == null || originalJson.isBlank()) return originalJson;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> tokenResponse = mapper.readValue(originalJson, Map.class);

            // If this is an error response, don't modify
            if (tokenResponse.containsKey("error")) {
                logger.debug("[TOKEN-ENHANCER] Skipping enhancement - error response");
                return originalJson;
            }

            // If access_token exists, decode its payload and look for claims
            Object accessTokenObj = tokenResponse.get("access_token");
            if (accessTokenObj instanceof String) {
                String accessToken = (String) accessTokenObj;
                if (accessToken.contains(".")) {
                    String[] parts = accessToken.split("\\.");
                    if (parts.length >= 2) {
                        String payloadJson = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
                        @SuppressWarnings("unchecked")
                        Map<String, Object> claims = mapper.readValue(payloadJson, Map.class);

                        logger.info("🔍 [TOKEN-ENHANCER] JWT claims: patient={}, fhirUser={}", 
                                   claims.get("patient"), claims.get("fhirUser"));

                        boolean modified = false;
                        
                        // Only add patient/fhirUser if not already present at top-level
                        if (!tokenResponse.containsKey("patient") && claims.containsKey("patient")) {
                            tokenResponse.put("patient", claims.get("patient"));
                            logger.info("✅ [TOKEN-ENHANCER] Added 'patient' to token response: {}", claims.get("patient"));
                            modified = true;
                        } else if (!tokenResponse.containsKey("patient")) {
                            logger.warn("⚠️ [TOKEN-ENHANCER] No 'patient' claim in JWT!");
                        }
                        
                        if (!tokenResponse.containsKey("fhirUser") && claims.containsKey("fhirUser")) {
                            tokenResponse.put("fhirUser", claims.get("fhirUser"));
                            logger.info("✅ [TOKEN-ENHANCER] Added 'fhirUser' to token response: {}", claims.get("fhirUser"));
                            modified = true;
                        }
                        
                        if (modified) {
                            logger.info("🎫 [TOKEN-ENHANCER] Enhanced token response");
                        }
                    }
                }
            }

            // Return modified JSON
            return mapper.writeValueAsString(tokenResponse);
        } catch (Exception e) {
            // On any error, return original unchanged
            logger.error("❌ [TOKEN-ENHANCER] Error enhancing token response: {}", e.getMessage(), e);
            return originalJson;
        }
    }
}
