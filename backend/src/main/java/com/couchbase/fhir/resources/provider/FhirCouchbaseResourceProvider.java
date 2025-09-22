package com.couchbase.fhir.resources.provider;

import ca.uhn.fhir.context.*;
import ca.uhn.fhir.rest.annotation.*;
import ca.uhn.fhir.rest.api.MethodOutcome;
import ca.uhn.fhir.rest.api.server.IBundleProvider;
import ca.uhn.fhir.rest.api.server.RequestDetails;
import ca.uhn.fhir.rest.param.DateParam;
import ca.uhn.fhir.rest.param.NumberParam;
import ca.uhn.fhir.rest.param.StringParam;
import ca.uhn.fhir.rest.server.IResourceProvider;
import ca.uhn.fhir.rest.server.SimpleBundleProvider;
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


    public FhirCouchbaseResourceProvider(Class<T> resourceClass, FhirResourceDaoImpl<T> dao , FhirContext fhirContext, FhirSearchParameterPreprocessor searchPreprocessor, FhirBucketValidator bucketValidator, FhirBucketConfigService configService, FhirValidator strictValidator, FhirValidator lenientValidator, com.couchbase.admin.connections.service.ConnectionService connectionService, com.couchbase.fhir.resources.service.PutService putService, com.couchbase.fhir.resources.service.DeleteService deleteService, FhirMetaHelper metaHelper, com.couchbase.fhir.resources.service.SearchService searchService, com.couchbase.fhir.resources.service.PatchService patchService, com.couchbase.fhir.resources.service.ConditionalPutService conditionalPutService) {
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
    }

    @Read
    public T read(@IdParam IdType theId) {
        String bucketName = TenantContextHolder.getTenantId();
        
        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        return dao.read(getResourceTypeName(), theId.getIdPart() , bucketName).orElseThrow(() ->
                new ResourceNotFoundException(theId));
    }


    @Read(version = true)
    public T readVersion(@IdParam IdType id) {
        String logicalId = id.getIdPart();
        String versionId = id.getVersionIdPart();

        String bucketName = TenantContextHolder.getTenantId();
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }


        if(versionId != null){
            return dao.readVersion(getResourceTypeName(),versionId ,  logicalId , bucketName).orElseThrow(() ->
                    new ResourceNotFoundException(logicalId));
        }else{
            return dao.read(getResourceTypeName(), logicalId , bucketName).orElseThrow(() ->
                    new ResourceNotFoundException(logicalId));
        }

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
            String profileUrl = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-" + 
                                resource.getClass().getSimpleName().toLowerCase();
            profiles = List.of(profileUrl);
        }
        
        MetaRequest metaRequest = MetaRequest.forCreate(null, "1", profiles);
        metaHelper.applyMeta(resource, metaRequest);
        
        if (profiles != null && !profiles.isEmpty()) {
            logger.debug("🔍 ResourceProvider: Added US Core profile: {}", profiles.get(0));
        }
        
        logger.debug("🔍 ResourceProvider: Processing {} for bucket: {}", getResourceTypeName(), bucketName);
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
                    logger.warn("FHIR Validation warnings for {} (lenient mode - continuing):", getResourceTypeName());
                    result.getMessages().forEach(msg -> 
                        logger.warn("  {}: {} - {}", msg.getSeverity(), msg.getLocationString(), msg.getMessage())
                    );
                }
            } else {
                logger.debug("🔍 ResourceProvider: ✅ FHIR validation PASSED for {} in bucket {}", getResourceTypeName(), bucketName);
            }
        } else {
            logger.debug("🔍 ResourceProvider: FHIR Validation DISABLED for bucket: {}", bucketName);
        }


        T created =  dao.create( resource.getResourceType().toString() , resource , bucketName).orElseThrow(() ->
                new InternalErrorException("Failed to create resource"));
        MethodOutcome outcome = new MethodOutcome();
        outcome.setCreated(true);
        outcome.setResource(created);
        outcome.setId(new IdType(resource.getResourceType().toString(), created.getIdElement().getIdPart()));
        return outcome;
    }


    @History
    public IBundleProvider history(@IdParam IdType theId , @Since DateParam since) {
        String bucketName = TenantContextHolder.getTenantId();

        // Validate FHIR bucket before proceeding
        try {
            bucketValidator.validateFhirBucketOrThrow(bucketName, "default");
        } catch (FhirBucketValidationException e) {
            throw new ca.uhn.fhir.rest.server.exceptions.InvalidRequestException(e.getMessage());
        }

        Resource currentResource= dao.read(getResourceTypeName(), theId.getIdPart() , bucketName).orElseThrow(() ->
                new ResourceNotFoundException(theId));

        @SuppressWarnings("unchecked")
        List<Resource> olderVersions = (List<Resource>) dao.history(getResourceTypeName(), theId.getIdPart() , since , bucketName);

        List<Resource> allVersions = new ArrayList<>();
        allVersions.add(currentResource);
        allVersions.addAll(olderVersions);
        return new SimpleBundleProvider(allVersions);
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

        String resourceType = resource.getResourceType().toString();
        
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
        String resourceType = resource.getResourceType().toString();
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

        String resourceType = getResourceTypeName();
        
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
            com.couchbase.fhir.resources.service.ResolveResult resolveResult = searchService.resolveOne(resourceType, searchCriteria);

            // com.couchbase.fhir.resources.service.ResolveResult resolveResult = 
            //     searchService.resolveOne(resourceType, searchCriteria);
            
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
        String resourceType = getResourceTypeName();
        
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
        String resourceType = getResourceTypeName();
        
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
            
            return searchService.handleRevIncludePagination(continuationToken, offset, count);
        }

        logger.debug("🔍 ResourceProvider: Delegating search for {} to SearchService", resourceType);

        // Delegate to SearchService for all search operations
        return searchService.search(resourceType, requestDetails);
    }
    /**
     * FHIR $validate Operation - Validates a resource without storing it
     * POST /fhir/{bucket}/{ResourceType}/$validate
     */
    @Operation(name = "$validate", idempotent = false)
    public OperationOutcome validateResource(@ResourceParam T resource) {
        try {
            // Create a FHIR validator
            FhirValidator validator = fhirContext.newValidator();
            
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

    @Override
    public Class<T> getResourceType() {
        return resourceClass;
    }

    public String getResourceTypeName(){
        try{
             return  ((Resource) resourceClass.getDeclaredConstructor().newInstance())
                    .getResourceType().toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
