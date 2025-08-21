package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.Collection;
import com.couchbase.client.java.Bucket;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper service for common FHIR resource storage operations with audit metadata
 */
@Component
public class FhirResourceStorageHelper {

    private static final Logger log = LoggerFactory.getLogger(FhirResourceStorageHelper.class);
    private static final String DEFAULT_SCOPE = "Resources";

    @Autowired
    private FhirAuditService auditService;

    @Autowired
    private FhirValidator fhirValidator;  // Primary US Core validator

    @Autowired
    @Qualifier("basicFhirValidator")
    private FhirValidator basicFhirValidator;  // Basic validator for lenient validation

    @Autowired
    public IParser jsonParser;

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Process and store an individual FHIR resource with audit metadata using strict validation (default)
     * @param resourceJson The JSON string of the FHIR resource
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @return Map with processing results (resourceType, resourceId, success, etc.)
     */
    public Map<String, Object> processAndStoreResource(String resourceJson, Cluster cluster,
                                                       String bucketName, String operation) {
        return processAndStoreResource(resourceJson, cluster, bucketName, operation, false);
    }

    /**
     * Process and store an individual FHIR resource with audit metadata and configurable validation
     * @param resourceJson The JSON string of the FHIR resource
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     * @return Map with processing results (resourceType, resourceId, success, etc.)
     */
    public Map<String, Object> processAndStoreResource(String resourceJson, Cluster cluster,
                                                       String bucketName, String operation, boolean useLenientValidation) {
        return processAndStoreResource(resourceJson, cluster, bucketName, operation, useLenientValidation, false);
    }

    /**
     * Process and store an individual FHIR resource with full validation control
     * @param resourceJson The JSON string of the FHIR resource
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     * @param skipValidation If true, skips all validation for performance (use for trusted sample data)
     * @return Map with processing results (resourceType, resourceId, success, etc.)
     */
    public Map<String, Object> processAndStoreResource(String resourceJson, Cluster cluster,
                                                       String bucketName, String operation, boolean useLenientValidation,
                                                       boolean skipValidation) {
        Map<String, Object> result = new HashMap<>();

        try {
            // Parse the resource as FHIR object to add audit metadata
            Resource fhirResource = (Resource) jsonParser.parseResource(resourceJson);
            String resourceType = fhirResource.getResourceType().name();
            String resourceId = fhirResource.getIdElement().getIdPart();
            // Generate ID if not present
            if (resourceId == null || resourceId.isEmpty()) {
                resourceId = java.util.UUID.randomUUID().toString();
                fhirResource.setId(resourceId);
            }
            // Validate the resource (skip if requested for performance)
            if (!skipValidation) {
                FhirValidator validator = useLenientValidation ? basicFhirValidator : fhirValidator;
                String validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";

                ValidationResult validationResult = validator.validateWithResult(fhirResource);
                if (!validationResult.isSuccessful()) {
                    log.error("FHIR {} validation failed with {} validation:", resourceType, validationType);
                    validationResult.getMessages().forEach(msg ->
                            log.error("   {} - {}: {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                    );
                    result.put("success", false);
                    result.put("error", "FHIR validation failed: " + validationResult.getMessages().size() + " errors");
                    return result;
                }
                log.debug("✅ FHIR {} validation passed with {} validation", resourceType, validationType);
            } else {
                log.debug("⚡ FHIR {} validation SKIPPED for performance", resourceType);
            }

            // Add audit metadata using centralized service - preserves existing meta
            auditService.addAuditInfoToMeta(fhirResource, auditService.getCurrentUserId(), operation , "1");

            // Construct document key: resourceType/id
            String documentKey = resourceType + "/" + resourceId;

            // Get bucket and collection - use Resources scope and resourceType as collection
            Bucket bucket = cluster.bucket(bucketName);
            Collection collection = bucket.scope(DEFAULT_SCOPE).collection(resourceType);

            // Convert enhanced FHIR resource back to JSON for storage
            String enhancedResourceJson = jsonParser.encodeResourceToString(fhirResource);
            com.fasterxml.jackson.databind.JsonNode enhancedJsonNode = objectMapper.readTree(enhancedResourceJson);

            // Upsert the enhanced resource with audit metadata as JSON object
            collection.upsert(documentKey, enhancedJsonNode);

            // Build successful result
            result.put("success", true);
            result.put("resourceType", resourceType);
            result.put("resourceId", resourceId);
            result.put("documentKey", documentKey);
            result.put("operation", operation);

            log.debug("Successfully upserted {} resource with ID: {} (with audit metadata) into scope: {}, collection: {}",
                    resourceType, resourceId, DEFAULT_SCOPE, resourceType);

        } catch (Exception e) {
            log.error("Error processing individual resource: {}", e.getMessage());
            result.put("success", false);
            result.put("error", e.getMessage());
        }

        return result;
    }

    /**
     * Process and store an individual FHIR resource from parsed Resource object using strict validation (default)
     * @param fhirResource The FHIR Resource object
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @return Map with processing results
     */
    public Map<String, Object> processAndStoreResource(Resource fhirResource, Cluster cluster,
                                                       String bucketName, String operation) {
        return processAndStoreResource(fhirResource, cluster, bucketName, operation, false);
    }

    /**
     * Process and store an individual FHIR resource from parsed Resource object with configurable validation
     * @param fhirResource The FHIR Resource object
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     * @return Map with processing results
     */
    public Map<String, Object> processAndStoreResource(Resource fhirResource, Cluster cluster,
                                                       String bucketName, String operation, boolean useLenientValidation) {
        return processAndStoreResource(fhirResource, cluster, bucketName, operation, useLenientValidation, false);
    }

    /**
     * Process and store an individual FHIR resource from parsed Resource object with full validation control
     * @param fhirResource The FHIR Resource object
     * @param cluster The Couchbase cluster connection
     * @param bucketName The bucket name
     * @param operation The operation type ("CREATE", "UPDATE", etc.)
     * @param useLenientValidation If true, uses basic FHIR R4 validation; if false, uses strict US Core validation
     * @param skipValidation If true, skips all validation for performance (use for trusted sample data)
     * @return Map with processing results
     */
    public Map<String, Object> processAndStoreResource(Resource fhirResource, Cluster cluster,
                                                       String bucketName, String operation, boolean useLenientValidation,
                                                       boolean skipValidation) {
        try {
            // Convert to JSON and use the main method
            String resourceJson = jsonParser.encodeResourceToString(fhirResource);
            return processAndStoreResource(resourceJson, cluster, bucketName, operation, useLenientValidation, skipValidation);
        } catch (Exception e) {
            Map<String, Object> result = new HashMap<>();
            result.put("success", false);
            result.put("error", "Failed to convert resource to JSON: " + e.getMessage());
            return result;
        }
    }
}
