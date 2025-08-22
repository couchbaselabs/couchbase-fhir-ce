package com.couchbase.fhir.resources.service;

import ca.uhn.fhir.parser.IParser;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.json.JsonObject;
import com.couchbase.client.java.query.QueryResult;
import org.hl7.fhir.r4.model.Meta;
import org.hl7.fhir.r4.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

/**
 * Service for handling FHIR PUT operations (create or update resources with client-controlled IDs).
 * PUT operations always use the client-supplied ID and handle proper FHIR versioning.
 * This service handles both standalone transactions and nested transactions (from Bundle).
 */
@Service
public class PutService {
    
    private static final Logger logger = LoggerFactory.getLogger(PutService.class);
    private static final String DEFAULT_SCOPE = "Resources";
    private static final String VERSIONS_COLLECTION = "Versions";
    
    @Autowired
    private IParser jsonParser;
    
    @Autowired
    private FhirAuditService auditService;
    
    @Autowired
    private DeleteService deleteService;
    
    /**
     * Create or update a FHIR resource via PUT operation.
     * Always uses the client-supplied ID and handles proper versioning.
     * 
     * @param resource The FHIR resource to create/update (must have client-supplied ID)
     * @param context The transaction context (standalone or nested)
     * @return The created/updated resource with proper versioning metadata
     */
    public Resource updateOrCreateResource(Resource resource, TransactionContext context) {
        String resourceType = resource.getResourceType().name();
        String clientId = resource.getIdElement().getIdPart();
        
        if (clientId == null || clientId.trim().isEmpty()) {
            throw new IllegalArgumentException("PUT operation requires a client-supplied ID");
        }
        
        String documentKey = resourceType + "/" + clientId;
        logger.info("🔄 PUT {}: Using client-supplied ID {}", resourceType, clientId);
        
        // ✅ FHIR Compliance: Check if ID was previously deleted (tombstoned)
        // Per FHIR spec, cannot reuse deleted resource IDs
        if (deleteService.isTombstoned(resourceType, clientId, context.getCluster(), context.getBucketName())) {
            logger.warn("🚫 PUT {}: ID was previously deleted and cannot be reused", documentKey);
            throw new ca.uhn.fhir.rest.server.exceptions.ResourceVersionConflictException(
                "Resource ID " + clientId + " was previously deleted and cannot be reused. Please choose a new ID."
            );
        }
        
        if (context.isInTransaction()) {
            // Operate within existing Bundle transaction
            return updateResourceInTransaction(resource, documentKey, context);
        } else {
            // Create standalone transaction for this PUT operation
            return updateResourceWithStandaloneTransaction(resource, documentKey, context);
        }
    }
    
    /**
     * Handle PUT operation within existing transaction (Bundle context)
     */
    private Resource updateResourceInTransaction(Resource resource, String documentKey, TransactionContext context) {
        String resourceType = resource.getResourceType().name();
        
        try {
            // Handle versioning and update within the existing transaction
            handleVersioningAndUpdate(resource, documentKey, context.getTransactionContext(), 
                                    context.getCluster(), context.getBucketName());
            
            logger.info("✅ PUT {} (in transaction): Updated resource {}", resourceType, documentKey);
            return resource;
            
        } catch (Exception e) {
            logger.error("❌ PUT {} (in transaction) failed: {}", resourceType, e.getMessage());
            throw new RuntimeException("PUT operation failed in transaction: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle PUT operation with standalone transaction
     */
    private Resource updateResourceWithStandaloneTransaction(Resource resource, String documentKey, TransactionContext context) {
        String resourceType = resource.getResourceType().name();
        
        try {
            // Create standalone transaction for this PUT operation
            context.getCluster().transactions().run(txContext -> {
                handleVersioningAndUpdate(resource, documentKey, txContext, 
                                        context.getCluster(), context.getBucketName());
            });
            
            logger.info("✅ PUT {}: Updated resource {} (standalone transaction)", resourceType, documentKey);
            return resource;
            
        } catch (Exception e) {
            logger.error("❌ PUT {} (standalone transaction) failed: {}", resourceType, e.getMessage());
            throw new RuntimeException("PUT operation failed: " + e.getMessage(), e);
        }
    }
    
    /**
     * Handle FHIR versioning and resource update within transaction context
     */
    private void handleVersioningAndUpdate(Resource resource, String documentKey,
                                         com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                         Cluster cluster, String bucketName) {
        String resourceType = resource.getResourceType().name();
        String resourceId = resource.getIdElement().getIdPart();
        
        // Step 1: Copy existing resource to Versions collection (if it exists) and get next version
        int nextVersion = copyExistingResourceToVersions(cluster, bucketName, resourceType, documentKey);
        
        if (nextVersion > 1) {
            logger.info("📋 PUT {}: Resource exists, copied to Versions, version {} → {}", 
                       documentKey, nextVersion - 1, nextVersion);
            updateResourceMetadata(resource, String.valueOf(nextVersion), "UPDATE");
        } else {
            logger.info("🆕 PUT {}: Creating new resource (version 1)", documentKey);
            updateResourceMetadata(resource, "1", "CREATE");
        }
        
        // Step 2: UPSERT the resource with new/updated version
        upsertResourceInTransaction(txContext, cluster, bucketName, resourceType, documentKey, resource);
    }
    
    /**
     * Copy existing resource to Versions collection using efficient N1QL and return next version number
     * Uses your SQL pattern: INSERT INTO Versions SELECT CONCAT(META(p).id, '/', p.meta.versionId), p FROM Patient p WHERE META(p).id = 'Patient/1001'
     * 
     * @return Next version number (1 if resource doesn't exist, current+1 if it does)
     */
    private int copyExistingResourceToVersions(Cluster cluster, String bucketName, String resourceType, String documentKey) {
        try {
            // Use efficient N1QL to copy existing resource to Versions collection
            String sql = String.format(
                "INSERT INTO `%s`.`%s`.`%s` (KEY k, VALUE v) " +
                "SELECT " +
                "    CONCAT(META(r).id, '/', IFNULL(r.meta.versionId, '1')) AS k, " +
                "    r AS v " +
                "FROM `%s`.`%s`.`%s` r " +
                "WHERE META(r).id = '%s'",
                bucketName, DEFAULT_SCOPE, VERSIONS_COLLECTION,  // INSERT INTO Versions
                bucketName, DEFAULT_SCOPE, resourceType,         // FROM ResourceType
                documentKey                                      // WHERE META(r).id = 'Patient/1001'
            );
            
            logger.debug("🔄 Copying existing resource to Versions: {}", sql);
            QueryResult result = cluster.query(sql);
            
            // Check how many rows were inserted (0 = resource doesn't exist, 1 = resource existed and copied)
            if (result.metaData().metrics().isPresent()) {
                long mutationCount = result.metaData().metrics().get().mutationCount();
                
                if (mutationCount > 0) {
                    // Resource existed and was copied - get current version and increment
                    int currentVersion = getCurrentVersion(cluster, bucketName, resourceType, documentKey);
                    int nextVersion = currentVersion + 1;
                    logger.debug("📂 Copied resource to Versions, incrementing version {} → {}", currentVersion, nextVersion);
                    return nextVersion;
                } else {
                    // Resource doesn't exist - this will be version 1
                    logger.debug("🆕 Resource doesn't exist, will create as version 1");
                    return 1;
                }
            }
            
            // Fallback - assume resource doesn't exist
            return 1;
            
        } catch (Exception e) {
            logger.debug("Resource {} doesn't exist or copy failed: {}", documentKey, e.getMessage());
            // If copy fails, assume resource doesn't exist (version 1)
            return 1;
        }
    }
    
    /**
     * Get current version of existing resource
     */
    private int getCurrentVersion(Cluster cluster, String bucketName, String resourceType, String documentKey) {
        try {
            String sql = String.format(
                "SELECT IFNULL(r.meta.versionId, '1') AS currentVersion " +
                "FROM `%s`.`%s`.`%s` r " +
                "WHERE META(r).id = '%s'",
                bucketName, DEFAULT_SCOPE, resourceType, documentKey
            );
            
            QueryResult result = cluster.query(sql);
            List<JsonObject> rows = result.rowsAsObject();
            
            if (!rows.isEmpty()) {
                String versionStr = rows.get(0).getString("currentVersion");
                return Integer.parseInt(versionStr);
            }
            
            return 1; // Default version
            
        } catch (Exception e) {
            logger.debug("Failed to get current version for {}: {}", documentKey, e.getMessage());
            return 1; // Default version
        }
    }
    
    /**
     * Update resource metadata (version, lastUpdated, audit info)
     */
    private void updateResourceMetadata(Resource resource, String versionId, String operation) {
        // Add/update Meta information
        if (resource.getMeta() == null) {
            resource.setMeta(new Meta());
        }
        
        resource.getMeta().setVersionId(versionId);
        resource.getMeta().setLastUpdated(new Date());
        
        // Add audit information
        UserAuditInfo auditInfo = auditService.getCurrentUserAuditInfo();
        auditService.addAuditInfoToMeta(resource, auditInfo, operation);
        
        logger.debug("🏷️ Updated metadata: version={}, operation={}", versionId, operation);
    }
    
    /**
     * UPSERT resource in transaction context
     */
    private void upsertResourceInTransaction(com.couchbase.client.java.transactions.TransactionAttemptContext txContext,
                                           Cluster cluster, String bucketName, String resourceType,
                                           String documentKey, Resource resource) {
        try {
            // Get collection reference
            com.couchbase.client.java.Collection collection = 
                cluster.bucket(bucketName).scope(DEFAULT_SCOPE).collection(resourceType);
            
            // Convert to JSON and upsert (try replace first, then insert if not found)
            String resourceJson = jsonParser.encodeResourceToString(resource);
            
            try {
                // First try to get the document for replace
                var existingDoc = txContext.get(collection, documentKey);
                txContext.replace(existingDoc, JsonObject.fromJson(resourceJson));
            } catch (com.couchbase.client.core.error.DocumentNotFoundException e) {
                // Document doesn't exist, use insert
                txContext.insert(collection, documentKey, JsonObject.fromJson(resourceJson));
            }
            
            logger.debug("🔧 Upserted resource in transaction: {}", documentKey);
            
        } catch (Exception e) {
            logger.error("❌ Failed to upsert resource {} in transaction: {}", documentKey, e.getMessage());
            throw new RuntimeException("Failed to update resource: " + e.getMessage(), e);
        }
    }
}
