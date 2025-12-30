package com.couchbase.fhir.auth.controller;

import com.couchbase.fhir.auth.SmartScopes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import com.couchbase.admin.auth.controller.AuthController;

/**
 * SMART on FHIR Configuration Endpoint
 *
 * Merged controller: supports both the embedded Spring Authorization Server
 * and an external Keycloak instance depending on `app.security.use-keycloak`.
 */
@RestController
public class SmartConfigurationController {

    private static final Logger logger = LoggerFactory.getLogger(SmartConfigurationController.class);

    @Value("${app.security.use-keycloak:false}")
    private boolean useKeycloak;

    @Value("${KEYCLOAK_URL:}")
    private String keycloakUrl;

    @Value("${KEYCLOAK_REALM:}")
    private String keycloakRealm;

    @Value("${KEYCLOAK_JWKS_URI:}")
    private String keycloakJwksUri;

    @Value("${KEYCLOAK_PUBLIC_URL:}")
    private String keycloakPublicUrl;

    @Value("${KEYCLOAK_TOKEN_PROXY_URL:}")
    private String keycloakTokenProxyUrl;

    @Value("${app.baseUrl:}")
    private String appBaseUrl;

    @GetMapping(value = {"/.well-known/smart-configuration", "/fhir/.well-known/smart-configuration"}, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> getSmartConfiguration(HttpServletRequest req) {
        logger.debug("🔍 SMART configuration requested");

        // Determine base URL: prefer system property (from config.yaml), then environment (Docker), then configured app.baseUrl, then derive from request
        String baseUrl = System.getProperty("app.baseUrl", "");
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = System.getenv("APP_BASE_URL");
        }
        if ((baseUrl == null || baseUrl.isEmpty()) && appBaseUrl != null && !appBaseUrl.isBlank()) {
            baseUrl = appBaseUrl;
        }
        if (baseUrl == null || baseUrl.isEmpty()) {
            baseUrl = deriveBaseUrl(req);
        }

        Map<String, Object> config = new LinkedHashMap<>();

        if (useKeycloak) {
            // If Keycloak is fully configured, return Keycloak endpoints; otherwise fall back to jwks or base issuer
            if (keycloakUrl == null || keycloakUrl.isBlank() || keycloakRealm == null || keycloakRealm.isBlank()) {
                if (keycloakJwksUri != null && !keycloakJwksUri.isBlank()) {
                    config.put("jwks_uri", keycloakJwksUri);
                }
                config.put("issuer", baseUrl);
            } else {
                // Determine the public-facing base for Keycloak endpoints. Prefer explicit public URL
                // (KEYCLOAK_PUBLIC_URL). If not provided, derive from appBaseUrl or the request and append
                // the HAProxy-mounted prefix `/auth` so advertised endpoints are reachable externally.
                String publicBase = null;
                if (keycloakPublicUrl != null && !keycloakPublicUrl.isBlank()) {
                    publicBase = keycloakPublicUrl;
                } else {
                    String base = (appBaseUrl != null && !appBaseUrl.isBlank()) ? appBaseUrl : deriveBaseUrl(req);
                    // If base includes a /fhir suffix, remove it before appending /auth
                    if (base != null && base.endsWith("/fhir")) {
                        base = base.substring(0, base.length() - 5);
                    }
                    publicBase = base;
                }

                if (publicBase == null) publicBase = "";

                if(keycloakJwksUri.contains("keycloak:8080")) {
                    keycloakJwksUri = keycloakJwksUri.replace("keycloak:8080", "localhost");
                    logger.debug(" as -> "+keycloakJwksUri);
                }
                // Trim trailing slash
                if (publicBase.endsWith("/")) publicBase = publicBase.substring(0, publicBase.length() - 1);

                String issuer = publicBase + "/auth/realms/" + AuthController.stripQuotes(keycloakRealm);
                config.put("issuer", issuer);
                config.put("authorization_endpoint", issuer + "/protocol/openid-connect/auth");
                if (keycloakTokenProxyUrl != null && !keycloakTokenProxyUrl.isBlank()) {
                    config.put("token_endpoint", keycloakTokenProxyUrl);
                } else {
                    config.put("token_endpoint", issuer + "/protocol/openid-connect/token");
                }
                config.put("introspection_endpoint", issuer + "/protocol/openid-connect/token/introspect");
                config.put("revocation_endpoint", issuer + "/protocol/openid-connect/revoke");
                config.put("jwks_uri", (keycloakJwksUri != null && !keycloakJwksUri.isBlank()) ? keycloakJwksUri : issuer + "/protocol/openid-connect/certs");
                config.put("registration_endpoint", issuer + "/clients-registrations/openid-connect");
            }
        } else {
            // Embedded Spring Authorization Server endpoints
            String issuer = baseUrl;
            if (issuer.endsWith("/fhir")) {
                issuer = issuer.substring(0, issuer.length() - 5);
            }
            config.put("issuer", issuer);
            config.put("authorization_endpoint", issuer + "/oauth2/authorize");
            config.put("token_endpoint", issuer + "/oauth2/token");
            config.put("jwks_uri", issuer + "/oauth2/jwks");
            config.put("registration_endpoint", issuer + "/oauth2/register");
        }

        // Common capabilities and supported values (SMART on FHIR scopes + capabilities)
        config.put("scopes_supported", Arrays.asList(
            "openid",
            "profile",
            "fhirUser",
            "launch",
            "launch/patient",
            "online_access",
            "offline_access",
            SmartScopes.PATIENT_ALL_READ,
            SmartScopes.PATIENT_ALL_WRITE,
            SmartScopes.PATIENT_ALL,
            "patient/Patient.read",
            "patient/Patient.write",
            "patient/Observation.read",
            "patient/Observation.write",
            "patient/Condition.read",
            "patient/Condition.write",
            "patient/MedicationRequest.read",
            "patient/MedicationRequest.write",
            "patient/AllergyIntolerance.read",
            "patient/Encounter.read",
            "patient/Procedure.read",
            "patient/DiagnosticReport.read",
            "patient/Immunization.read",
            "patient/DocumentReference.read",
            "patient/Device.rs",
            "patient/MedicationRequest.rs",
            "patient/DiagnosticReport.rs",
            "patient/Goal.rs",
            "patient/Practitioner.rs",
            "patient/Provenance.rs",
            "patient/Organization.rs",
            "patient/Encounter.rs",
            "patient/DocumentReference.rs",
            "patient/Immunization.rs",
            "patient/PractitionerRole.rs",
            "patient/Observation.rs",
            "patient/Patient.rs",
            "patient/CareTeam.rs",
            "patient/CarePlan.rs",
            "patient/Procedure.rs",
            "patient/AllergyIntolerance.rs",
            "patient/Location.rs",
            "patient/Condition.rs",
            "patient/Medication.rs",
            "patient/ServiceRequest.rs",
            "patient/Coverage.rs",
            "patient/MedicationDispense.rs",
            "patient/Specimen.rs",
            SmartScopes.USER_ALL_READ,
            SmartScopes.USER_ALL_WRITE,
            SmartScopes.USER_ALL,
            "user/Patient.read",
            "user/Patient.write",
            "user/Observation.read",
            "user/Observation.write",
            "user/Condition.read",
            "user/Condition.write",
            "user/MedicationRequest.read",
            "user/MedicationRequest.write",
            SmartScopes.SYSTEM_ALL_READ,
            SmartScopes.SYSTEM_ALL_WRITE,
            SmartScopes.SYSTEM_ALL
        ));

        config.put("response_types_supported", Arrays.asList("code"));
        config.put("grant_types_supported", Arrays.asList("authorization_code", "client_credentials", "refresh_token"));
        config.put("code_challenge_methods_supported", Arrays.asList("S256"));

        config.put("capabilities", Arrays.asList(
            "launch-ehr",
            "launch-standalone",
            "client-public",
            "client-confidential-symmetric",
            "client-confidential-asymmetric",
            "authorize-post",
            "sso-openid-connect",
            "context-ehr-patient",
            "context-ehr-encounter",
            "context-standalone-patient",
            "permission-offline",
            "permission-patient",
            "permission-user",
            "permission-v1",
            "permission-v2"
        ));

        logger.info("✅ Returned SMART configuration (useKeycloak: {}, issuer: {})", useKeycloak, config.get("issuer"));

        return ResponseEntity.ok(config);
    }

    private String deriveBaseUrl(HttpServletRequest req) {
        String scheme = req.getScheme();
        String host = req.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) host = req.getServerName();
        int port = req.getServerPort();
        String forwardedProto = req.getHeader("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isBlank()) scheme = forwardedProto;
        boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80) || ("https".equalsIgnoreCase(scheme) && port == 443);
        return host + (defaultPort ? "" : (":" + port));
    }
}

