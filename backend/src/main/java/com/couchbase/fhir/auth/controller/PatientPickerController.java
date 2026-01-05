package com.couchbase.fhir.auth.controller;

import com.couchbase.fhir.auth.service.PatientPickerService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.endpoint.OAuth2ParameterNames;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import java.security.Principal;
import java.util.List;

/**
 * Patient Picker Controller for SMART on FHIR Provider Apps
 * 
 * Handles patient selection during OAuth authorization flow for standalone launch.
 * Only practitioners can access this - patients have their context automatically set.
 * 
 * Flow:
 * 1. User authenticates (login page)
 * 2. For provider apps requesting launch/patient scope: redirect to /patient-picker
 * 3. Provider selects a patient from the list
 * 4. Selected patient_id is stored in OAuth2Authorization
 * 5. Redirect to /consent with patient context
 */
@Controller
@ConditionalOnProperty(name = "app.security.use-keycloak", havingValue = "false", matchIfMissing = true)
public class PatientPickerController {
    
    private static final Logger logger = LoggerFactory.getLogger(PatientPickerController.class);
    
    private final PatientPickerService patientPickerService;
    
    public PatientPickerController(PatientPickerService patientPickerService) {
        this.patientPickerService = patientPickerService;
    }
    
    /**
     * Show patient picker page
     * GET /patient-picker - Display list of patients for provider to choose
     */
    @GetMapping("/patient-picker")
    public String showPatientPicker(
            Principal principal,
            Model model,
            HttpServletResponse response,
            HttpSession session,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
            @RequestParam(OAuth2ParameterNames.STATE) String state,
            @RequestParam(value = OAuth2ParameterNames.REDIRECT_URI, required = false) String redirectUri,
            @RequestParam(value = OAuth2ParameterNames.RESPONSE_TYPE, required = false, defaultValue = "code") String responseType,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod,
            @RequestParam(value = "searchTerm", required = false) String searchTerm) {
        
        logger.info("🚀 [PATIENT-PICKER] GET /patient-picker called - client: {}, user: {}", clientId, principal.getName());
        
        // Prevent browser caching
        response.setHeader("Cache-Control", "no-store, no-cache, must-revalidate, max-age=0");
        response.setHeader("Pragma", "no-cache");
        response.setHeader("Expires", "0");
        
        logger.info("🏥 Patient picker requested for client: {} by user: {}", clientId, principal.getName());
        
        // Validate user role - only practitioners can use patient picker
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!hasRole(authentication, "practitioner")) {
            logger.warn("⚠️ User {} with non-practitioner role attempted to access patient picker", principal.getName());
            model.addAttribute("error", "Only practitioners can select patients for provider applications");
            return "error";
        }
        
        // Search patients (default: first 10 if no search term)
        List<PatientPickerService.PatientSummary> patients = 
            patientPickerService.searchPatients(searchTerm, 10);
        
        // Add model attributes for the page
        model.addAttribute("principalName", principal.getName());
        model.addAttribute("patients", patients);
        model.addAttribute("searchTerm", searchTerm != null ? searchTerm : "");
        model.addAttribute("client_id", clientId);
        model.addAttribute("scope", scope);
        model.addAttribute("state", state);
        model.addAttribute("redirect_uri", redirectUri);
        model.addAttribute("response_type", responseType);
        model.addAttribute("code_challenge", codeChallenge);
        model.addAttribute("code_challenge_method", codeChallengeMethod);
        
        logger.debug("📋 Displaying {} patients for selection", patients.size());
        return "patient-picker";
    }
    
    /**
     * Handle patient selection
     * POST /patient-picker - User selected a patient, store context and redirect to consent
     */
    @PostMapping("/patient-picker")
    public String selectPatient(
            Principal principal,
            HttpSession session,
            RedirectAttributes redirectAttributes,
            @RequestParam(value = "patient_id", required = false) String patientId,
            @RequestParam(value = "action", required = false, defaultValue = "select") String action,
            @RequestParam(OAuth2ParameterNames.CLIENT_ID) String clientId,
            @RequestParam(OAuth2ParameterNames.SCOPE) String scope,
            @RequestParam(OAuth2ParameterNames.STATE) String state,
            @RequestParam(value = OAuth2ParameterNames.REDIRECT_URI, required = false) String redirectUri,
            @RequestParam(value = OAuth2ParameterNames.RESPONSE_TYPE, required = false, defaultValue = "code") String responseType,
            @RequestParam(value = "code_challenge", required = false) String codeChallenge,
            @RequestParam(value = "code_challenge_method", required = false) String codeChallengeMethod) {
        
        logger.info("🔐 Patient selection submitted by {}: patient={}, action={}", 
                   principal.getName(), patientId, action);
        
        // Handle cancel action
        if ("cancel".equals(action)) {
            logger.info("❌ Patient selection cancelled by user");
            // Redirect back to client with error
            return "redirect:" + redirectUri + "?error=access_denied&state=" + state;
        }
        
        // Validate patient_id was provided
        if (patientId == null || patientId.trim().isEmpty()) {
            logger.warn("⚠️ No patient selected");
            redirectAttributes.addFlashAttribute("error", "Please select a patient");
            redirectAttributes.addAttribute("client_id", clientId);
            redirectAttributes.addAttribute("scope", scope);
            redirectAttributes.addAttribute("state", state);
            redirectAttributes.addAttribute("redirect_uri", redirectUri);
            redirectAttributes.addAttribute("response_type", responseType);
            redirectAttributes.addAttribute("code_challenge", codeChallenge);
            redirectAttributes.addAttribute("code_challenge_method", codeChallengeMethod);
            return "redirect:/patient-picker";
        }
        
        // Validate patient exists
        PatientPickerService.PatientSummary patient = patientPickerService.getPatientById(patientId);
        if (patient == null) {
            logger.error("❌ Invalid patient ID: {}", patientId);
            redirectAttributes.addFlashAttribute("error", "Invalid patient selected");
            redirectAttributes.addAttribute("client_id", clientId);
            redirectAttributes.addAttribute("scope", scope);
            redirectAttributes.addAttribute("state", state);
            redirectAttributes.addAttribute("redirect_uri", redirectUri);
            redirectAttributes.addAttribute("response_type", responseType);
            redirectAttributes.addAttribute("code_challenge", codeChallenge);
            redirectAttributes.addAttribute("code_challenge_method", codeChallengeMethod);
            return "redirect:/patient-picker";
        }
        
        // Store patient_id in session for the consent/authorization flow
        // The OAuth2Authorization will be created with this context
        session.setAttribute("selected_patient_id", patientId);
        logger.info("✅ Patient context stored in session: {}", patientId);
        
        // Redirect back to Authorization Endpoint so SAS can attach consent state
        StringBuilder authorizeUrl = new StringBuilder("/oauth2/authorize");
        authorizeUrl.append("?response_type=").append(responseType);
        authorizeUrl.append("&client_id=").append(clientId);
        if (redirectUri != null) {
            authorizeUrl.append("&redirect_uri=").append(redirectUri);
        }
        authorizeUrl.append("&scope=").append(scope);
        authorizeUrl.append("&state=").append(state);
        // Include patient context explicitly so SAS can capture it in additionalParameters
        authorizeUrl.append("&patient_id=").append(patientId);
        if (codeChallenge != null) {
            authorizeUrl.append("&code_challenge=").append(codeChallenge);
        }
        if (codeChallengeMethod != null) {
            authorizeUrl.append("&code_challenge_method=").append(codeChallengeMethod);
        }
        
        logger.info("➡️ Redirecting to authorization endpoint with patient context: {}", patientId);
        return "redirect:" + authorizeUrl.toString();
    }
    
    /**
     * Check if the authenticated user has a specific role
     */
    private boolean hasRole(Authentication authentication, String role) {
        if (authentication == null) {
            return false;
        }
        
        return authentication.getAuthorities().stream()
                .anyMatch(grantedAuthority -> 
                    grantedAuthority.getAuthority().equals("ROLE_" + role.toUpperCase()) ||
                    grantedAuthority.getAuthority().equalsIgnoreCase(role));
    }
}

