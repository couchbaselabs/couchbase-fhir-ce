package com.couchbase.fhir.resources.service;

import com.couchbase.common.fhir.FhirMetaHelper;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
public class FhirAuditService {

    private static final Logger logger = LoggerFactory.getLogger(FhirAuditService.class);
    /**
     * Add comprehensive audit information to resource meta using centralized helper
     */
    public void addAuditInfoToMeta(IBaseResource resource, String userId, String operation , String versionId) {
        UserAuditInfo auditInfo = getCurrentUserAuditInfo();
        addAuditInfoToMeta(resource, auditInfo, operation ,versionId);
    }
    /**
     * Add comprehensive audit information to resource meta with detailed audit info using centralized helper
     */
    public void addAuditInfoToMeta(IBaseResource resource, UserAuditInfo auditInfo, String operation  , String versionId) {
        try {
            if (!(resource instanceof Resource)) {
                logger.warn("⚠️ Cannot add audit info to non-R4 resource: {}", resource.getClass().getSimpleName());
                return;
            }

            Resource r4Resource = (Resource) resource;

            // Determine user ID - prefer auditInfo, fallback to current user
            String userId = (auditInfo != null && auditInfo.getUserId() != null)
                    ? auditInfo.getUserId()
                    : getCurrentUserId();

            // Extract existing profiles to preserve them
            List<String> existingProfiles = FhirMetaHelper.extractExistingProfiles(r4Resource);

            // Use centralized helper to apply complete meta information
            FhirMetaHelper.applyCompleteMeta(
                    r4Resource,
                    userId,
                    operation,
                    new Date(),        // lastUpdated - always current time
                    versionId,              // versionId - simple for now
                    existingProfiles  // preserve existing profiles
            );

            logger.debug("✅ Applied audit meta to {} resource for user: {}, operation: {}",
                    r4Resource.getResourceType().name(), userId, operation);

        } catch (Exception e) {
            logger.error("❌ Failed to add audit info to resource: {}", e.getMessage(), e);
            // Don't throw - audit failure shouldn't break resource processing
        }
    }
    /**
     * Add minimal audit information to resource (preserves existing meta)
     */
    public void addMinimalAuditInfo(IBaseResource resource, String operation) {
        try {
            if (!(resource instanceof Resource)) {
                logger.warn("⚠️ Cannot add audit info to non-R4 resource: {}", resource.getClass().getSimpleName());
                return;
            }

            Resource r4Resource = (Resource) resource;
            String userId = getCurrentUserId();

            // Use audit-only method to preserve existing meta
            FhirMetaHelper.applyAuditOnly(r4Resource, userId, operation);

            logger.debug("✅ Applied minimal audit meta to {} resource for user: {}, operation: {}",
                    r4Resource.getResourceType().name(), userId, operation);

        } catch (Exception e) {
            logger.error("❌ Failed to add minimal audit info to resource: {}", e.getMessage(), e);
            // Don't throw - audit failure shouldn't break resource processing
        }
    }
    /**
     * Get current authenticated user from Spring Security context
     */
    public String getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return "anonymous";
        }

        // For different auth types:
        if (authentication instanceof OAuth2AuthenticationToken) {
            // OAuth2 - extract from attributes
            OAuth2AuthenticationToken oauth2Auth = (OAuth2AuthenticationToken) authentication;
            Object sub = oauth2Auth.getPrincipal().getAttribute("sub");
            Object preferredUsername = oauth2Auth.getPrincipal().getAttribute("preferred_username");
            Object userId = oauth2Auth.getPrincipal().getAttribute("user_id");
            // Use preferred order: user_id > preferred_username > sub > name
            if (userId != null) return userId.toString();
            if (preferredUsername != null) return preferredUsername.toString();
            if (sub != null) return sub.toString();
            return authentication.getName();
        } else {
            // Basic auth or other - use name
            return authentication.getName();
        }
    }
    /**
     * Get user details including roles/permissions
     */
    public UserAuditInfo getCurrentUserAuditInfo() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated()) {
            return new UserAuditInfo("anonymous", "system", Collections.emptySet());
        }

        String userId = getCurrentUserId();
        String userType = determineUserType(authentication);
        Set<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.toSet());

        UserAuditInfo auditInfo = new UserAuditInfo(userId, userType, roles);

        // Add additional context if available
        if (authentication instanceof OAuth2AuthenticationToken) {
            OAuth2AuthenticationToken oauth2Auth = (OAuth2AuthenticationToken) authentication;
            Object department = oauth2Auth.getPrincipal().getAttribute("department");
            Object sessionId = oauth2Auth.getPrincipal().getAttribute("session_id");

            if (department != null) auditInfo.setDepartment(department.toString());
            if (sessionId != null) auditInfo.setSessionId(sessionId.toString());
        }

        return auditInfo;
    }

    /**
     * Determine user type based on authentication
     */
    private String determineUserType(Authentication auth) {
        if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_SYSTEM"))) {
            return "system";
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"))) {
            return "administrator";
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_CLINICIAN"))) {
            return "clinician";
        } else if (auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_PATIENT"))) {
            return "patient";
        } else {
            return "user";
        }
    }
}