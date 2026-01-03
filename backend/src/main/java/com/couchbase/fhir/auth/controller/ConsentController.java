package com.couchbase.fhir.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.web.savedrequest.SavedRequest;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.security.Principal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * OAuth 2.0 Consent Controller
 * 
 * Handles the consent screen where users approve or deny access to their data.
 * Required for SMART on FHIR authorization flow.
 */
@Controller
@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "false", matchIfMissing = true)
public class ConsentController {
    
    private static final Logger logger = LoggerFactory.getLogger(ConsentController.class);
    
    private final RegisteredClientRepository clientRepository;
    
    public ConsentController(RegisteredClientRepository clientRepository) {
        this.clientRepository = clientRepository;
    }
    
    /**
     * Show consent page
     * GET /consent - Custom consent page for SMART on FHIR authorization
     */
    @GetMapping("/consent")
    public String consent(
            Principal principal,
            Model model,
            HttpServletRequest request,
            HttpServletResponse response,
            HttpSession session,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
            @RequestParam(OAuth2ParameterNames.STATE) String state,
            @RequestParam(value = OAuth2ParameterNames.REDIRECT_URI, required = false) String redirectUri,
            @RequestParam(value = OAuth2ParameterNames.RESPONSE_TYPE, required = false, defaultValue = "code") String responseType,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "patient_id", required = false) String patientIdParam) {
        
        // Prevent browser from caching the consent page HTML
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        logger.info("🔐 Consent requested for client: {} by user: {}", clientId, principal.getName());
        
        // Recover missing parameters from SavedRequest (the original /oauth2/authorize request)
        // IMPORTANT: Don't use getRequest() as it removes the SavedRequest from session!
        // We need to keep it for Spring Authorization Server to process the POST
        // NOTE: DO NOT override state - use the one from URL as it's for the current authorization attempt
        SavedRequest savedRequest = (SavedRequest) session.getAttribute("SPRING_SECURITY_SAVED_REQUEST");
        if (savedRequest != null) {
            logger.debug("🔍 [CONSENT] Found SavedRequest, recovering missing OAuth parameters");
            
            // Recover redirect_uri if not in query params
            if (redirectUri == null || redirectUri.isBlank()) {
                String[] redirectValues = savedRequest.getParameterValues(OAuth2ParameterNames.REDIRECT_URI);
                if (redirectValues != null && redirectValues.length > 0) {
                    redirectUri = redirectValues[0];
                    logger.debug("✅ [CONSENT] Recovered redirect_uri from SavedRequest: {}", redirectUri);
                }
            }
            
            // Recover code_challenge if not in query params (critical for PKCE!)
            if (codeChallenge == null || codeChallenge.isBlank()) {
                String[] challengeValues = savedRequest.getParameterValues("code_challenge");
                if (challengeValues != null && challengeValues.length > 0) {
                    codeChallenge = challengeValues[0];
                    logger.debug("✅ [CONSENT] Recovered code_challenge from SavedRequest");
                }
            }
            
            // Recover code_challenge_method if not in query params
            if (codeChallengeMethod == null || codeChallengeMethod.isBlank()) {
                String[] methodValues = savedRequest.getParameterValues("code_challenge_method");
                if (methodValues != null && methodValues.length > 0) {
                    codeChallengeMethod = methodValues[0];
                    logger.debug("✅ [CONSENT] Recovered code_challenge_method from SavedRequest: {}", codeChallengeMethod);
                }
            }
        }
        
        logger.debug("🔍 [CONSENT] OAuth params: state={}, redirect_uri={}, response_type={}, code_challenge={}", 
                    state, redirectUri, responseType, (codeChallenge != null ? "[present]" : "null"));
        
        // Get patient_id from request parameter (passed from patient picker) or session
        String patientId = patientIdParam;
        if (patientId == null) {
            Object sessionPatientId = session.getAttribute("selected_patient_id");
            if (sessionPatientId != null) {
                patientId = sessionPatientId.toString();
                logger.debug("🏥 [CONSENT] Found patient_id in session: {}", patientId);
            }
        } else {
            logger.debug("🏥 [CONSENT] Found patient_id in request parameter: {}", patientId);
        }
        
        if (patientId != null) {
            logger.info("🏥 [CONSENT] Patient context for consent: {}", patientId);
        }
        
        // Get client details
        RegisteredClient client = clientRepository.findByClientId(clientId);
        if (client == null) {
            logger.error("❌ Client not found: {}", clientId);
            model.addAttribute("error", "Invalid client");
            return "error";
        }
        
        // Parse requested scopes
        Set<String> requestedScopes = Arrays.stream(scope.split(" "))
                .collect(Collectors.toSet());
        
        // Build scope list with descriptions
        List<Map<String, String>> scopeList = new ArrayList<>();
        for (String scopeName : requestedScopes) {
            Map<String, String> scopeInfo = new HashMap<>();
            scopeInfo.put("scope", scopeName);
            scopeInfo.put("description", getScopeDescription(scopeName));
            scopeList.add(scopeInfo);
        }
        
        // Add model attributes for the consent page
        model.addAttribute("principalName", principal.getName());
        model.addAttribute("clientName", client.getClientName() != null ? client.getClientName() : clientId);
        model.addAttribute("clientUri", client.getClientSettings().getTokenEndpointAuthenticationSigningAlgorithm());
        model.addAttribute("scopes", scopeList);
        model.addAttribute("scopeString", scope);
        model.addAttribute("client_id", clientId);
        model.addAttribute("state", state);
        model.addAttribute("redirect_uri", redirectUri);
        model.addAttribute("response_type", responseType);
        model.addAttribute("code_challenge", codeChallenge);
        model.addAttribute("code_challenge_method", codeChallengeMethod);
        model.addAttribute("patient_id", patientId);  // Pass patient context to consent form
        
        logger.debug("🔍 [CONSENT] Model attributes: state={}, patient_id={}, scopes={}", 
                    state, patientId, scopeList.size());
        
        return "consent";
    }
    
    /**
     * Get human-readable description for a SMART scope
     */
    private String getScopeDescription(String scope) {
        // SMART on FHIR scope descriptions
        Map<String, String> descriptions = new HashMap<>();
        descriptions.put("openid", "Verify your identity");
        descriptions.put("profile", "Access your profile information");
        descriptions.put("fhirUser", "Know which user you are");
        descriptions.put("launch/patient", "Know which patient record to access");
        descriptions.put("offline_access", "Access your data when you're not using the app");
        descriptions.put("online_access", "Access your data only when you're using the app");
        descriptions.put("patient/*.rs", "Read and search all your health data");
        descriptions.put("patient/*.cud", "Create, update, and delete your health data");
        descriptions.put("patient/*.cruds", "Full access to your health data");
        // Legacy v1 format support
        descriptions.put("patient/*.read", "Read all your health data");
        descriptions.put("patient/*.write", "Create and update your health data");
        descriptions.put("patient/*.*", "Full access to your health data");
        descriptions.put("user/*.read", "Read health data on your behalf");
        descriptions.put("user/*.write", "Create and update health data on your behalf");
        descriptions.put("user/*.*", "Full access to health data on your behalf");
        
        // US Core resource-specific scopes
        if (scope.startsWith("patient/")) {
            String resource = scope.substring(8).split("\\.")[0];
            if (scope.endsWith(".read") || scope.endsWith(".rs")) {
                return "Read your " + formatResourceName(resource) + " data";
            } else if (scope.endsWith(".write")) {
                return "Create and update your " + formatResourceName(resource) + " data";
            }
        }
        
        return descriptions.getOrDefault(scope, "Access: " + scope);
    }
    
    /**
     * Format FHIR resource names for display
     */
    private String formatResourceName(String resource) {
        return switch (resource) {
            case "AllergyIntolerance" -> "allergies";
            case "MedicationRequest" -> "medications";
            case "DiagnosticReport" -> "lab results";
            case "DocumentReference" -> "documents";
            case "Immunization" -> "immunizations";
            case "Observation" -> "observations";
            case "Condition" -> "conditions";
            case "Procedure" -> "procedures";
            case "Encounter" -> "encounters";
            case "Patient" -> "patient information";
            case "Practitioner" -> "practitioner information";
            case "Organization" -> "organization information";
            default -> resource.toLowerCase();
        };
    }
}

