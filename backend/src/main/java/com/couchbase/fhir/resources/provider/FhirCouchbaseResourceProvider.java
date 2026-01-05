package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.*;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.exceptions.InternalErrorException;
import ca.uhn.fhir.rest.server.exceptions.ResourceNotFoundException;
import ca.uhn.fhir.rest.server.exceptions.UnprocessableEntityException;
import ca.uhn.fhir.validation.ValidationResult;
import com.couchbase.fhir.resources.config.TenantContextHolder;
import com.couchbase.fhir.resources.repository.FhirResourceDaoImpl;
import com.couchbase.fhir.resources.service.FhirBucketConfigService;
import com.couchbase.fhir.resources.service.MetaRequest;
import com.couchbase.common.fhir.FhirMetaHelper;
import com.couchbase.fhir.resources.search.validation.FhirSearchParameterPreprocessor;
import com.couchbase.fhir.resources.validation.FhirBucketValidator;
import com.couchbase.fhir.resources.validation.FhirBucketValidationException;
import org.hl7.fhir.r4.model.*;
import ca.uhn.fhir.validation.FhirValidator;
import ca.uhn.fhir.rest.annotation.Operation;
import ca.uhn.fhir.rest.annotation.ResourceParam;
import ca.uhn.fhir.rest.api.PatchTypeEnum;

import java.io.IOException;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Generic FHIR resource provider for HAPI FHIR that enables CRUD operations and search capabilities
 * for any FHIR resource type backed by a Couchbase data store.
 *
 * <p>This class dynamically handles requests for FHIR resources using the generic type {@code T}
 * and delegates persistence logic to the associated {@link FhirResourceDaoImpl}. It integrates
 * validation using the HAPI FHIR validation API and ensures the resource conforms to US Core profiles
 * when applicable.</p>
 *
 * @param <T> A FHIR resource type extending {@link Resource}
 */

public class FhirCouchbaseResourceProvider <T extends Resource> implements IResourceProvider {

    private static final Logger logger = LoggerFactory.getLogger(FhirCouchbaseResourceProvider.class);
    private final Class<T> resourceClass;
    private final FhirResourceDaoImpl<T> dao;
    private final FhirContext fhirContext;
    private final FhirBucketValidator bucketValidator;
    private final FhirBucketConfigService configService;
    private final FhirValidator strictValidator; // Primary US Core validator
    private final FhirValidator lenientValidator; // Basic validator
    private final com.couchbase.admin.connections.service.ConnectionService connectionService;
    private final com.couchbase.fhir.resources.service.PutService putService;
    private final com.couchbase.fhir.resources.service.DeleteService deleteService;
    private final FhirMetaHelper metaHelper;
    private final com.couchbase.fhir.resources.service.SearchService searchService;
    private final com.couchbase.fhir.resources.service.PatchService patchService;
    private final com.couchbase.fhir.resources.service.ConditionalPutService conditionalPutService;
    private final com.couchbase.fhir.resources.service.HistoryService historyService;
    private final com.couchbase.fhir.resources.service.EverythingService everythingService;
    private final com.couchbase.fhir.resources.search.SearchStateManager searchStateManager;
    private final com.couchbase.common.config.FhirServerConfig fhirServerConfig;


    public FhirCouchbaseResourceProvider(Class<T> resourceClass, FhirResourceDaoImpl<T> dao , FhirContext fhirContext, FhirSearchParameterPreprocessor searchPreprocessor, FhirBucketValidator bucketValidator, FhirBucketConfigService configService, FhirValidator strictValidator, FhirValidator lenientValidator, com.couchbase.admin.connections.service.ConnectionService connectionService, com.couchbase.fhir.resources.service.PutService putService, com.couchbase.fhir.resources.service.DeleteService deleteService, FhirMetaHelper metaHelper, com.couchbase.fhir.resources.service.SearchService searchService, com.couchbase.fhir.resources.service.PatchService patchService, com.couchbase.fhir.resources.service.ConditionalPutService conditionalPutService, com.couchbase.fhir.resources.service.HistoryService historyService, com.couchbase.fhir.resources.service.EverythingService everythingService, com.couchbase.fhir.resources.search.SearchStateManager searchStateManager, com.couchbase.common.config.FhirServerConfig fhirServerConfig) {
        this.resourceClass = resourceClass;
        this.dao = dao;
        this.fhirContext = fhirContext;
        // searchPreprocessor is now handled by SearchService
        this.bucketValidator = bucketValidator;
        this.configService = configService;
        this.strictValidator = strictValidator;
        this.lenientValidator = lenientValidator;
        this.connectionService = connectionService;
        this.putService = putService;
        this.deleteService = deleteService;
        this.metaHelper = metaHelper;
        // objectMapper is now handled by PatchService
        this.searchService = searchService;
        this.patchService = patchService;
        this.conditionalPutService = conditionalPutService;
        this.historyService = historyService;
        this.everythingService = everythingService;
        this.searchStateManager = searchStateManager;
        this.fhirServerConfig = fhirServerConfig;
    }

    /**
     * Get the correct FHIR resource type name (e.g., "List", "Patient") 
     * instead of the Java class name (e.g., "ListResource", "Patient")
     */
    private String getFhirResourceType() {
        return fhirContext.getResourceDefinition(resourceClass).getName();
    }

    /**
     * FHIR read operation: Read current version of a resource
     * GET {resourceType}/{id}
     */
    @Read
    public T read(@IdParam IdType theId) {
        String bucketName = TenantContextHolder.getTenantId();
        String resourceType = getFhirResourceType();
        String id = theId.getIdPart();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }
        
        return dao.read(resourceType, id, bucketName).orElseThrow(() ->
                new ResourceNotFoundException(theId));
    }

    /**
     * FHIR vread operation: Read a specific version of a resource
     * GET {resourceType}/{id}/_history/{vid}
     * 
     * Note: HAPI sometimes routes regular reads here, so we delegate to read() if no version is specified
     */
    @Read(version = true)
    public T vread(@IdParam IdType theId) {
        String bucketName = TenantContextHolder.getTenantId();
        String resourceType = getFhirResourceType();
        String id = theId.getIdPart();
        String versionId = theId.getVersionIdPart();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }
        
        // If no version is specified, this is a regular read request - delegate to read()
        if (versionId == null || versionId.isEmpty()) {
            logger.debug("📖 vread called without version, delegating to read()");
            return read(theId);
        }
        
        logger.info("📜 vread request: {}/{} version {}", resourceType, id, versionId);
        
        Resource resource = historyService.getResourceVersion(resourceType, id, versionId, bucketName);
        
        @SuppressWarnings("unchecked")
        T typedResource = (T) resource;
        return typedResource;
    }

    /**
     * FHIR history operation: Get version history for a resource instance
     * GET {resourceType}/{id}/_history
     * 
     * Returns List<T> and lets HAPI FHIR handle bundle construction - much simpler!
     */
    @History
    public List<T> getResourceInstanceHistory(
            @IdParam IdType theId,
            @ca.uhn.fhir.rest.annotation.Since java.util.Date theSince,
            @ca.uhn.fhir.rest.annotation.Count Integer theCount
    ) {
        String bucketName = TenantContextHolder.getTenantId();
        String resourceType = getFhirResourceType();
        String id = theId.getIdPart();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }
        
        // Convert Since date to Instant
        java.time.Instant sinceInstant = theSince != null ? theSince.toInstant() : null;
        
        logger.info("📜 History request: {}/{} (count={}, since={})", resourceType, id, theCount, sinceInstant);
        
        // Get list of versioned resources - HAPI will wrap them in a history bundle
        List<Resource> versions = historyService.getResourceHistoryResources(resourceType, id, theCount, sinceInstant, bucketName);
        
        // Cast to List<T> for HAPI
        @SuppressWarnings("unchecked")
        List<T> typedVersions = (List<T>) (List<?>) versions;
        
        return typedVersions;
    }

    @Create
    public MethodOutcome create(@ResourceParam T resource) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        if (resource.getIdElement().isEmpty()) {
            resource.setId(UUID.randomUUID().toString());
        }

        // Get bucket-specific validation configuration first
        FhirBucketConfigService.FhirBucketConfig bucketConfig = configService.getFhirBucketConfig(bucketName);

        // Apply proper meta with version "1" for CREATE operations
        List<String> profiles = null;
        if (resource instanceof DomainResource && bucketConfig.isEnforceUSCore()) {
            String resourceType = resource.fhirType();
            // Only add US Core profiles for resources that actually have them
            // List resource doesn't have a US Core profile, so skip it
            if (!"List".equals(resourceType)) {
                String profileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-" + 
                                    resourceType.toLowerCase();
                profiles = List.of(profileUrl);
            }
        }
        
        MetaRequest metaRequest = MetaRequest.forCreate(null, "1", profiles);
        metaHelper.applyMeta(resource, metaRequest);
        
        if (profiles != null && !profiles.isEmpty()) {
            logger.debug("🔍 ResourceProvider: Added US Core profile: {}", profiles.get(0));
        }
        
        String resourceType = resource.fhirType();
        logger.debug("🔍 ResourceProvider: Processing {} for bucket: {}", resourceType, bucketName);
        logger.debug("🔍 ResourceProvider: Bucket config - strict: {}, enforceUSCore: {}, validationDisabled: {}", 
            bucketConfig.isStrictValidation(), bucketConfig.isEnforceUSCore(), bucketConfig.isValidationDisabled());
        
        // Perform validation based on bucket configuration
        if (!bucketConfig.isValidationDisabled()) {
            // Choose validator based on bucket configuration
            FhirValidator validator;
            String validationMode;
            
            if (bucketConfig.isStrictValidation() || bucketConfig.isEnforceUSCore()) {
                validator = strictValidator;
                validationMode = "strict (US Core enforced)";
            } else {
                validator = lenientValidator;
                validationMode = "lenient (basic FHIR R4)";
            }

            logger.debug("🔍 ResourceProvider: Using {} validation for bucket: {}", validationMode, bucketName);
            logger.debug("🔍 ResourceProvider: About to validate resource with validator: {}", validator.getClass().getSimpleName());

            ValidationResult result = validator.validateWithResult(resource);

            logger.debug("🔍 ResourceProvider: Validation result - isSuccessful: {}, messageCount: {}", 
                result.isSuccessful(), result.getMessages().size());
            
            if (!result.isSuccessful()) {
                logger.info("🔍 ResourceProvider: Validation FAILED - messages:");
                result.getMessages().forEach(msg -> 
                    logger.info("🔍   {}: {} - {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                );
                
                if (bucketConfig.isStrictValidation()) {
                    // Strict mode: reject any validation errors
                    StringBuilder issues = new StringBuilder();
                    result.getMessages().forEach(msg -> issues.append(msg.getSeverity())
                            .append(": ")
                            .append(msg.getLocationString())
                            .append(" - ")
                            .append(msg.getMessage())
                            .append("\n"));
                    logger.error("🔍 ResourceProvider: Throwing UnprocessableEntityException for strict validation failure");
                    throw new UnprocessableEntityException("FHIR Validation failed (strict mode):\n" + issues.toString());
                } else if (bucketConfig.isLenientValidation()) {
                    // Lenient mode: log warnings but continue
                    logger.warn("FHIR Validation warnings for {} (lenient mode - continuing):", resourceType);
                    result.getMessages().forEach(msg -> 
                        logger.warn("  {}: {} - {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                    );
                }
            } else {
                logger.debug("🔍 ResourceProvider: ✅ FHIR validation PASSED for {} in bucket {}", resourceType, bucketName);
            }
        } else {
            logger.debug("🔍 ResourceProvider: FHIR Validation DISABLED for bucket: {}", bucketName);
        }


        T created =  dao.create( resourceType , resource , bucketName).orElseThrow(() ->
                new InternalErrorException("Failed to create resource"));
        MethodOutcome outcome = new MethodOutcome();
        outcome.setCreated(true);
        outcome.setResource(created);
        outcome.setId(new IdType(resourceType, created.getIdElement().getIdPart()));
        return outcome;
    }

    /**
     * Unified PUT handler: supports both ID-based and conditional PUT
     * - PUT /Patient/123 → ID-based update
     * - PUT /Patient?family=Smith → Conditional update (upsert)
     */
    @Update
    public MethodOutcome update(
        @IdParam(optional = true) IdType theId,
        @ResourceParam T resource,
        @ConditionalUrlParam String theConditionalUrl,
        RequestDetails requestDetails
    ) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        String resourceType = getFhirResourceType();
        
        if (theId != null && theId.hasIdPart()) {
            // ID-based PUT: PUT /Patient/123
            String urlId = theId.getIdPart();
            logger.debug("🔄 ResourceProvider: Processing ID-based PUT for {}/{}", resourceType, urlId);
            
            // Ensure the resource ID matches the URL parameter
            resource.setId(urlId);
            
            return performPut(resource, urlId, bucketName, false);
            
        } else {
            // Conditional PUT - use HAPI's already-parsed parameters
            logger.debug("🔄 ResourceProvider: Processing conditional PUT for {}", resourceType);

            Map<String, String[]> rawParams = requestDetails.getParameters();
            Map<String, List<String>> searchCriteria = new LinkedHashMap<>();
            for (Map.Entry<String, String[]> e : rawParams.entrySet()) {
                String key = e.getKey();
                if (key.startsWith("_")) continue;      // skip control params
                String[] vals = e.getValue();
                if (vals != null && vals.length > 0) {
                    searchCriteria.put(key, Arrays.asList(vals));   // preserve all values
                }
            }
            
            logger.debug("🔍 ResourceProvider: Conditional PUT criteria: {}", searchCriteria);
            
            if (searchCriteria.isEmpty()) {
                throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(
                    "PUT operation requires either an ID in the URL or search parameters for conditional update");
            }
            
            return conditionalPutService.conditionalPut(resource, searchCriteria, resourceType);
        }
    }
    

    
    /**
     * Perform the actual PUT operation for both ID-based and conditional updates
     */
    private MethodOutcome performPut(T resource, String expectedId, String bucketName, boolean isConditionalCreate) throws IOException {
        String resourceType = getFhirResourceType();
        String resourceId = resource.getIdElement() != null ? resource.getIdElement().getIdPart() : "new";
        
        logger.debug("🔄 PUT {}: Processing {} for ID {}", resourceType, 
            isConditionalCreate ? "conditional create" : "update", resourceId);

        // Delegate to PutService for proper versioning and tombstone checking
        try {
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");
            com.couchbase.fhir.resources.service.TransactionContextImpl context = 
                new com.couchbase.fhir.resources.service.TransactionContextImpl(cluster, bucketName);
            
            @SuppressWarnings("unchecked")
            T updatedResource = (T) putService.updateOrCreateResource(resource, context);
            
            MethodOutcome outcome = new MethodOutcome();
            outcome.setResource(updatedResource);
            outcome.setId(new IdType(resourceType, updatedResource.getIdElement().getIdPart()));
            
            // Determine if this was a create or update based on version
            String versionId = updatedResource.getMeta().getVersionId();
            if ("1".equals(versionId) || isConditionalCreate) {
                outcome.setCreated(true); // New resource
                logger.debug("✅ PUT {}: Created new resource with ID {}", resourceType, updatedResource.getIdElement().getIdPart());
            } else {
                outcome.setCreated(false); // Updated existing
                logger.debug("✅ PUT {}: Updated existing resource with ID {}, version {}", resourceType, updatedResource.getIdElement().getIdPart(), versionId);
            }
            
            return outcome;
            
        } catch (ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException e) {
            // ID was tombstoned - return 409
            throw e;
        } catch (Exception e) {
            logger.error("❌ PUT {}: Failed to update resource {}: {}", resourceType, resourceId, e.getMessage());
            throw new InternalErrorException("Failed to update resource: " + e.getMessage());
        }
    }

    @Delete
    public MethodOutcome delete(@IdParam(optional = true) IdType theId, RequestDetails requestDetails) throws IOException {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        String resourceType = getFhirResourceType();
        
        if (theId != null && theId.hasIdPart()) {
            // ID-based DELETE: DELETE /Patient/123
            String resourceId = theId.getIdPart();
            logger.debug("🗑️ DELETE {}: Processing ID-based delete for {}", resourceType, resourceId);

            return performDelete(resourceType, resourceId, bucketName);
            
        } else {
            // Conditional DELETE: DELETE /Patient?family=Smith
            logger.debug("🗑️ DELETE {}: Processing conditional delete", resourceType);
            
            // Extract search parameters from request
            Map<String, String[]> rawParams = requestDetails.getParameters();
            Map<String, List<String>> searchCriteria = new HashMap<>();
            
            for (Map.Entry<String, String[]> entry : rawParams.entrySet()) {
                String paramName = entry.getKey();
                String[] values = entry.getValue();
                
                // Skip FHIR control parameters
                if (paramName.startsWith("_")) {
                    continue;
                }
                
                if (values != null && values.length > 0) {
                    searchCriteria.put(paramName, Arrays.asList(values)); // Preserve all values
                }
            }
            
            if (searchCriteria.isEmpty()) {
                throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(
                    "Conditional DELETE requires search parameters");
            }
            
            logger.debug("🔍 DELETE {}: Conditional criteria: {}", resourceType, searchCriteria);
            
            // Use SearchService to resolve the condition
            com.couchbase.fhir.resources.service.ResolveResult resolveResult = searchService.resolveConditional(resourceType, searchCriteria);
            
            switch (resolveResult.getStatus()) {
                case ZERO:
                    // No matches - return 404 Not Found
                    logger.warn("❌ DELETE {}: No resources found matching criteria", resourceType);
                    throw new ResourceNotFoundException("No " + resourceType + " found matching the specified criteria");
                    
                case MANY:
                    // Multiple matches - return 412 Precondition Failed
                    logger.warn("❌ DELETE {}: Multiple resources found matching criteria", resourceType);
                    throw new ca.uhn.fhir.rest.server.exceptions.PreconditionFailedException(
                        "Multiple " + resourceType + " resources found matching the specified criteria. " +
                        "Conditional DELETE requires exactly one match.");
                    
                case ONE:
                    // Single match - proceed with delete
                    String resourceId = resolveResult.getResourceId();
                    logger.debug("✅ DELETE {}: Found single match: {}", resourceType, resourceId);
                    
                    return performDelete(resourceType, resourceId, bucketName);
                    
                default:
                    throw new InternalErrorException("Unexpected resolve result: " + resolveResult.getStatus());
            }
        }
    }
    
    /**
     * Perform the actual DELETE operation for both ID-based and conditional deletes
     */
    private MethodOutcome performDelete(String resourceType, String resourceId, String bucketName) throws IOException {
        logger.debug("🗑️ DELETE {}: Processing soft delete for ID {}", resourceType, resourceId);

        // Delegate to DeleteService for proper tombstone handling
        try {
            com.couchbase.client.java.Cluster cluster = connectionService.getConnection("default");
            com.couchbase.fhir.resources.service.TransactionContextImpl context = 
                new com.couchbase.fhir.resources.service.TransactionContextImpl(cluster, bucketName);
            
            deleteService.deleteResource(resourceType, resourceId, context);
            
            // DELETE always returns 204 No Content (idempotent)
            MethodOutcome outcome = new MethodOutcome();
            // Note: No resource or ID set for DELETE - just success indication

            logger.debug("✅ DELETE {}: Soft delete completed for ID {}", resourceType, resourceId);
            return outcome;
            
        } catch (Exception e) {
            logger.error("❌ DELETE {}: Failed to delete resource {}: {}", resourceType, resourceId, e.getMessage());
            throw new InternalErrorException("Failed to delete resource: " + e.getMessage());
        }
    }

        @ca.uhn.fhir.rest.annotation.Patch
    public MethodOutcome patch(@IdParam IdType theId, PatchTypeEnum patchType, @ResourceParam String patchBody) throws IOException {
        String resourceId = theId.getIdPart();
        String resourceType = getFhirResourceType();
        
        logger.debug("🔧 ResourceProvider: Delegating PATCH for {}/{} to PatchService", resourceType, resourceId);
        
        // Validate patch type - we only support JSON Patch
        if (patchType != PatchTypeEnum.JSON_PATCH) {
            logger.error("❌ ResourceProvider: Unsupported patch type: {}", patchType);
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(
                "Only JSON Patch is supported. Received: " + patchType);
        }
        
        // Delegate to PatchService for all patch operations
        return patchService.patchResource(resourceType, resourceId, patchBody, resourceClass);
    }

    @Search(allowUnknownParams = true)
    public Bundle search(RequestDetails requestDetails) {
        String resourceType = getFhirResourceType();
        
        // Check if this is a pagination request
        Map<String, String[]> params = requestDetails.getParameters();
        if (params.containsKey("_page")) {
            String continuationToken = params.get("_page")[0];
            int offset = 0;
            int count = 20; // default
            
            if (params.containsKey("_offset")) {
                try {
                    offset = Integer.parseInt(params.get("_offset")[0]);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid _offset parameter: {}", params.get("_offset")[0]);
                }
            }
            
            if (params.containsKey("_count")) {
                try {
                    count = Integer.parseInt(params.get("_count")[0]);
                } catch (NumberFormatException e) {
                    logger.warn("Invalid _count parameter: {}", params.get("_count")[0]);
                }
            }
            
            logger.debug("🔍 ResourceProvider: Handling pagination request for {} (token: {}, offset: {}, count: {})", 
                       resourceType, continuationToken, offset, count);
            
            return searchService.handleRevIncludePagination(continuationToken, offset, count, requestDetails);
        }

        logger.debug("🔍 ResourceProvider: Delegating search for {} to SearchService", resourceType);

        // Delegate to SearchService for all search operations
        return searchService.search(resourceType, requestDetails);
    }

    /**
     * FHIR $validate Operation - Validates a resource without storing it
     * POST /fhir/{bucket}/{ResourceType}/$validate
     * Uses singleton validator beans to avoid creating 95MB validator per request
     */
    @Operation(name = "$validate", idempotent = false)
    public OperationOutcome validateResource(@ResourceParam T resource) {
        try {
            String bucketName = TenantContextHolder.getTenantId();
            
            // Get bucket config to determine which validator to use
            FhirBucketConfigService.FhirBucketConfig bucketConfig = configService.getFhirBucketConfig(bucketName);
            
            // Use singleton validator based on bucket configuration
            FhirValidator validator;
            if (bucketConfig.isStrictValidation() || bucketConfig.isEnforceUSCore()) {
                validator = strictValidator;  // Reuse singleton (saves 95MB per request!)
                logger.debug("$validate using strict validator");
            } else {
                validator = lenientValidator; // Reuse singleton
                logger.debug("$validate using lenient validator");
            }
            
            // Validate the resource
            ValidationResult result = validator.validateWithResult(resource);
            
            // Create OperationOutcome based on validation result
            OperationOutcome outcome = new OperationOutcome();
            
            if (result.isSuccessful()) {
                // Validation passed
                outcome.addIssue()
                    .setSeverity(OperationOutcome.IssueSeverity.INFORMATION)
                    .setCode(OperationOutcome.IssueType.INFORMATIONAL)
                    .setDiagnostics("Resource validation successful");
                    
            } else {
                // Validation failed - add all issues
                for (var issue : result.getMessages()) {
                    OperationOutcome.OperationOutcomeIssueComponent outcomeIssue = outcome.addIssue();
                    
                    // Map severity
                    switch (issue.getSeverity()) {
                        case ERROR:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                            break;
                        case WARNING:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.WARNING);
                            break;
                        case INFORMATION:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.INFORMATION);
                            break;
                        default:
                            outcomeIssue.setSeverity(OperationOutcome.IssueSeverity.ERROR);
                    }
                    
                    // Set issue type and message
                    outcomeIssue.setCode(OperationOutcome.IssueType.INVALID);
                    outcomeIssue.setDiagnostics(issue.getMessage());
                    
                    // Add location if available
                    if (issue.getLocationString() != null) {
                        outcomeIssue.addLocation(issue.getLocationString());
                    }
                }
            }
            
            return outcome;
            
        } catch (Exception e) {
            // Handle validation errors
            OperationOutcome errorOutcome = new OperationOutcome();
            errorOutcome.addIssue()
                .setSeverity(OperationOutcome.IssueSeverity.ERROR)
                .setCode(OperationOutcome.IssueType.EXCEPTION)
                .setDiagnostics("Validation failed: " + e.getMessage());
            
            return errorOutcome;
        }
    }

    /**
     * FHIR $everything Operation - Get all resources related to a patient
     * GET /fhir/{bucket}/Patient/{id}/$everything
     * 
     * Only available for Patient resources. Returns:
     * - The patient resource
     * - All resources that reference the patient (Observation, Condition, etc.)
     * 
     * Supports query parameters:
     * - start: Start date filter (Date)
     * - end: End date filter (Date)
     * - _type: Comma-separated list of resource types to include
     * - _since: Only resources updated after this instant
     * - _count: Page size (default: 50, max: 200)
     */
    @Operation(name = "$everything", idempotent = true, type = Patient.class)
    public Bundle patientEverything(
            @IdParam IdType theId,
            @OperationParam(name = "start") DateParam start,
            @OperationParam(name = "end") DateParam end,
            @OperationParam(name = "_type") StringParam types,
            @OperationParam(name = "_since") DateParam since,
            @OperationParam(name = "_count") IntegerType count,
            @OperationParam(name = "_page") StringParam page,
            @OperationParam(name = "_offset") IntegerType offset,
            RequestDetails requestDetails) {
        
        String patientId = theId.getIdPart();
        String continuationToken = page != null ? page.getValue() : null;
        
        // Convert DateParam to Date if provided
        Date startDate = start != null ? start.getValue() : null;
        Date endDate = end != null ? end.getValue() : null;
        Date sinceDate = since != null ? since.getValue() : null;
        
        // Convert StringParam to String
        String typeString = types != null ? types.getValue() : null;
        
        // Convert IntegerType to Integer
        Integer countInt = count != null ? count.getValue() : null;
        Integer offsetInt = offset != null ? offset.getValue() : 0;
        
        // Handle pagination continuation
        if (continuationToken != null) {
            logger.info("🌍 $everything continuation for Patient/{} (token: {}, offset: {}, count: {})", 
                       patientId, continuationToken, offsetInt, countInt);
            return buildEverythingContinuationBundle(continuationToken, offsetInt, countInt, requestDetails);
        }
        
        logger.info("🌍 $everything operation for Patient/{}", patientId);
        
        // Get base URL
        String bucketName = TenantContextHolder.getTenantId();
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        
        // Get all resources related to the patient (with pagination support)
        com.couchbase.fhir.resources.service.EverythingService.EverythingResult result = 
            everythingService.getPatientEverything(
                patientId,
                startDate,
                endDate,
                typeString,
                sinceDate,
                countInt,
                baseUrl
            );
        
        // Store pagination state if needed
        String paginationToken = null;
        if (result.needsPagination) {
            int effectiveCount = (countInt != null && countInt > 0) ? Math.min(countInt, 200) : 50;
            com.couchbase.fhir.resources.search.PaginationState paginationState = 
                com.couchbase.fhir.resources.search.PaginationState.builder()
                    .searchType("everything")
                    .resourceType("Patient")
                    .allDocumentKeys(result.allDocumentKeys)
                    .pageSize(effectiveCount)
                    .currentOffset(effectiveCount) // Next page starts after first page
                    .bucketName(bucketName)
                    .baseUrl(baseUrl)
                    .build();
            
            paginationToken = searchStateManager.storePaginationState(paginationState);
            logger.info("✅ Created $everything PaginationState: token={}, totalKeys={}, pages={}", 
                       paginationToken, result.allDocumentKeys.size(), paginationState.getTotalPages());
        }
        
        // Build bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        bundle.setTotal(result.totalResourceCount); // Total includes patient + all related resources
        
        // Add patient resource first (always MATCH mode)
        Bundle.BundleEntryComponent patientEntry = bundle.addEntry();
        patientEntry.setResource(result.patient);
        patientEntry.setFullUrl(baseUrl + "/Patient/" + patientId);
        patientEntry.getSearch().setMode(Bundle.SearchEntryMode.MATCH);
        
        // Add all first page related resources (INCLUDE mode)
        for (Resource resource : result.firstPageResources) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setResource(resource);
            
            // Set fullUrl
            String resourceType = resource.getResourceType().name();
            String resourceId = resource.getIdElement().getIdPart();
            entry.setFullUrl(baseUrl + "/" + resourceType + "/" + resourceId);
            entry.getSearch().setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add self link
        String selfUrl = baseUrl + "/Patient/" + patientId + "/$everything";
        if (typeString != null) selfUrl += "?_type=" + typeString;
        if (startDate != null) selfUrl += (selfUrl.contains("?") ? "&" : "?") + "start=" + formatDate(startDate);
        if (endDate != null) selfUrl += (selfUrl.contains("?") ? "&" : "?") + "end=" + formatDate(endDate);
        if (sinceDate != null) selfUrl += (selfUrl.contains("?") ? "&" : "?") + "_since=" + formatDate(sinceDate);
        if (countInt != null) selfUrl += (selfUrl.contains("?") ? "&" : "?") + "_count=" + countInt;
        
        bundle.addLink()
            .setRelation("self")
            .setUrl(selfUrl);
        
        // Add next link if pagination is needed
        if (paginationToken != null) {
            int effectiveCount = (countInt != null && countInt > 0) ? Math.min(countInt, 200) : 50;
            String nextUrl = baseUrl + "/Patient/" + patientId + "/$everything?_page=" + paginationToken 
                           + "&_offset=" + effectiveCount 
                           + "&_count=" + effectiveCount;
            
            bundle.addLink()
                .setRelation("next")
                .setUrl(nextUrl);
        }
        
        logger.info("✅ $everything returning bundle with {} resources (total: {})", 
                   bundle.getEntry().size(), result.totalResourceCount);
        return bundle;
    }
    
    /**
     * Build continuation bundle for $everything pagination
     */
    private Bundle buildEverythingContinuationBundle(String continuationToken, int offset, Integer count, RequestDetails requestDetails) {
        String bucketName = TenantContextHolder.getTenantId();
        String baseUrl = extractBaseUrl(requestDetails, bucketName);
        
        // Extract patient ID from the request URL (e.g., /Patient/example/$everything)
        String patientId = extractPatientIdFromRequest(requestDetails);
        
        // Get next page of resources
        List<Resource> pageResources = everythingService.getPatientEverythingNextPage(continuationToken, offset, count);
        
        // Retrieve pagination state to check if there are more pages
        com.couchbase.fhir.resources.search.PaginationState paginationState = 
            searchStateManager.getPaginationState(continuationToken, bucketName);
        
        if (paginationState == null) {
            throw new ca.uhn.fhir.rest.server.exceptions.ResourceGoneException(
                "Pagination state has expired or is invalid. Please repeat your original $everything request.");
        }
        
        // Note: We do NOT update currentOffset in Couchbase
        // The offset is tracked in the URL (_offset parameter), not in the document
        // This keeps the document immutable (write once, read many) and avoids resetting TTL
        
        // Build bundle
        Bundle bundle = new Bundle();
        bundle.setType(Bundle.BundleType.SEARCHSET);
        if (paginationState != null && paginationState.getAllDocumentKeys() != null) {
            bundle.setTotal(paginationState.getAllDocumentKeys().size() + 1); // +1 for patient
        }
        
        // Add resources (all INCLUDE mode for continuation pages)
        for (Resource resource : pageResources) {
            Bundle.BundleEntryComponent entry = bundle.addEntry();
            entry.setResource(resource);
            
            String resourceType = resource.getResourceType().name();
            String resourceId = resource.getIdElement().getIdPart();
            entry.setFullUrl(baseUrl + "/" + resourceType + "/" + resourceId);
            entry.getSearch().setMode(Bundle.SearchEntryMode.INCLUDE);
        }
        
        // Add self link (use the patient ID extracted from request)
        bundle.addLink()
            .setRelation("self")
            .setUrl(baseUrl + "/Patient/" + patientId + "/$everything?_page=" + continuationToken);
        
        // Add next link if there are more pages
        int pageSize = (count != null && count > 0) ? count : paginationState.getPageSize();
        int nextOffset = offset + pageResources.size();
        boolean hasMoreResults = paginationState.getAllDocumentKeys() != null 
            && nextOffset < paginationState.getAllDocumentKeys().size();
        
        if (hasMoreResults) {
            String nextUrl = baseUrl + "/Patient/" + patientId + "/$everything?_page=" + continuationToken 
                           + "&_offset=" + nextOffset 
                           + "&_count=" + pageSize;
            
            bundle.addLink()
                .setRelation("next")
                .setUrl(nextUrl);
        }
        
        // Note: We rely on TTL for cleanup (no explicit delete to avoid unnecessary DB chatter)
        
        // Calculate current page for logging (1-based)
        int currentPage = (offset / pageSize) + 1;
        int totalPages = (paginationState.getAllDocumentKeys() != null) 
            ? (int) Math.ceil((double) paginationState.getAllDocumentKeys().size() / pageSize)
            : 0;
        
        logger.info("✅ $everything continuation returning {} resources (page {}/{})", 
                   pageResources.size(), currentPage, totalPages);
        return bundle;
    }
    
    /**
     * Extract base URL from request details
     * 
     * IMPORTANT: Always prioritize the configured base URL from config.yaml to ensure
     * the correct protocol (https vs http) is preserved, especially when behind
     * a reverse proxy that terminates SSL.
     */
    private String extractBaseUrl(RequestDetails requestDetails, String bucketName) {
        // Always use configured base URL to preserve protocol (https vs http)
        // This is critical when behind HAProxy or other reverse proxies that terminate SSL
        String configuredBaseUrl = fhirServerConfig.getNormalizedBaseUrl();
        if (configuredBaseUrl != null && !configuredBaseUrl.equals("http://localhost/fhir")) {
            return configuredBaseUrl;
        }
        
        // Only fall back to request URL if no base URL is configured (development mode)
        if (requestDetails != null) {
            String serverBase = requestDetails.getFhirServerBase();
            if (serverBase != null && !serverBase.isBlank()) {
                return serverBase;
            }
        }
        
        // Final fallback
        if (configuredBaseUrl != null) {
            return configuredBaseUrl;
        }
        throw new IllegalStateException("Missing FHIR server base URL (configure app.baseUrl / server base)");
    }
    
    /**
     * Extract patient ID from the request URL
     * For $everything requests, URL is like: /Patient/{id}/$everything
     */
    private String extractPatientIdFromRequest(RequestDetails requestDetails) {
        if (requestDetails != null) {
            String requestPath = requestDetails.getRequestPath();
            if (requestPath != null && requestPath.contains("/Patient/")) {
                // Extract patient ID from path like: /fhir/acme/Patient/example/$everything
                int patientIndex = requestPath.indexOf("/Patient/");
                if (patientIndex >= 0) {
                    String afterPatient = requestPath.substring(patientIndex + "/Patient/".length());
                    // Patient ID is between /Patient/ and the next /
                    int nextSlash = afterPatient.indexOf("/");
                    if (nextSlash > 0) {
                        return afterPatient.substring(0, nextSlash);
                    } else {
                        // No trailing slash, return the rest
                        return afterPatient;
                    }
                }
            }
        }
        return "unknown";
    }

    /**
     * Format date for URL parameter (simple ISO format)
     */
    private String formatDate(Date date) {
        return new java.text.SimpleDateFormat("yyyy-MM-dd").format(date);
    }

    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }
}
