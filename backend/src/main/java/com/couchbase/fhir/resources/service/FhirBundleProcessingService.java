package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.util.BundleUtil;
import ca.uhn.fhir.util.FhirTerser;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.validation.ValidationResult;
import ca.uhn.fhir.validation.SingleValidationMessage;
import ca.uhn.fhir.validation.ResultSeverityEnum;
import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import org.hl7.fhir.instance.model.api.IBaseResource;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import jakarta.annotation.PostConstruct;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class FhirBundleProcessingService {

    private static final Logger logger = LoggerFactory.getLogger(FhirBundleProcessingService.class);

    @Autowired
    private ConnectionService connectionService;

    @Autowired
    private FhirContext fhirContext;

    @Autowired
    private FhirValidator fhirValidator;  // Primary US Core validator

    @Autowired
    @Qualifier("basicFhirValidator")
    private FhirValidator basicFhirValidator;  // Basic validator for sample data

    @Autowired
    private IParser jsonParser;

    @Autowired
    private FhirAuditService auditService;

    // Default connection and bucket names
    private static final String DEFAULT_CONNECTION = "default";
    private static final String DEFAULT_BUCKET = "fhir";
    private static final String DEFAULT_SCOPE = "Resources";

    @PostConstruct
    private void init() {
        logger.info("🚀 FHIR Bundle Processing Service initialized");
        // Configure parser for optimal performance - critical for bundle processing
        jsonParser.setPrettyPrint(false);                    // ✅ No formatting overhead
        jsonParser.setStripVersionsFromReferences(false);    // Skip processing
        jsonParser.setOmitResourceId(false);                 // Keep IDs as-is
        jsonParser.setSummaryMode(false);                    // Full resources
        jsonParser.setOverrideResourceIdWithBundleEntryFullUrl(false); // Big performance gain for bundles

        // Context-level optimizations
        fhirContext.getParserOptions().setStripVersionsFromReferences(false);
        fhirContext.getParserOptions().setOverrideResourceIdWithBundleEntryFullUrl(false);

        logger.info("✅ Bundle Processing Service optimized for high-performance transactions");
    }

    /**
     * Process a FHIR Bundle transaction with strict US Core validation (default)
     */
    public Bundle processBundleTransaction(String bundleJson, String connectionName, String bucketName) {
        return processBundleTransaction(bundleJson, connectionName, bucketName, false);
    }
    /**
     * Process a FHIR Bundle transaction with configurable validation
     * @param bundleJson Bundle JSON string
     * @param connectionName Couchbase connection name
     * @param bucketName Couchbase bucket name
     * @param useLenientValidation If true, uses basic FHIR validation instead of strict US Core validation
     */
    public Bundle processBundleTransaction(String bundleJson, String connectionName, String bucketName, boolean useLenientValidation) {
        return processBundleTransaction(bundleJson, connectionName, bucketName, useLenientValidation, false);
    }
    /**
     * Process a FHIR Bundle transaction with full validation control
     * @param bundleJson Bundle JSON string
     * @param connectionName Couchbase connection name
     * @param bucketName Couchbase bucket name
     * @param useLenientValidation If true, uses basic FHIR validation instead of strict US Core validation
     * @param skipValidation If true, skips all validation for performance (use for trusted sample data)
     */
    public Bundle processBundleTransaction(String bundleJson, String connectionName, String bucketName,
                                           boolean useLenientValidation, boolean skipValidation) {
        try {
            String validationType;
            if (skipValidation) {
                validationType = "NONE (skip validation for performance)";
            } else {
                validationType = useLenientValidation ? "lenient (basic FHIR R4)" : "strict (US Core 6.1.0)";
            }
            logger.info("🔄 Processing FHIR Bundle transaction with {} validation", validationType);
            // Step 1: Parse Bundle
            Bundle bundle = (Bundle) jsonParser.parseResource(bundleJson);
            logger.info("📦 Parsed Bundle with {} entries", bundle.getEntry().size());

            // Step 2: Validate Bundle structure (skip if requested for performance)
            if (!skipValidation) {
                ValidationResult bundleValidation = validateBundle(bundle, useLenientValidation);
                if (!bundleValidation.isSuccessful()) {
                    logger.error("❌ Bundle validation failed with {} errors", bundleValidation.getMessages().size());
                    bundleValidation.getMessages().forEach(msg ->
                            logger.error("   {} - {}: {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                    );
                    throw new RuntimeException("Bundle validation failed - see logs for details");
                }
                logger.info("✅ Bundle structure validation passed ({})", validationType);
            } else {
                logger.info("⚡ Bundle structure validation SKIPPED for performance");
            }

            // Step 3: Extract all resources from Bundle using HAPI utility
            List<IBaseResource> allResources = BundleUtil.toListOfResources(fhirContext, bundle);
            logger.info("📋 Extracted {} resources from Bundle", allResources.size());

            // Step 4: Process entries sequentially with proper UUID resolution
            List<ProcessedEntry> processedEntries = processEntriesSequentially(bundle, connectionName, bucketName, skipValidation);

            // Step 5: Create proper FHIR transaction-response Bundle
            return createTransactionResponseBundle(processedEntries, bundle.getType());

        } catch (Exception e) {
            logger.error("❌ Failed to process Bundle transaction: {}", e.getMessage(), e);
            throw new RuntimeException("Bundle processing failed: " + e.getMessage(), e);
        }
    }

    /**
     * Process Bundle entries sequentially with proper UUID resolution
     */
    private List<ProcessedEntry> processEntriesSequentially(Bundle bundle, String connectionName, String bucketName, boolean skipValidation) {
        logger.info("🔄 Processing Bundle entries sequentially (validation: {})", skipValidation ? "SKIPPED" : "ENABLED");

        connectionName = connectionName != null ? connectionName : getDefaultConnection();
        bucketName = bucketName != null ? bucketName : DEFAULT_BUCKET;

        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new RuntimeException("No active connection found: " + connectionName);
        }

        // Step 1: Build UUID mapping for all entries first
        Map<String, String> uuidToIdMapping = buildUuidMapping(bundle);

        // Step 2: Process each entry in order
        List<ProcessedEntry> processedEntries = new ArrayList<>();
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();

        for (int i = 0; i < bundle.getEntry().size(); i++) {
            Bundle.BundleEntryComponent entry = bundle.getEntry().get(i);
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();

            try {
                // Step 2a: Resolve UUID references in this resource
                resolveUuidReferencesInResource(resource, uuidToIdMapping);

                // Step 2b: Validate the resource (skip if requested for performance)
                if (!skipValidation) {
                    ValidationResult result = fhirValidator.validateWithResult(resource);

                    // Filter out INFORMATION level messages
                    List<SingleValidationMessage> filteredMessages = result
                            .getMessages()
                            .stream()
                            .filter(msg -> msg.getSeverity() != ResultSeverityEnum.INFORMATION)
                            .collect(Collectors.toList());

                    ValidationResult validation = new ValidationResult(result.getContext(), filteredMessages);

                    if (!validation.isSuccessful()) {
                        logger.warn("⚠️ Validation failed for {} with {} significant issues", resourceType, validation.getMessages().size());
                        // Continue processing even if validation fails (configurable behavior)
                    }
                }

                // Step 2c: Add audit information
                auditService.addAuditInfoToMeta(resource, auditInfo, "CREATE" , "1");

                // Step 2d: Prepare for insertion
                String resourceId = resource.getIdElement().getIdPart();
                String documentKey = resourceType + "/" + resourceId;

                // Step 2e: Insert into Couchbase
                insertResourceIntoCouchbase(cluster, bucketName, resourceType, documentKey, resource);

                // Step 2f: Create response entry
                Bundle.BundleEntryComponent responseEntry = createResponseEntry(resource, resourceType);

                processedEntries.add(ProcessedEntry.success(resourceType, resourceId, documentKey, responseEntry));
                logger.debug("✅ Successfully processed {}/{}", resourceType, resourceId);

            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.error("❌ Failed to process {} entry: {} (Exception: {})", resourceType, errorMessage, e.getClass().getSimpleName(), e);

                // Additional debug logging for the resource that failed
                logger.error("   Resource ID: {}", resource.getId());
                logger.error("   Resource Type: {}", resourceType);
                if (entry.getFullUrl() != null) {
                    logger.error("   FullUrl: {}", entry.getFullUrl());
                }

                processedEntries.add(ProcessedEntry.failed("Failed to process " + resourceType + ": " + errorMessage));
            }
        }

        return processedEntries;
    }

    /**
     * Build UUID mapping for all entries in the Bundle
     */
    private Map<String, String> buildUuidMapping(Bundle bundle) {
        Map<String, String> uuidToIdMapping = new HashMap<>();

        logger.debug("🔄 Building UUID mapping for Bundle with {} entries", bundle.getEntry().size());

        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            Resource resource = entry.getResource();
            String resourceType = resource.getResourceType().name();

            logger.debug("📝 Processing entry - ResourceType: {}, FullUrl: {}, Initial ID: {}",
                    resourceType, entry.getFullUrl(), resource.getId());

            String actualResourceId;

            // Extract meaningful ID from urn:uuid if present
            if (entry.getFullUrl() != null && entry.getFullUrl().startsWith("urn:uuid:")) {
                String uuidFullUrl = entry.getFullUrl(); // "urn:uuid:org1"
                actualResourceId = extractIdFromUuid(uuidFullUrl); // "org1"
                logger.debug("🆔 Extracted ID from UUID: {} → {}", uuidFullUrl, actualResourceId);
                // Map the full urn:uuid to the resource reference
                String mappedReference = resourceType + "/" + actualResourceId; // "Organization/org1"
                uuidToIdMapping.put(uuidFullUrl, mappedReference);
                logger.debug("🔗 UUID mapping: {} → {}", uuidFullUrl, mappedReference);
            } else {
                // Generate ID for resources without urn:uuid
                actualResourceId = generateResourceId(resourceType);
                logger.debug("🆔 Generated new ID for {}: {}", resourceType, actualResourceId);
            }

            // Set the actual ID on the resource
            resource.setId(actualResourceId);
        }

        logger.debug("📊 Final UUID mapping: {}", uuidToIdMapping);
        return uuidToIdMapping;
    }

    /**
     * Extract meaningful ID from urn:uuid format
     */
    private String extractIdFromUuid(String uuidFullUrl) {
        if (uuidFullUrl.startsWith("urn:uuid:")) {
            String extracted = uuidFullUrl.substring("urn:uuid:".length());
            // Validate that it's a reasonable ID (optional)
            if (isValidResourceId(extracted)) {
                return extracted;
            } else {
                // Fallback to generated ID if the UUID part isn't suitable
                logger.warn("⚠️ UUID part '{}' not suitable as resource ID, generating new one", extracted);
                return UUID.randomUUID().toString();
            }
        }
        return UUID.randomUUID().toString();
    }

    /**
     * Check if extracted ID is valid for use as resource ID
     */
    private boolean isValidResourceId(String id) {
        // FHIR ID rules: length 1-64, [A-Za-z0-9\-\.]{1,64}
        return id != null &&
                id.length() >= 1 &&
                id.length() <= 64 &&
                id.matches("[A-Za-z0-9\\-\\.]+");
    }

    /**
     * Generate a new resource ID
     */
    private String generateResourceId(String resourceType) {
        return UUID.randomUUID().toString();
    }

    /**
     * Resolve urn:uuid references in a single resource
     */
    private void resolveUuidReferencesInResource(Resource resource, Map<String, String> uuidToIdMapping) {
        FhirTerser terser = fhirContext.newTerser();
        String resourceType = resource.getResourceType().name();

        // Find all Reference fields in the resource
        List<Reference> references = terser.getAllPopulatedChildElementsOfType(resource, Reference.class);

        logger.debug("🔍 Found {} references in {}", references.size(), resourceType);

        for (Reference reference : references) {
            String originalRef = reference.getReference();
            logger.debug("🔍 Processing reference: {}", originalRef);

            if (originalRef != null && originalRef.contains("urn:uuid:")) {
                // Handle both "urn:uuid:xxx" and "ResourceType/urn:uuid:xxx" formats
                String uuid = null;
                if (originalRef.startsWith("urn:uuid:")) {
                    uuid = originalRef; // Direct UUID reference
                } else if (originalRef.contains("/urn:uuid:")) {
                    // Extract just the urn:uuid part
                    int uuidIndex = originalRef.indexOf("urn:uuid:");
                    uuid = originalRef.substring(uuidIndex);
                }

                if (uuid != null) {
                    String actualReference = uuidToIdMapping.get(uuid);

                    if (actualReference != null) {
                        reference.setReference(actualReference);
                        logger.debug("🔗 Resolved reference in {}: {} → {}", resourceType, originalRef, actualReference);
                    } else {
                        logger.warn("⚠️ Could not resolve UUID reference: {} (uuid: {})", originalRef, uuid);
                        logger.debug("⚠️ Available mappings: {}", uuidToIdMapping.keySet());
                    }
                } else {
                    logger.warn("⚠️ Could not extract UUID from reference: {}", originalRef);
                }
            } else {
                logger.debug("📝 Non-UUID reference (skipping): {}", originalRef);
            }
        }
    }

    /**
     * Insert a single resource into Couchbase
     */
    private void insertResourceIntoCouchbase(Cluster cluster, String bucketName, String resourceType,
                                             String documentKey, Resource resource) {
        // Ensure proper meta information using FhirMetaHelper
        String versionId = "1";
        Date lastUpdated = new Date();
        java.util.List<String> profiles = null;
        if (resource.getMeta() != null && resource.getMeta().hasProfile()) {
            profiles = new java.util.ArrayList<>();
            for (org.hl7.fhir.r4.model.CanonicalType ct : resource.getMeta().getProfile()) {
                profiles.add(ct.getValue());
            }
        }
        // Use auditService to get user info if available, else fallback
        String createdBy = "user:anonymous";
        try {
            com.couchbase.fhir.resources.service.FhirAuditService auditService = this.auditService;
            if (auditService != null) {
                com.couchbase.fhir.resources.service.UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
                if (auditInfo != null && auditInfo.getUserId() != null) {
                    createdBy = "user:" + auditInfo.getUserId();
                }
            }
        } catch (Exception e) {}
        com.couchbase.common.fhir.FhirMetaHelper.applyMeta(
                resource,
                lastUpdated,
                versionId,
                profiles,
                createdBy
        );

        // Convert to JSON and then to Map for Couchbase
        String resourceJson = jsonParser.encodeResourceToString(resource);
        Map<String, Object> resourceMap = JsonObject.fromJson(resourceJson).toMap();

        // UPSERT into appropriate collection
        String sql = String.format(
                "UPSERT INTO `%s`.`%s`.`%s` (KEY, VALUE) VALUES ('%s', %s)",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey,
                JsonObject.from(resourceMap).toString()
        );

        cluster.query(sql);
        logger.debug("✅ Upserted {}/{} into collection", resourceType, resource.getIdElement().getIdPart());
    }

    /**
     * Create a response entry for Bundle transaction response
     */
    private Bundle.BundleEntryComponent createResponseEntry(Resource resource, String resourceType) {
        Bundle.BundleEntryComponent responseEntry = new Bundle.BundleEntryComponent();

        // Set the resource in response
        responseEntry.setResource(resource);

        // Set response details
        Bundle.BundleEntryResponseComponent response = new Bundle.BundleEntryResponseComponent();
        response.setStatus("201 Created");
        response.setLocation(resourceType + "/" + resource.getIdElement().getIdPart());
        responseEntry.setResponse(response);
        return responseEntry;
    }





    /**
     * Validate Bundle structure
     */
    private ValidationResult validateBundle(Bundle bundle, boolean useLenientValidation) {
        FhirValidator validator;
        if (useLenientValidation) {
            validator = basicFhirValidator;
            logger.info("Using basic FHIR R4 validator for lenient validation.");
        } else {
            validator = fhirValidator;
            logger.info("Using strict US Core 6.1.0 validator.");
        }

        ValidationResult result = validator.validateWithResult(bundle);

        // Filter out INFORMATION level messages
        List<SingleValidationMessage> filteredMessages = result
                .getMessages()
                .stream()
                .filter(msg -> msg.getSeverity() != ResultSeverityEnum.INFORMATION)
                .collect(Collectors.toList());

        return new ValidationResult(result.getContext(), filteredMessages);
    }

    /**
     * Create comprehensive response
     */
    /**
     * Create proper FHIR transaction-response Bundle
     */
    private Bundle createTransactionResponseBundle(List<ProcessedEntry> processedEntries,
                                                   Bundle.BundleType originalType) {
        Bundle responseBundle = new Bundle();

        // Set response type based on original bundle type
        if (originalType == Bundle.BundleType.TRANSACTION) {
            responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE);
        } else if (originalType == Bundle.BundleType.BATCH) {
            responseBundle.setType(Bundle.BundleType.BATCHRESPONSE);
        } else {
            responseBundle.setType(Bundle.BundleType.TRANSACTIONRESPONSE); // Default
        }

        responseBundle.setId(UUID.randomUUID().toString());
        responseBundle.setTimestamp(new Date());

        // Add meta information
        Meta bundleMeta = new Meta();
        bundleMeta.setLastUpdated(new Date());
        responseBundle.setMeta(bundleMeta);

        logger.debug("📦 Creating {} response with {} entries",
                responseBundle.getType().name(), processedEntries.size());

        // Add all response entries
        for (ProcessedEntry entry : processedEntries) {
            if (entry.isSuccess()) {
                responseBundle.addEntry(entry.getResponseEntry());
                logger.debug("✅ Added successful entry: {}/{}", entry.getResourceType(), entry.getResourceId());
            } else {
                // Create error entry
                Bundle.BundleEntryComponent errorEntry = createErrorEntry(entry.getErrorMessage());
                responseBundle.addEntry(errorEntry);
                logger.warn("❌ Added error entry: {}", entry.getErrorMessage());
            }
        }

        logger.info("📋 Created {} Bundle response with {} entries",
                responseBundle.getType().name(), responseBundle.getEntry().size());

        return responseBundle;
    }

    /**
     * Create error entry for Bundle response
     */
    private Bundle.BundleEntryComponent createErrorEntry(String errorMessage) {
        Bundle.BundleEntryComponent errorEntry = new Bundle.BundleEntryComponent();
        Bundle.BundleEntryResponseComponent errorResponse = new Bundle.BundleEntryResponseComponent();
        errorResponse.setStatus("400 Bad Request");
        errorResponse.setOutcome(createOperationOutcome(errorMessage));
        errorEntry.setResponse(errorResponse);
        return errorEntry;
    }
    /**
     * Create OperationOutcome for error responses
     */
    private OperationOutcome createOperationOutcome(String errorMessage) {
        OperationOutcome outcome = new OperationOutcome();
        OperationOutcome.OperationOutcomeIssueComponent issue = new OperationOutcome.OperationOutcomeIssueComponent();
        issue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
        issue.setCode(OperationOutcome.IssueType.PROCESSING);
        issue.setDiagnostics(errorMessage);
        outcome.addIssue(issue);
        return outcome;
    }

    private String getCurrentFhirTimestamp() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"));
    }



    /**
     * Create validation failure response
     */


    private String getDefaultConnection() {
        List<String> connections = connectionService.getActiveConnections();
        return connections.isEmpty() ? DEFAULT_CONNECTION : connections.get(0);
    }
}