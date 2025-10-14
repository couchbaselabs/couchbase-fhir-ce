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
import org.springframework.core.io.Resource;
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
    private static final String FTS_INDEXES_PATH = "classpath*:/fts-indexes/*.json";
    private final ObjectMapper objectMapper = new ObjectMapper();
    
    @Autowired
    private ConnectionService connectionService;

    
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
            PathMatchingResourcePatternResolver resolver = 
                new PathMatchingResourcePatternResolver(FtsIndexCreator.class.getClassLoader());
            Resource[] resources = resolver.getResources(FTS_INDEXES_PATH);
            
            if (resources.length == 0) {
                logger.warn("⚠️ No FTS index files found at: {}", FTS_INDEXES_PATH);
                return;
            }
            
            logger.info("📋 Found {} FTS index files", resources.length);
            
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
        String hostname = connectionService.getHostname(connectionName);

        if (cluster == null) {
            throw new IllegalStateException("Connection not found: " + connectionName);
        }

        String apiPath;
        String localEnv = System.getenv("LOCAL_ENV");
        if (localEnv != null && !localEnv.isEmpty()) {
            // Use new API path for local env
            apiPath = String.format("/api/index/%s", indexName);
            // Parse and modify indexJson
            ObjectMapper mapper = new ObjectMapper();
            JsonNode jsonNode = mapper.readTree(indexJson);
            if (jsonNode.isObject()) {
                ObjectNode obj = (ObjectNode) jsonNode;
                obj.put("name", indexName);
                obj.put("sourceName", bucketName);
                indexJson = mapper.writeValueAsString(obj);
            }
        } else {
            // Default behavior (remote/production)
            String scopeName = extractScopeFromIndexName(indexName);
            apiPath = String.format("/api/bucket/%s/scope/%s/index/%s", bucketName, scopeName, indexName);
        }

        logger.info("🔗 Creating FTS index '{}' via Couchbase SDK HTTP client: {}, with hostname: {}", indexName, apiPath, hostname);

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
    
    
}
