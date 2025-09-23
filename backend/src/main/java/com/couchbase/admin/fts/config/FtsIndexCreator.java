package com.couchbase.admin.fts.config;

import com.couchbase.admin.connections.service.ConnectionService;
import com.couchbase.client.java.Cluster;
import com.couchbase.client.java.http.CouchbaseHttpClient;
import com.couchbase.client.java.http.HttpResponse;
import com.couchbase.client.java.http.HttpTarget;
import com.couchbase.client.java.http.HttpPath;
import com.couchbase.client.java.http.HttpPutOptions;
import com.couchbase.client.java.http.HttpBody;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * FTS Index Creator - Loads individual JSON files and creates FTS indexes
 * 
 * Each JSON file should be a direct copy from Couchbase console.
 * This component will automatically:
 * - Replace bucket names with the target bucket
 * - Clear UUIDs for new index creation
 * - Clear sourceUUID for new index creation
 * - Create or update indexes using Couchbase SDK
 */
@Configuration
public class FtsIndexCreator {
    
    private static final Logger logger = LoggerFactory.getLogger(FtsIndexCreator.class);
    private static final String FTS_INDEXES_PATH = "classpath*:fts-indexes/*.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private ConnectionService connectionService;
    
    @Autowired
    private ResourceLoader resourceLoader;
    

    
    /**
     * Process JSON for index creation - replace bucket names and clear UUIDs
     */
    public String processJsonForCreation(JsonNode jsonNode, String targetBucketName, String filename) throws Exception {
        // Create a mutable copy of the JSON
        ObjectNode mutableJson = jsonNode.deepCopy();
        
        // 1. Clear UUID for new index creation
        mutableJson.put("uuid", "");
        
        // 2. Clear sourceUUID for new index creation  
        mutableJson.put("sourceUUID", "");
        
        // 3. Update sourceName to target bucket
        mutableJson.put("sourceName", targetBucketName);
        
        // 4. Generate index name from filename and bucket
        String indexName = extractIndexNameFromFilename(filename);
        String fullIndexName = targetBucketName + ".Resources." + indexName;
        mutableJson.put("name", fullIndexName);
        
        return objectMapper.writeValueAsString(mutableJson);
    }
    
    /**
     * Extract index name from filename
     * Example: "ftsAllergyIntolerance.json" -> "ftsAllergyIntolerance"
     */
    private String extractIndexNameFromFilename(String filename) {
        if (filename.endsWith(".json")) {
            return filename.substring(0, filename.length() - 5);
        }
        return filename;
    }
    
    
    /**
     * Create FTS index using REST API - to be called during bucket creation
     */
    public void createFtsIndex(String connectionName, String jsonFilePath, String bucketName) {
        try {
            logger.info("🚀 Creating FTS index from file: {} for bucket: {}", jsonFilePath, bucketName);
            
            // Read JSON file
            Path path = Paths.get(jsonFilePath);
            String jsonContent = Files.readString(path);
            
            // Extract filename from path
            String filename = path.getFileName().toString();
            
            // Parse and process JSON
            JsonNode jsonNode = objectMapper.readTree(jsonContent);
            String processedJson = processJsonForCreation(jsonNode, bucketName, filename);
            JsonNode processedNode = objectMapper.readTree(processedJson);
            
            // Extract just the index name part (not fully qualified)
            String fullIndexName = processedNode.get("name").asText();
            String indexName = extractIndexNameOnly(fullIndexName);
            
            // Create index via REST API
            createFtsIndexViaRest(connectionName, bucketName, indexName, processedJson);
            
            logger.info("✅ FTS index created/updated: {}", indexName);
            
        } catch (Exception e) {
            logger.error("❌ Failed to create FTS index from file: {}", jsonFilePath, e);
            throw new RuntimeException("FTS index creation failed", e);
        }
    }
    
    /**
     * Create all FTS indexes for a bucket
     */
    public void createAllFtsIndexesForBucket(String connectionName, String bucketName) {
        try {
            logger.info("🔄 Creating all FTS indexes for bucket: {}", bucketName);
            
            // Find all JSON files in fts-indexes directory
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            Resource[] resources;
            
            try {
                resources = resolver.getResources(FTS_INDEXES_PATH);
                if (resources.length == 0) {
                    logger.warn("⚠️ No FTS index files found at: {}", FTS_INDEXES_PATH);
                    return;
                }
            } catch (Exception e) {
                logger.error("❌ Failed to load FTS index files from: {} - {}", FTS_INDEXES_PATH, e.getMessage());
                // Try alternative path without trailing slash
                try {
                    String alternativePath = "classpath:fts-indexes/*.json";
                    logger.info("🔄 Trying alternative path: {}", alternativePath);
                    resources = resolver.getResources(alternativePath);
                    if (resources.length == 0) {
                        logger.error("❌ No FTS index files found at alternative path either. Skipping FTS index creation.");
                        return;
                    }
                } catch (Exception e2) {
                    logger.error("❌ Alternative path also failed: {}", e2.getMessage());
                    // Try loading individual files by name as a fallback
                    logger.info("🔄 Trying to load individual FTS index files by name...");
                    resources = loadFtsIndexFilesByName();
                    if (resources.length == 0) {
                        logger.error("❌ No FTS index files could be loaded. Skipping FTS index creation.");
                        return;
                    }
                }
            }
            
            int successCount = 0;
            int skippedCount = 0;
            int failCount = 0;
            
            for (Resource resource : resources) {
                try {
                    // Read JSON content
                    String jsonContent = new String(resource.getInputStream().readAllBytes());
                    
                    // Parse and process JSON
                    JsonNode jsonNode = objectMapper.readTree(jsonContent);
                    String processedJson = processJsonForCreation(jsonNode, bucketName, resource.getFilename());
                    JsonNode processedNode = objectMapper.readTree(processedJson);
                    
                    // Extract just the index name part (not fully qualified)
                    String fullIndexName = processedNode.get("name").asText();
                    String indexName = extractIndexNameOnly(fullIndexName);
                    
                    // Create index via REST API
                    try {
                        createFtsIndexViaRest(connectionName, bucketName, indexName, processedJson);
                        logger.info("✅ Created FTS index: {} from file: {}", indexName, resource.getFilename());
                        successCount++;
                    } catch (RuntimeException e) {
                        if (e.getMessage().contains("index with the same name already exists")) {
                            logger.info("⚠️ Skipped FTS index: {} from file: {} (already exists)", indexName, resource.getFilename());
                            skippedCount++;
                        } else {
                            throw e; // Re-throw other runtime exceptions
                        }
                    }
                    
                } catch (Exception e) {
                    logger.error("❌ Failed to create FTS index from file: {}", resource.getFilename(), e);
                    failCount++;
                }
            }
            
            logger.info("🎯 FTS index creation completed for bucket '{}': {} created, {} skipped (already exist), {} failed", 
                       bucketName, successCount, skippedCount, failCount);
            
        } catch (Exception e) {
            logger.error("❌ Failed to create FTS indexes for bucket: {}", bucketName, e);
            throw new RuntimeException("FTS index creation failed for bucket: " + bucketName, e);
        }
    }
    
    /**
     * Create FTS index via REST API using Couchbase SDK's HTTP client
     * PUT /api/bucket/{BUCKET_NAME}/scope/{SCOPE_NAME}/index/{INDEX_NAME}
     */
    private void createFtsIndexViaRest(String connectionName, String bucketName, String indexName, String indexJson) throws Exception {
        // Get the active cluster connection
        Cluster cluster = connectionService.getConnection(connectionName);
        if (cluster == null) {
            throw new IllegalStateException("Connection not found: " + connectionName);
        }
        
        // Extract scope from index name (e.g., "bucket.Resources.ftsName" -> "Resources")
        String scopeName = extractScopeFromIndexName(indexName);
        
        // Build FTS API path - the SDK's HTTP client handles the full URL construction
        String apiPath = String.format("/api/bucket/%s/scope/%s/index/%s", bucketName, scopeName, indexName);
        
        logger.info("🔗 Creating FTS index '{}' via Couchbase SDK HTTP client: {}", indexName, apiPath);
        
        // Use the cluster's HTTP client - this handles SSL certificates and authentication automatically
        CouchbaseHttpClient httpClient = cluster.httpClient();
        
        // Send PUT request using Couchbase SDK's HTTP client
        HttpResponse response = httpClient.put(
            HttpTarget.search(), // Use the search (FTS) target
            HttpPath.of(apiPath),
            HttpPutOptions.httpPutOptions()
                .body(HttpBody.json(indexJson))
                .header("Content-Type", "application/json")
        );
        
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            logger.info("✅ FTS index created successfully: {}", indexName);
        } else if (response.statusCode() == 400 && 
                   response.contentAsString().contains("index with the same name already exists")) {
            // Handle the case where index already exists - this should be an update but Couchbase is rejecting it
            logger.warn("⚠️ FTS index '{}' already exists - skipping creation (this should have been an update)", indexName);
            logger.debug("Response body: {}", response.contentAsString());
        } else {
            logger.error("❌ FTS index creation failed: {} - Status: {}, Body: {}", 
                        indexName, response.statusCode(), response.contentAsString());
            throw new RuntimeException("FTS index creation failed with status: " + response.statusCode());
        }
    }
    
    /**
     * Extract scope name from full index name
     * Example: "bucket.Resources.ftsName" -> "Resources"
     */
    private String extractScopeFromIndexName(String indexName) {
        String[] parts = indexName.split("\\.", 3);
        if (parts.length >= 2) {
            return parts[1]; // Return the scope part
        }
        return "Resources"; // Default fallback
    }
    
    /**
     * Extract just the index name from full index name
     * Example: "bucket.Resources.ftsClaim" -> "ftsClaim"
     * Example: "test.Resources.fts-claim-index" -> "fts-claim-index"
     */
    private String extractIndexNameOnly(String fullIndexName) {
        String[] parts = fullIndexName.split("\\.", 3);
        if (parts.length >= 3) {
            return parts[2]; // Return just the index name part
        } else if (parts.length == 2) {
            return parts[1]; // If only 2 parts, return the second part
        } else {
            return fullIndexName; // If not qualified, return as-is
        }
    }
    
    /**
     * Fallback method to load FTS index files by known filenames
     * This is used when the pattern-based loading fails
     */
    private Resource[] loadFtsIndexFilesByName() {
        String[] knownFiles = {
            "ftsCondition.json",
            "ftsDiagnosticReport.json", 
            "ftsDocumentReference.json",
            "ftsEncounter.json",
            "ftsGeneral.json",
            "ftsImmunization.json",
            "ftsMedicationRequest.json",
            "ftsObservation.json",
            "ftsPatient.json",
            "ftsProcedure.json",
            "ftsServiceRequest.json",
            "ftsVersions.json"
        };
        
        java.util.List<Resource> resourceList = new java.util.ArrayList<>();
        
        for (String filename : knownFiles) {
            boolean found = false;
            try {
                // Try multiple approaches to load the resource
                String[] pathsToTry = {
                    "classpath:fts-indexes/" + filename,
                    "classpath:/fts-indexes/" + filename,
                    "fts-indexes/" + filename,
                    "/fts-indexes/" + filename
                };
                
                for (String path : pathsToTry) {
                    try {
                        Resource resource;
                        if (path.startsWith("classpath:")) {
                            resource = resourceLoader.getResource(path);
                        } else {
                            resource = new ClassPathResource(path);
                        }
                        
                        logger.debug("🔍 Trying path: {} for file: {} (exists: {})", path, filename, resource.exists());
                        
                        if (resource.exists()) {
                            resourceList.add(resource);
                            logger.debug("✅ Found FTS index file: {} via path: {}", filename, path);
                            found = true;
                            break;
                        }
                    } catch (Exception pathException) {
                        logger.debug("⚠️ Path failed: {} - {}", path, pathException.getMessage());
                    }
                }
                
                if (!found) {
                    logger.debug("⚠️ FTS index file not found after trying all paths: {}", filename);
                }
                
            } catch (Exception e) {
                logger.warn("❌ Failed to load FTS index file: {} - {}", filename, e.getMessage());
            }
        }
        
        logger.info("📋 Loaded {} FTS index files using fallback method", resourceList.size());
        return resourceList.toArray(new Resource[0]);
    }
    
}
