package com.couchbase.fhir.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Validates SMART on FHIR scopes against requested FHIR resource operations
 * 
 * This validator checks if the OAuth 2.0 access token contains the necessary
 * SMART scopes to perform operations on FHIR resources.
 */
@Component
public class SmartScopeValidator {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartScopeValidator.class);
    
    /**
     * Validate if the authentication has permission for the requested resource operation
     * 
     * @param authentication Spring Security authentication (should contain JWT)
     * @param resourceType FHIR resource type (e.g., "Patient", "Observation")
     * @param operation Operation type ("read", "write", or "*")
     * @return true if authorized, false otherwise
     */
    public boolean hasPermission(Authentication authentication, String resourceType, String operation) {
        if (authentication == null || !authentication.isAuthenticated()) {
            logger.warn("Authentication is null or not authenticated");
            return false;
        }
        
        // Extract scopes from JWT
        List<String> scopes = extractScopes(authentication);
        logger.debug("🔍 [SCOPE-VALIDATOR] Checking permission for {}.{} with scopes: {}", resourceType, operation, scopes);
        
        if (scopes == null || scopes.isEmpty()) {
            // No SMART scopes found — deny
            logger.warn("No SMART scopes found in authentication for {}.{}, denying access", resourceType, operation);
            return false;
        }
        
        // Parse scopes and check for permission, with diagnostics of matched scopes
        List<SmartScopes.SmartScope> parsedScopes = scopes.stream()
            .map(SmartScopes::parse)
            .filter(scope -> scope != null)
            .collect(Collectors.toList());

        boolean hasPermission = parsedScopes.stream()
            .anyMatch(scope -> scope.matches(resourceType, operation));

        if (!parsedScopes.isEmpty()) {
            List<String> matched = parsedScopes.stream()
                .filter(scope -> scope.matches(resourceType, operation))
                .map(SmartScopes.SmartScope::getOriginalScope)
                .collect(Collectors.toList());
            logger.debug("🔍 [SCOPE-VALIDATOR] Matched scopes for {}.{}: {}", resourceType, operation, matched);
        }
        
        logger.debug("🔍 [SCOPE-VALIDATOR] Scope matching result for {}.{}: {}", resourceType, operation, hasPermission);
        
        if (!hasPermission) {
            logger.warn("Access denied: No matching scope or role found for resource: {}, operation: {}", 
                       resourceType, operation);
            logger.debug("Available scopes: {}", scopes);
            return false;
        } else {
            logger.debug("✅ [SCOPE-VALIDATOR] Access granted for {}.{} with matching SMART scope", resourceType, operation);
            return true;
        }
    }
    
    /**
     * Check if has read permission for a resource type
     */
    public boolean hasReadPermission(Authentication authentication, String resourceType) {
        return hasPermission(authentication, resourceType, "read");
    }
    
    /**
     * Check if has write permission for a resource type
     */
    public boolean hasWritePermission(Authentication authentication, String resourceType) {
        return hasPermission(authentication, resourceType, "write");
    }
    
    /**
     * Extract patient ID from JWT token if present
     * Used for patient-scoped queries
     */
    public String getPatientContext(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken) {
            Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
            Object patientClaim = jwt.getClaim("patient");
            return patientClaim != null ? patientClaim.toString() : null;
        }
        return null;
    }
    
    /**
     * Extract fhirUser claim from JWT token
     * Typically identifies the authenticated practitioner/user
     */
    public String getFhirUser(Authentication authentication) {
        if (authentication instanceof JwtAuthenticationToken) {
            Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
            Object fhirUserClaim = jwt.getClaim("fhirUser");
            return fhirUserClaim != null ? fhirUserClaim.toString() : null;
        }
        return null;
    }
    
    /**
     * Check if the token contains patient-scoped access
     */
    public boolean hasPatientScope(Authentication authentication) {
        List<String> scopes = extractScopes(authentication);
        return scopes != null && scopes.stream().anyMatch(s -> s.startsWith("patient/"));
    }
    
    /**
     * Check if the token contains user-scoped access
     */
    public boolean hasUserScope(Authentication authentication) {
        List<String> scopes = extractScopes(authentication);
        return scopes != null && scopes.stream().anyMatch(s -> s.startsWith("user/"));
    }
    
    /**
     * Check if the token contains system-scoped access
     */
    public boolean hasSystemScope(Authentication authentication) {
        List<String> scopes = extractScopes(authentication);
        return scopes != null && scopes.stream().anyMatch(s -> s.startsWith("system/"));
    }
    
    /**
     * Extract scopes from JWT authentication
     */
    private List<String> extractScopes(Authentication authentication) {
        logger.debug("🔍 [SCOPE-EXTRACT] Extracting scopes from authentication: {}", authentication.getClass().getSimpleName());
        
        // First, try to extract from Spring Security authorities (for API tokens)
        // API tokens set authorities with "SCOPE_" prefix
        if (authentication.getAuthorities() != null && !authentication.getAuthorities().isEmpty()) {
            logger.debug("🔍 [SCOPE-EXTRACT] Found {} authorities", authentication.getAuthorities().size());
            
            List<String> scopesFromAuthorities = authentication.getAuthorities().stream()
                .map(authority -> {
                    String auth = authority.getAuthority();
                    logger.debug("🔍 [SCOPE-EXTRACT] Authority: {} (type: {})", auth, authority.getClass().getSimpleName());
                    return auth;
                })
                .filter(auth -> auth.startsWith("SCOPE_"))
                .map(auth -> auth.substring(6)) // Remove "SCOPE_" prefix
                .collect(Collectors.toList());
            
            if (!scopesFromAuthorities.isEmpty()) {
                logger.debug("✅ [SCOPE-EXTRACT] Extracted scopes from authorities: {}", scopesFromAuthorities);
                return scopesFromAuthorities;
            }
        }
        
        // Fallback: Extract from JWT claims (for OAuth2 tokens)
        if (authentication instanceof JwtAuthenticationToken) {
            Jwt jwt = ((JwtAuthenticationToken) authentication).getToken();
            logger.debug("🔍 [SCOPE-EXTRACT] JWT Authentication detected, checking claims");
            
            // Try to get scopes from "scope" claim (space-separated string)
            Object scopeClaim = jwt.getClaim("scope");
            logger.debug("🔍 [SCOPE-EXTRACT] scope claim: {} (type: {})", 
                scopeClaim, scopeClaim != null ? scopeClaim.getClass().getName() : "null");
            
            if (scopeClaim instanceof String) {
                String[] scopes = ((String) scopeClaim).split(" ");
                logger.debug("✅ [SCOPE-EXTRACT] Extracted scopes from String claim: {}", List.of(scopes));
                return List.of(scopes);
            } else if (scopeClaim instanceof Collection) {
                List<String> scopes = ((Collection<?>) scopeClaim).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
                logger.debug("✅ [SCOPE-EXTRACT] Extracted scopes from Collection claim: {}", scopes);
                return scopes;
            }
            
            // Try to get from "scp" claim (alternative claim name)
            Object scpClaim = jwt.getClaim("scp");
            if (scpClaim != null) {
                logger.debug("🔍 [SCOPE-EXTRACT] scp claim: {} (type: {})", 
                    scpClaim, scpClaim.getClass().getName());
            }
            if (scpClaim instanceof Collection) {
                List<String> scopes = ((Collection<?>) scpClaim).stream()
                    .map(Object::toString)
                    .collect(Collectors.toList());
                logger.debug("✅ [SCOPE-EXTRACT] Extracted scopes from scp Collection: {}", scopes);
                return scopes;
            }
        }
        
        logger.warn("⚠️ [SCOPE-EXTRACT] No scopes found in authentication");
        return List.of();
    }
}

