package com.couchbase.admin.initialization.service;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.admin.initialization.model.InitializationStatus;
import com.couchbase.admin.initialization.model.InitializationStatus.Status;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.manager.collection.CollectionSpec;
import com.couchbase.fhir.auth.AuthorizationServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service to check FHIR system initialization status
 * In single-tenant mode, we expect exactly one bucket named "fhir"
 */
@Service
public class InitializationService {
    
    private static final Logger logger = LoggerFactory.getLogger(InitializationService.class);
    private static final String FHIR_BUCKET_NAME = "fhir";
    private static final String DEFAULT_CONNECTION_NAME = "default";
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired(required = false)  // Optional - only needed if OAuth is configured
    private AuthorizationServerConfig authorizationServerConfig;
    
    /**
     * Check the initialization status of the FHIR system
     * @param connectionName The connection name to check (default: "default")
     * @return InitializationStatus with detailed status information
     */
    public InitializationStatus checkStatus(String connectionName) {
        InitializationStatus status = new InitializationStatus();
        status.setBucketName(FHIR_BUCKET_NAME);
        
        // Step 1: Check if connection exists
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            status.setStatus(Status.NOT_CONNECTED);
            status.setMessage("No connection to Couchbase established. Please check config.yaml and restart.");
            status.setHasConnection(false);
            status.setBucketExists(false);
            status.setFhirInitialized(false);
            return status;
        }
        
        status.setHasConnection(true);
        logger.debug("✅ Connection exists: {}", connectionName);
        
        // Step 2: Check if "fhir" bucket exists
        boolean bucketExists = checkBucketExists(cluster, FHIR_BUCKET_NAME);
        status.setBucketExists(bucketExists);
        
        if (!bucketExists) {
            status.setStatus(Status.BUCKET_MISSING);
            status.setMessage(String.format(
                "Bucket '%s' does not exist. Please create it manually in Couchbase UI or CLI, then click 'Initialize'.",
                FHIR_BUCKET_NAME
            ));
            status.setFhirInitialized(false);
            
            // CRITICAL: Invalidate OAuth key cache when bucket is missing
            // This ensures old JWTs stop working after bucket is dropped
            if (authorizationServerConfig != null) {
                logger.warn("⚠️ Bucket missing - invalidating OAuth key cache to reject old JWTs");
                authorizationServerConfig.invalidateKeyCache();
            }
            
            return status;
        }
        
        logger.debug("✅ Bucket '{}' exists", FHIR_BUCKET_NAME);

        boolean adminInitialized = checkAdminInitialization(cluster, FHIR_BUCKET_NAME);
        status.setAdminInitialized(adminInitialized);
        
        // Step 3: Check if bucket is FHIR-initialized (has Admin.config.fhir-config document)
        boolean isFhirInitialized = checkFhirInitialization(cluster, FHIR_BUCKET_NAME, connectionName);
        status.setFhirInitialized(isFhirInitialized);
        
        if (!isFhirInitialized) {
            status.setStatus(Status.BUCKET_NOT_INITIALIZED);
            status.setMessage(String.format(
                "Bucket '%s' exists but is not FHIR-initialized. Click 'Initialize' to create scopes, collections, and indexes.",
                FHIR_BUCKET_NAME
            ));
            
            // CRITICAL: Invalidate OAuth key cache when bucket is not initialized
            // This ensures old JWTs stop working if bucket was re-created empty
            if (authorizationServerConfig != null) {
                logger.warn("⚠️ Bucket not initialized - invalidating OAuth key cache to reject old JWTs");
                authorizationServerConfig.invalidateKeyCache();
            }
            
            return status;
        }
        
        logger.debug("✅ Bucket '{}' is FHIR-initialized", FHIR_BUCKET_NAME);
        
        // All checks passed - system is ready!
        status.setStatus(Status.READY);
        status.setMessage("FHIR system is fully initialized and ready.");
        
        return status;
    }

    /**
     * Check if the Admin scope and required collections exist.
     */
    public boolean checkAdminInitialization(Cluster cluster, String bucketName) {
        try {
            var collectionManager = cluster.bucket(bucketName).collections();
            var scopes = collectionManager.getAllScopes();

            var adminScopeOpt = scopes.stream()
                .filter(scope -> "Admin".equals(scope.name()))
                .findFirst();

            if (adminScopeOpt.isEmpty()) {
                return false;
            }

            Set<String> expected = new HashSet<>(List.of("config", "users", "tokens", "clients", "cache", "bulk_groups"));
            Set<String> existing = adminScopeOpt.get().collections().stream()
                .map(CollectionSpec::name)
                .collect(Collectors.toSet());

            return existing.containsAll(expected);
        } catch (Exception e) {
            logger.warn("⚠️ Failed to check Admin initialization for bucket '{}': {}", bucketName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if a bucket exists in the cluster
     */
    private boolean checkBucketExists(Cluster cluster, String bucketName) {
        try {
            String sql = String.format(
                "SELECT name FROM system:buckets WHERE namespace_id = 'default' AND name = '%s'",
                bucketName
            );
            var result = cluster.query(sql);
            return result.rowsAsObject().size() > 0;
        } catch (Exception e) {
            logger.error("Failed to check if bucket '{}' exists: {}", bucketName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Check if bucket is FHIR-initialized by looking for Admin.config.fhir-config document
     * Uses REST API to avoid SDK retry issues
     */
    private boolean checkFhirInitialization(Cluster cluster, String bucketName, String connectionName) {
        try {
            // Use SDK's HTTP client to check for fhir-config document
            com.couchbase.client.java.http.CouchbaseHttpClient httpClient = cluster.httpClient();
            
            // Construct the REST path for the fhir-config document
            com.couchbase.client.java.http.HttpResponse httpResponse = httpClient.get(
                com.couchbase.client.java.http.HttpTarget.manager(),
                com.couchbase.client.java.http.HttpPath.of(
                    "/pools/default/buckets/{}/scopes/Admin/collections/config/docs/fhir-config",
                    bucketName
                )
            );
            
            int statusCode = httpResponse.statusCode();
            
            // 200 = document exists (FHIR-initialized)
            // 404 = document doesn't exist (not FHIR-initialized)
            boolean initialized = (statusCode == 200);
            
            logger.debug("FHIR config check for bucket '{}': status={}, initialized={}", 
                bucketName, statusCode, initialized);
            
            return initialized;
            
        } catch (Exception e) {
            logger.error("Failed to check FHIR initialization for bucket '{}': {}", bucketName, e.getMessage());
            return false;
        }
    }
    
    /**
     * Get the expected FHIR bucket name for single-tenant mode
     */
    public String getFhirBucketName() {
        return FHIR_BUCKET_NAME;
    }
    
    /**
     * Get the default connection name
     */
    public String getDefaultConnectionName() {
        return DEFAULT_CONNECTION_NAME;
    }
}

