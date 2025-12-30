package com.couchbase.fhir.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.List;

/**
 * SMART on FHIR scope definitions
 * Reference: http://hl7.org/fhir/smart-app-launch/scopes-and-launch-context.html
 * 
 * SMART v2 Format: {category}/{resource-type}.{interactions}
 * 
 * Categories:
 * - patient: Access limited to patient's compartment
 * - user: Access by authenticated user (practitioner, etc.)
 * - system: Backend service access (no user context)
 * 
 * SMART v2 Interactions (granular):
 * - c = create
 * - r = read
 * - u = update
 * - d = delete
 * - s = search
 * - Combinations: rs (read+search), cud (create+update+delete), cruds (all)
 * - Or * for all interactions
 */
public class SmartScopes {
    
    private static final Logger logger = LoggerFactory.getLogger(SmartScopes.class);
    
    // Launch contexts
    public static final String LAUNCH = "launch";
    public static final String LAUNCH_PATIENT = "launch/patient";
    public static final String ONLINE_ACCESS = "online_access";
    public static final String OFFLINE_ACCESS = "offline_access";
    public static final String OPENID = "openid";
    public static final String FHIRUSER = "fhirUser";
    public static final String PROFILE = "profile";
    
    // Patient scopes - SMART v2 format (access limited to patient compartment)
    public static final String PATIENT_ALL_READ = "patient/*.rs";      // read + search (v2)
    public static final String PATIENT_ALL_WRITE = "patient/*.cud";    // create + update + delete (v2)
    public static final String PATIENT_ALL = "patient/*.cruds";        // all operations (v2)
    
    // Common patient resource scopes
    public static final String PATIENT_PATIENT_READ = "patient/Patient.read";
    public static final String PATIENT_OBSERVATION_READ = "patient/Observation.read";
    public static final String PATIENT_CONDITION_READ = "patient/Condition.read";
    public static final String PATIENT_MEDICATION_READ = "patient/MedicationRequest.read";
    public static final String PATIENT_ALLERGY_READ = "patient/AllergyIntolerance.read";
    
    // User scopes - SMART v2 format (access by authenticated user)
    public static final String USER_ALL_READ = "user/*.rs";        // read + search (v2)
    public static final String USER_ALL_WRITE = "user/*.cud";      // create + update + delete (v2)
    public static final String USER_ALL = "user/*.cruds";          // all operations (v2)
    
    // Common user resource scopes
    public static final String USER_PATIENT_READ = "user/Patient.read";
    public static final String USER_PATIENT_WRITE = "user/Patient.write";
    public static final String USER_OBSERVATION_READ = "user/Observation.read";
    public static final String USER_OBSERVATION_WRITE = "user/Observation.write";
    
    // System scopes - SMART v2 format (backend service access)
    public static final String SYSTEM_ALL_READ = "system/*.rs";    // read + search (v2)
    public static final String SYSTEM_ALL_WRITE = "system/*.cud";  // create + update + delete (v2)
    public static final String SYSTEM_ALL = "system/*.cruds";      // all operations (v2)
    
    // All default scopes for testing
    public static final List<String> DEFAULT_TEST_SCOPES = Arrays.asList(
        OPENID,
        FHIRUSER,
        PROFILE,
        ONLINE_ACCESS,
        PATIENT_ALL_READ,
        USER_ALL_READ
    );
    
    /**
     * Parse a SMART scope into its components
     * @param scope SMART scope (e.g., "patient/Patient.read")
     * @return SmartScope object
     */
    public static SmartScope parse(String scope) {
        if (scope == null || scope.isEmpty()) {
            return null;
        }
        
        // Handle special scopes (launch, openid, etc.)
        if (!scope.contains("/")) {
            return new SmartScope(scope, null, null, null);
        }
        
        String[] parts = scope.split("/");
        if (parts.length != 2) {
            return null;
        }
        
        String category = parts[0]; // patient, user, system
        String resourceAndAction = parts[1];
        
        String[] resourceParts = resourceAndAction.split("\\.");
        if (resourceParts.length != 2) {
            return null;
        }
        
        String resourceType = resourceParts[0]; // Patient, Observation, *
        String action = resourceParts[1]; // read, write, *
        
        return new SmartScope(scope, category, resourceType, action);
    }
    
    /**
     * Represents a parsed SMART scope
     */
    public static class SmartScope {
        private final String originalScope;
        private final String category;  // patient, user, system
        private final String resourceType; // Patient, Observation, *
        private final String action; // read, write, *
        
        public SmartScope(String originalScope, String category, String resourceType, String action) {
            this.originalScope = originalScope;
            this.category = category;
            this.resourceType = resourceType;
            this.action = action;
        }
        
        public String getOriginalScope() {
            return originalScope;
        }
        
        public String getCategory() {
            return category;
        }
        
        public String getResourceType() {
            return resourceType;
        }
        
        public String getAction() {
            return action;
        }
        
        public boolean isWildcardResource() {
            return "*".equals(resourceType);
        }
        
        public boolean isWildcardAction() {
            return "*".equals(action);
        }
        
        public boolean isPatientScope() {
            return "patient".equals(category);
        }
        
        public boolean isUserScope() {
            return "user".equals(category);
        }
        
        public boolean isSystemScope() {
            return "system".equals(category);
        }
        
        public boolean allowsRead() {
            // SMART v2 granular scopes: check if action contains 'r' (read) or 's' (search)
            // Examples: "read", "rs", "cruds", "*"
            return "read".equals(action) || 
                   "*".equals(action) || 
                   (action != null && (action.contains("r") || action.contains("s")));
        }
        
        public boolean allowsWrite() {
            // SMART v2 granular scopes: check if action contains 'c', 'u', or 'd'
            // Examples: "write", "cud", "cruds", "*"
            return "write".equals(action) || 
                   "*".equals(action) || 
                   (action != null && (action.contains("c") || action.contains("u") || action.contains("d")));
        }
        
        public boolean matches(String requestedResourceType, String requestedAction) {
            // Check resource type match (must match explicitly or be wildcard)
            boolean resourceMatches = isWildcardResource() || 
                                     (resourceType != null && requestedResourceType != null && resourceType.equalsIgnoreCase(requestedResourceType));
            
            logger.trace("[SCOPE-MATCH] Checking scope '{}' against resource={}, action={} - resourceMatches={}", 
                        originalScope, requestedResourceType, requestedAction, resourceMatches);
            
            if (!resourceMatches) {
                logger.trace("[SCOPE-MATCH] ❌ Resource mismatch: scope resource '{}' != requested '{}'", 
                           resourceType, requestedResourceType);
                return false; // If resource doesn't match, deny immediately
            }
            
            // Check action match
            if (isWildcardAction()) {
                logger.trace("[SCOPE-MATCH] ✅ Wildcard action '*' grants all permissions");
                return true; // Wildcard action grants all permissions
            }
            
            // SMART v2 granular action matching
            boolean matches = false;
            if ("read".equals(requestedAction)) {
                matches = allowsRead();
                logger.trace("[SCOPE-MATCH] Read permission check: scope action='{}', allowsRead={}", action, matches);
            } else if ("write".equals(requestedAction)) {
                matches = allowsWrite();
                logger.trace("[SCOPE-MATCH] Write permission check: scope action='{}', allowsWrite={}", action, matches);
            } else if ("*".equals(requestedAction)) {
                matches = allowsRead() || allowsWrite();
                logger.trace("[SCOPE-MATCH] Wildcard permission check: scope action='{}', allowsRead={}, allowsWrite={}", 
                           action, allowsRead(), allowsWrite());
            } else {
                // Exact match for other actions
                matches = action.equals(requestedAction);
                logger.trace("[SCOPE-MATCH] Exact match check: scope action='{}', requested='{}', matches={}", 
                           action, requestedAction, matches);
            }
            
            if (matches) {
                logger.debug("[SCOPE-MATCH] ✅ GRANTED: Scope '{}' allows {}.{}", 
                           originalScope, requestedResourceType, requestedAction);
            } else {
                logger.trace("[SCOPE-MATCH] ❌ DENIED: Scope '{}' does not allow {}.{}", 
                           originalScope, requestedResourceType, requestedAction);
            }
            
            return matches;
        }
        
        @Override
        public String toString() {
            return originalScope;
        }
    }
}

